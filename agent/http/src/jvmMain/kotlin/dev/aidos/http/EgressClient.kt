package dev.aidos.http

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.UUID

/**
 * Immutable audit record for outbound network calls (RFC-0042).
 *
 * Answers "what has left this device, and where did it go" without storing
 * payloads directly in the permanent audit trail.
 */
data class EgressRecord(
    val id: String = UUID.randomUUID().toString(),
    val projectId: String? = null,
    val attemptId: String? = null,
    val capabilityId: String,
    val destinationHost: String,
    val resolvedAddressClass: AddressClass,
    val method: String,
    val requestBytes: Long,
    val responseBytes: Long,
    val payloadHash: String,
    val contentSensitivity: String = "UNKNOWN",
    val runTaint: String = "TRUSTED",
    val durationMs: Long,
    val outcome: EgressOutcome,
    val costUnits: Long? = null,
    val occurredAt: String = java.time.Instant.now().toString()
)

data class EgressRequest(
    val capabilityId: CapabilityId,
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutSeconds: Long = 30L,
    val projectId: String? = null,
    val attemptId: String? = null,
    val contentSensitivity: String = "UNKNOWN",
    val runTaint: String = "TRUSTED",
    val costUnits: Long? = null
)

data class EgressResponse(
    val statusCode: Int,
    val headers: Map<String, List<String>>,
    val body: ByteArray,
    val record: EgressRecord
) {
    val bodyString: String by lazy { String(body, Charsets.UTF_8) }
}

/**
 * Central mediated egress client interface (RFC-0042).
 * All network access leaving the device passes through an implementation of this interface.
 */
interface EgressClient {
    suspend fun request(
        request: EgressRequest,
        scope: CapabilityScope.Network? = null
    ): Result<EgressResponse>

    /** Returns recorded egress audit history. */
    fun getAuditRecords(): List<EgressRecord>
}

/**
 * Default implementation of [EgressClient] with RFC-0042 four-point destination checking.
 */
class DefaultEgressClient(
    val policy: EgressSecurityPolicy = EgressSecurityPolicy(),
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(15))
        .followRedirects(HttpClient.Redirect.NEVER) // Redirects handled explicitly by policy
        .build(),
    private val recordSink: (EgressRecord) -> Unit = {},
) : EgressClient {

    private val auditLog = mutableListOf<EgressRecord>()

    override fun getAuditRecords(): List<EgressRecord> = synchronized(auditLog) {
        auditLog.toList()
    }

    override suspend fun request(
        request: EgressRequest,
        scope: CapabilityScope.Network?
    ): Result<EgressResponse> {
        return withContext(Dispatchers.IO) {
            val effectiveScope = scope ?: policy.defaultScope
            val startTime = System.currentTimeMillis()
            val requestBytes = request.body?.size?.toLong() ?: 0L
            val payloadHash = EgressSecurityPolicy.sha256Hex(request.body ?: ByteArray(0))

            // 1 & 2. Pre-DNS and Post-DNS Destination checks
            val validation = policy.validateDestination(
                urlString = request.url,
                scope = effectiveScope,
                allowWildcardHostIfEmpty = effectiveScope.allowedHosts.isEmpty()
            )

            if (validation is EgressValidationResult.Denied) {
                val host = try { URI.create(request.url).host ?: "unknown" } catch (_: Exception) { "unknown" }
                record(
                    request = request,
                    host = host,
                    addressClass = validation.addressClass ?: AddressClass.RESERVED,
                    method = request.method,
                    requestBytes = requestBytes,
                    responseBytes = 0L,
                    payloadHash = payloadHash,
                    durationMs = System.currentTimeMillis() - startTime,
                    outcome = validation.outcome,
                )
                return@withContext Result.failure(SecurityException("RFC-0042 Egress Denied: ${validation.reason}"))
            }

            val initial = validation as EgressValidationResult.Allowed
            var current = initial
            var redirectsFollowed = 0
            while (true) {
                try {
                    val reqBuilder = HttpRequest.newBuilder()
                        .uri(current.uri)
                        .timeout(Duration.ofSeconds(request.timeoutSeconds))

                    request.headers.forEach { (k, v) -> reqBuilder.setHeader(k, v) }

                    val publisher = if (request.body != null) {
                        HttpRequest.BodyPublishers.ofByteArray(request.body)
                    } else {
                        HttpRequest.BodyPublishers.noBody()
                    }

                    when (request.method.uppercase()) {
                        "GET" -> reqBuilder.GET()
                        "HEAD" -> reqBuilder.method("HEAD", HttpRequest.BodyPublishers.noBody())
                        "POST" -> reqBuilder.POST(publisher)
                        "PUT" -> reqBuilder.PUT(publisher)
                        "DELETE" -> reqBuilder.DELETE()
                        else -> reqBuilder.method(request.method.uppercase(), publisher)
                    }

                    val httpResponse = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofInputStream())
                    val redirectTarget = if (httpResponse.statusCode() in 300..399) {
                        val location = httpResponse.headers().firstValue("Location").orElse(null)
                        if (location != null) {
                            try {
                                val redirectValidation = policy.validateRedirect(
                                    fromUrl = current.uri.toString(),
                                    toLocation = location,
                                    scope = effectiveScope
                                )
                                if (redirectValidation is EgressValidationResult.Denied) {
                                    record(
                                        request = request,
                                        host = current.uri.host ?: "unknown",
                                        addressClass = current.addressClass,
                                        method = request.method,
                                        requestBytes = requestBytes,
                                        responseBytes = 0L,
                                        payloadHash = payloadHash,
                                        durationMs = System.currentTimeMillis() - startTime,
                                        outcome = redirectValidation.outcome,
                                    )
                                    return@withContext Result.failure(
                                        SecurityException("RFC-0042 Egress Denied: ${redirectValidation.reason}")
                                    )
                                }
                                if (redirectsFollowed++ >= 5) {
                                    record(
                                        request = request,
                                        host = current.uri.host ?: "unknown",
                                        addressClass = current.addressClass,
                                        method = request.method,
                                        requestBytes = requestBytes,
                                        responseBytes = 0L,
                                        payloadHash = payloadHash,
                                        durationMs = System.currentTimeMillis() - startTime,
                                        outcome = EgressOutcome.BLOCKED_REDIRECT,
                                    )
                                    return@withContext Result.failure(SecurityException("RFC-0042 Egress Denied: redirect limit exceeded"))
                                }
                                redirectValidation as EgressValidationResult.Allowed
                            } finally {
                                runCatching { httpResponse.body().close() }
                            }
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                    if (redirectTarget != null) {
                        current = redirectTarget
                        continue
                    }

                    val maxBytes = effectiveScope.maxResponseBytes.coerceAtLeast(0L)
                    httpResponse.body().use { bodyStream ->
                        val contentLength = httpResponse.headers().firstValue("Content-Length").orElse(null)?.toLongOrNull()
                        if (contentLength != null && contentLength > maxBytes) {
                            record(
                                request = request,
                                host = current.uri.host ?: "unknown",
                                addressClass = current.addressClass,
                                method = request.method,
                                requestBytes = requestBytes,
                                responseBytes = 0L,
                                payloadHash = payloadHash,
                                durationMs = System.currentTimeMillis() - startTime,
                                outcome = EgressOutcome.BLOCKED_RESPONSE_SIZE,
                            )
                            return@withContext Result.failure(
                                SecurityException(
                                    "RFC-0042 Egress Denied: response body exceeds maxResponseBytes ($contentLength > $maxBytes)"
                                )
                            )
                        }

                        val readResult = readBoundedBody(bodyStream, maxBytes)
                        if (readResult.exceededLimit) {
                            record(
                                request = request,
                                host = current.uri.host ?: "unknown",
                                addressClass = current.addressClass,
                                method = request.method,
                                requestBytes = requestBytes,
                                responseBytes = readResult.bytesRead,
                                payloadHash = payloadHash,
                                durationMs = System.currentTimeMillis() - startTime,
                                outcome = EgressOutcome.BLOCKED_RESPONSE_SIZE,
                            )
                            return@withContext Result.failure(
                                SecurityException(
                                    "RFC-0042 Egress Denied: response body exceeds maxResponseBytes (${readResult.bytesRead} > $maxBytes)"
                                )
                            )
                        }

                        val responseBytes = readResult.bytes
                        val record = record(
                            request = request,
                            host = current.uri.host ?: "unknown",
                            addressClass = current.addressClass,
                            method = request.method,
                            requestBytes = requestBytes,
                            responseBytes = responseBytes.size.toLong(),
                            payloadHash = payloadHash,
                            durationMs = System.currentTimeMillis() - startTime,
                            outcome = EgressOutcome.SUCCESS,
                        )
                        return@withContext Result.success(
                            EgressResponse(
                                statusCode = httpResponse.statusCode(),
                                headers = httpResponse.headers().map(),
                                body = responseBytes,
                                record = record
                            )
                        )
                    }
                } catch (e: Exception) {
                    record(
                        request = request,
                        host = current.uri.host ?: "unknown",
                        addressClass = current.addressClass,
                        method = request.method,
                        requestBytes = requestBytes,
                        responseBytes = 0L,
                        payloadHash = payloadHash,
                        durationMs = System.currentTimeMillis() - startTime,
                        outcome = EgressOutcome.FAILED,
                    )
                    return@withContext Result.failure(e)
                }
            }
            @Suppress("UNREACHABLE_CODE")
            Result.failure(IllegalStateException("unreachable"))
        }
    }

    private fun record(
        request: EgressRequest,
        host: String,
        addressClass: AddressClass,
        method: String,
        requestBytes: Long,
        responseBytes: Long,
        payloadHash: String,
        durationMs: Long,
        outcome: EgressOutcome,
    ): EgressRecord {
        val record = EgressRecord(
            projectId = request.projectId,
            attemptId = request.attemptId,
            capabilityId = request.capabilityId.value,
            destinationHost = host,
            resolvedAddressClass = addressClass,
            method = method,
            requestBytes = requestBytes,
            responseBytes = responseBytes,
            payloadHash = payloadHash,
            contentSensitivity = request.contentSensitivity,
            runTaint = request.runTaint,
            durationMs = durationMs,
            outcome = outcome,
            costUnits = request.costUnits,
        )
        synchronized(auditLog) { auditLog.add(record) }
        runCatching { recordSink(record) }
        return record
    }

    private class BoundedReadResult(
        val bytes: ByteArray,
        val bytesRead: Long,
        val exceededLimit: Boolean,
    )

    private fun readBoundedBody(input: InputStream, maxBytes: Long): BoundedReadResult {
        val out = ByteArrayOutputStream(minOf(maxBytes, 8192L).toInt())
        val buffer = ByteArray(8192)
        var totalRead = 0L
        val readCap = if (maxBytes == Long.MAX_VALUE) Long.MAX_VALUE else maxBytes + 1L

        while (true) {
            val remainingToCap = readCap - totalRead
            if (remainingToCap <= 0L) {
                return BoundedReadResult(
                    bytes = ByteArray(0),
                    bytesRead = totalRead,
                    exceededLimit = true,
                )
            }
            val toRead = minOf(buffer.size.toLong(), remainingToCap).toInt()
            val n = input.read(buffer, 0, toRead)
            if (n == -1) break
            if (n == 0) continue

            val newTotal = totalRead + n
            if (newTotal > maxBytes) {
                return BoundedReadResult(
                    bytes = ByteArray(0),
                    bytesRead = newTotal,
                    exceededLimit = true,
                )
            }
            out.write(buffer, 0, n)
            totalRead = newTotal
        }

        return BoundedReadResult(
            bytes = out.toByteArray(),
            bytesRead = totalRead,
            exceededLimit = false,
        )
    }
}
