package dev.aidos.http

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import okhttp3.ConnectionPool
import okhttp3.Dns
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.Proxy
import java.net.URI
import java.net.UnknownHostException
import java.time.Duration
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

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
    // Template for the per-hop clients below; only its TLS and timeout settings are used.
    // Redirects, proxies and name resolution are fixed per hop and cannot be loosened from here.
    private val baseClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(Duration.ofSeconds(15))
        .build(),
    private val recordSink: (EgressRecord) -> Unit = {},
) : EgressClient {

    private val auditLog = ArrayDeque<EgressRecord>()

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
            // What is sent on this hop. A redirect may change all three (see [afterRedirect]).
            var method = request.method.uppercase()
            var headers = request.headers
            var body = request.body
            // The timeout covers the whole exchange. HttpRequest.timeout ends when the response
            // headers arrive, so a server that then trickled or stalled the body held the call forever.
            val deadlineMillis = startTime + request.timeoutSeconds * 1000
            while (true) {
                try {
                    val remainingMillis = (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(1)
                    val reqBuilder = Request.Builder().url(current.uri.toString())
                    // The connection is pinned, so a caller-chosen Host could only mislead the server.
                    headers.forEach { (k, v) -> if (!k.equals("host", ignoreCase = true)) reqBuilder.header(k, v) }
                    val payload = when (method) {
                        "GET", "HEAD" -> null
                        else -> (body ?: ByteArray(0)).toRequestBody()
                    }
                    reqBuilder.method(method, payload)

                    // RFC-0042 point 3: dial exactly the addresses that were just classified and verify the
                    // socket that results. One client per hop, so a redirect is pinned to its own checked
                    // addresses and no pooled connection from another destination is ever reused.
                    val hopClient = pinnedClient(current, effectiveScope, remainingMillis)
                    val httpResponse = hopClient.newCall(reqBuilder.build()).execute()
                    val redirectTarget = if (httpResponse.code in 300..399) {
                        val location = httpResponse.header("Location")
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
                                runCatching { httpResponse.close() }
                            }
                        } else {
                            null
                        }
                    } else {
                        null
                    }
                    if (redirectTarget != null) {
                        val next = afterRedirect(httpResponse.code, current.uri, redirectTarget.uri, method, headers, body)
                        method = next.method
                        headers = next.headers
                        body = next.body
                        current = redirectTarget
                        continue
                    }

                    val maxBytes = effectiveScope.maxResponseBytes.coerceAtLeast(0L)
                    httpResponse.use {
                        val bodyStream = httpResponse.body!!.byteStream()
                        // A blocked read cannot be interrupted, but closing the stream ends it.
                        val watchdog = bodyDeadline.schedule(
                            { runCatching { bodyStream.close() } },
                            (deadlineMillis - System.currentTimeMillis()).coerceAtLeast(1),
                            TimeUnit.MILLISECONDS,
                        )
                        try {
                        val contentLength = httpResponse.header("Content-Length")?.toLongOrNull()
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
                                statusCode = httpResponse.code,
                                headers = httpResponse.headers.toMultimap(),
                                body = responseBytes,
                                record = record
                            )
                        )
                        } finally {
                            watchdog.cancel(false)
                        }
                    }
                } catch (e: ConnectedAddressDenied) {
                    // The socket that came up was not one of the addresses that passed the check.
                    record(
                        request = request,
                        host = current.uri.host ?: "unknown",
                        addressClass = e.denial.addressClass ?: current.addressClass,
                        method = request.method,
                        requestBytes = requestBytes,
                        responseBytes = 0L,
                        payloadHash = payloadHash,
                        durationMs = System.currentTimeMillis() - startTime,
                        outcome = e.denial.outcome,
                    )
                    return@withContext Result.failure(SecurityException("RFC-0042 Egress Denied: ${e.denial.reason}"))
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
        // The durable trail is [recordSink]; this is a recent-history view and must not grow for the
        // life of the process.
        synchronized(auditLog) {
            auditLog.addLast(record)
            while (auditLog.size > MAX_RECENT_RECORDS) auditLog.removeFirst()
        }
        runCatching { recordSink(record) }
        return record
    }

    /** Thrown from the network interceptor, which may only fail with an [IOException]. */
    private class ConnectedAddressDenied(val denial: EgressValidationResult.Denied) : IOException(denial.reason)

    /**
     * A client whose only resolver answers with [target]'s already-checked addresses (RFC-0042 points
     * 2-3), and whose connected socket is checked again before a byte is sent. No system DNS lookup
     * happens after the check, so a name that answers public first and private second cannot be
     * rebound; TLS still verifies the original host name because the URL is unchanged.
     */
    private fun pinnedClient(
        target: EgressValidationResult.Allowed,
        scope: CapabilityScope.Network,
        remainingMillis: Long,
    ): OkHttpClient {
        val host = target.uri.host
        val pinned = target.resolvedAddresses
        val pinnedDns = object : Dns {
            override fun lookup(hostname: String): List<java.net.InetAddress> =
                if (hostname.equals(host, ignoreCase = true)) pinned else throw UnknownHostException(hostname)
        }
        val verifyConnected = Interceptor { chain ->
            val connected = chain.connection()?.socket()?.inetAddress
            if (connected == null || pinned.none { it.address.contentEquals(connected.address) }) {
                throw ConnectedAddressDenied(
                    EgressValidationResult.Denied(
                        reason = "connected to ${connected?.hostAddress} which is not an address checked for '$host'",
                        outcome = EgressOutcome.BLOCKED_POST_DNS,
                    )
                )
            }
            policy.addressDenial(host, connected, scope)?.let { throw ConnectedAddressDenied(it) }
            chain.proceed(chain.request())
        }
        return baseClient.newBuilder()
            .dns(pinnedDns)
            .proxy(Proxy.NO_PROXY) // a proxy would receive the host name and resolve it itself
            .followRedirects(false) // redirects are re-validated hop by hop above
            .followSslRedirects(false)
            .connectionPool(ConnectionPool(0, 1, TimeUnit.SECONDS))
            .callTimeout(Duration.ofMillis(remainingMillis))
            .addNetworkInterceptor(verifyConnected)
            .build()
    }

    private class Hop(val method: String, val headers: Map<String, String>, val body: ByteArray?)

    /**
     * What the next hop of a redirect carries (RFC-0042 point 4: a redirect target is a new destination).
     *
     * - 303, and 301/302 answering anything but GET or HEAD, are re-fetched with GET and no body,
     *   as browsers do. 307 and 308 repeat the request as it was.
     * - A hop to another origin (scheme, host or port) loses the headers that carry the caller's
     *   credentials. They were issued for the first destination; the same request, token and all,
     *   used to be replayed to wherever it pointed.
     */
    private fun afterRedirect(
        status: Int,
        from: URI,
        to: URI,
        method: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): Hop {
        val becomesGet = status == 303 || ((status == 301 || status == 302) && method != "GET" && method != "HEAD")
        var nextHeaders = headers
        if (becomesGet) nextHeaders = nextHeaders.filterKeys { it.lowercase() !in BODY_HEADERS }
        if (!sameOrigin(from, to)) nextHeaders = nextHeaders.filterKeys { it.lowercase() !in CREDENTIAL_HEADERS }
        return Hop(if (becomesGet) "GET" else method, nextHeaders, if (becomesGet) null else body)
    }

    private fun sameOrigin(a: URI, b: URI): Boolean =
        a.scheme.equals(b.scheme, ignoreCase = true) && a.host.equals(b.host, ignoreCase = true) && effectivePort(a) == effectivePort(b)

    private fun effectivePort(uri: URI): Int =
        if (uri.port != -1) uri.port else if (uri.scheme.equals("https", ignoreCase = true)) 443 else 80

    private companion object {
        const val MAX_RECENT_RECORDS = 1000
        val CREDENTIAL_HEADERS = setOf("authorization", "proxy-authorization", "cookie", "x-api-key", "api-key", "x-auth-token")
        val BODY_HEADERS = setOf("content-type", "content-length", "content-encoding")

        /** Closes response bodies that outlive their request's deadline. One daemon thread for the process. */
        val bodyDeadline: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { task ->
            Thread(task, "egress-body-deadline").apply { isDaemon = true }
        }
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
