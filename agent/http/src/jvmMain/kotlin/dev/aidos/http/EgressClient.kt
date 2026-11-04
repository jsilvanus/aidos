package dev.aidos.http

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
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
    val durationMs: Long,
    val outcome: EgressOutcome,
    val occurredAt: String = java.time.Instant.now().toString()
)

data class EgressRequest(
    val capabilityId: CapabilityId,
    val url: String,
    val method: String = "GET",
    val headers: Map<String, String> = emptyMap(),
    val body: ByteArray? = null,
    val timeoutSeconds: Long = 30L
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
        .build()
) : EgressClient {

    private val auditLog = mutableListOf<EgressRecord>()

    override fun getAuditRecords(): List<EgressRecord> = synchronized(auditLog) {
        auditLog.toList()
    }

    override suspend fun request(
        request: EgressRequest,
        scope: CapabilityScope.Network?
    ): Result<EgressResponse> = withContext(Dispatchers.IO) {
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

        when (validation) {
            is EgressValidationResult.Denied -> {
                val duration = System.currentTimeMillis() - startTime
                val host = try { URI.create(request.url).host ?: "unknown" } catch (e: Exception) { "unknown" }
                val record = EgressRecord(
                    capabilityId = request.capabilityId.value,
                    destinationHost = host,
                    resolvedAddressClass = validation.addressClass ?: AddressClass.RESERVED,
                    method = request.method,
                    requestBytes = requestBytes,
                    responseBytes = 0L,
                    payloadHash = payloadHash,
                    durationMs = duration,
                    outcome = validation.outcome
                )
                synchronized(auditLog) { auditLog.add(record) }
                return@withContext Result.failure(
                    SecurityException("RFC-0042 Egress Denied: ${validation.reason}")
                )
            }
            is EgressValidationResult.Allowed -> {
                // Execute HTTP call
                try {
                    val reqBuilder = HttpRequest.newBuilder()
                        .uri(validation.uri)
                        .timeout(Duration.ofSeconds(request.timeoutSeconds))

                    request.headers.forEach { (k, v) ->
                        reqBuilder.setHeader(k, v)
                    }

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

                    val httpResponse = httpClient.send(reqBuilder.build(), HttpResponse.BodyHandlers.ofByteArray())
                    val responseBytes = httpResponse.body()
                    val duration = System.currentTimeMillis() - startTime

                    val record = EgressRecord(
                        capabilityId = request.capabilityId.value,
                        destinationHost = validation.uri.host,
                        resolvedAddressClass = validation.addressClass,
                        method = request.method,
                        requestBytes = requestBytes,
                        responseBytes = responseBytes.size.toLong(),
                        payloadHash = payloadHash,
                        durationMs = duration,
                        outcome = EgressOutcome.SUCCESS
                    )
                    synchronized(auditLog) { auditLog.add(record) }

                    Result.success(
                        EgressResponse(
                            statusCode = httpResponse.statusCode(),
                            headers = httpResponse.headers().map(),
                            body = responseBytes,
                            record = record
                        )
                    )
                } catch (e: Exception) {
                    val duration = System.currentTimeMillis() - startTime
                    val record = EgressRecord(
                        capabilityId = request.capabilityId.value,
                        destinationHost = validation.uri.host,
                        resolvedAddressClass = validation.addressClass,
                        method = request.method,
                        requestBytes = requestBytes,
                        responseBytes = 0L,
                        payloadHash = payloadHash,
                        durationMs = duration,
                        outcome = EgressOutcome.FAILED
                    )
                    synchronized(auditLog) { auditLog.add(record) }
                    Result.failure(e)
                }
            }
        }
    }
}
