package dev.aidos.daemon

import dev.aidos.amp.HttpTransport
import dev.aidos.http.EgressClient
import dev.aidos.http.EgressRequest
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import dev.aidos.vault.AnthropicTransport
import java.net.URI
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlin.coroutines.coroutineContext

const val DEFAULT_PROVIDER_CAPABILITY_ID = "provider-egress"

/**
 * Per-call overrides for [EgressJsonTransport] requests (capability, attempt, taint, sensitivity).
 * Any null field inherits the transport instance's configured default.
 */
data class EgressTransportContext(
    val capabilityId: CapabilityId? = null,
    val projectId: String? = null,
    val attemptId: String? = null,
    val sensitivity: String? = null,
    val runTaint: String? = null,
    val timeoutSeconds: Long? = null,
    val costUnits: Long? = null,
)

/**
 * Coroutine context element carrying per-request [EgressTransportContext] across adapter interfaces
 * whose `postJson(url, headers, bodyJson)` signature does not take metadata parameters directly.
 */
class EgressTransportContextElement(
    val context: EgressTransportContext,
) : AbstractCoroutineContextElement(Key) {
    companion object Key : CoroutineContext.Key<EgressTransportContextElement>
}

class EgressJsonTransport(
    private val egressClient: EgressClient,
    private val scope: CapabilityScope.Network,
    private val projectId: String?,
    private val attemptId: String? = null,
    private val sensitivity: String = "REQUIRES_APPROVAL",
    private val runTaint: String = "UNTRUSTED",
    private val capabilityId: CapabilityId = CapabilityId(DEFAULT_PROVIDER_CAPABILITY_ID),
    private val timeoutSeconds: Long = 120L,
    private val costUnits: Long? = null,
    private val contextProvider: (() -> EgressTransportContext?)? = null,
) : HttpTransport, AnthropicTransport {

    override suspend fun postJson(url: String, headers: Map<String, String>, bodyJson: String): String =
        executePostJson(url = url, headers = headers, bodyJson = bodyJson, explicitContext = null)

    suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        bodyJson: String,
        context: EgressTransportContext,
    ): String = executePostJson(url = url, headers = headers, bodyJson = bodyJson, explicitContext = context)

    private suspend fun executePostJson(
        url: String,
        headers: Map<String, String>,
        bodyJson: String,
        explicitContext: EgressTransportContext?,
    ): String {
        val coroutineOverride = coroutineContext[EgressTransportContextElement]?.context
        val dynamicOverride = contextProvider?.invoke()

        val resolvedCapabilityId = explicitContext?.capabilityId
            ?: coroutineOverride?.capabilityId
            ?: dynamicOverride?.capabilityId
            ?: capabilityId
        val resolvedProjectId = explicitContext?.projectId
            ?: coroutineOverride?.projectId
            ?: dynamicOverride?.projectId
            ?: projectId
        val resolvedAttemptId = explicitContext?.attemptId
            ?: coroutineOverride?.attemptId
            ?: dynamicOverride?.attemptId
            ?: attemptId
        val resolvedSensitivity = explicitContext?.sensitivity
            ?: coroutineOverride?.sensitivity
            ?: dynamicOverride?.sensitivity
            ?: sensitivity
        val resolvedRunTaint = explicitContext?.runTaint
            ?: coroutineOverride?.runTaint
            ?: dynamicOverride?.runTaint
            ?: runTaint
        val resolvedTimeout = explicitContext?.timeoutSeconds
            ?: coroutineOverride?.timeoutSeconds
            ?: dynamicOverride?.timeoutSeconds
            ?: timeoutSeconds
        val resolvedCostUnits = explicitContext?.costUnits
            ?: coroutineOverride?.costUnits
            ?: dynamicOverride?.costUnits
            ?: costUnits

        val response = egressClient.request(
            request = EgressRequest(
                capabilityId = resolvedCapabilityId,
                url = url,
                method = "POST",
                headers = headers + mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body = bodyJson.toByteArray(Charsets.UTF_8),
                timeoutSeconds = resolvedTimeout,
                projectId = resolvedProjectId,
                attemptId = resolvedAttemptId,
                contentSensitivity = resolvedSensitivity,
                runTaint = resolvedRunTaint,
                costUnits = resolvedCostUnits,
            ),
            scope = scope,
        ).getOrThrow()

        if (response.statusCode !in 200..299) {
            throw IllegalStateException("HTTP ${response.statusCode}: ${response.bodyString}")
        }
        return response.bodyString
    }
}

fun anthropicScope() = CapabilityScope.Network(
    allowedHosts = listOf("api.anthropic.com"),
    allowedPorts = listOf(443),
)

fun openAiScope(baseUrl: String): CapabilityScope.Network {
    val uri = URI.create(baseUrl)
    val host = uri.host ?: throw IllegalArgumentException("OpenAI base URL has no host: $baseUrl")
    val scheme = uri.scheme?.lowercase()
    val defaultPort = if (scheme == "http") 80 else 443
    val explicitPort = if (uri.port == -1) defaultPort else uri.port
    val allowPrivate = host.equals("localhost", ignoreCase = true) || host == "127.0.0.1" || host == "::1"
    return CapabilityScope.Network(
        allowedHosts = listOf(host),
        allowedPorts = listOf(explicitPort),
        allowPrivateAddresses = allowPrivate,
    )
}
