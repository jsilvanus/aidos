package dev.aidos.daemon

import dev.aidos.amp.HttpTransport
import dev.aidos.http.EgressClient
import dev.aidos.http.EgressRequest
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import dev.aidos.vault.AnthropicTransport
import java.net.URI

private const val PROVIDER_CAPABILITY_ID = "provider-egress"

class EgressJsonTransport(
    private val egressClient: EgressClient,
    private val scope: CapabilityScope.Network,
    private val projectId: String?,
    private val attemptId: String? = null,
    private val sensitivity: String = "REQUIRES_APPROVAL",
    private val runTaint: String = "UNTRUSTED",
) : HttpTransport, AnthropicTransport {
    override suspend fun postJson(url: String, headers: Map<String, String>, bodyJson: String): String {
        val response = egressClient.request(
            request = EgressRequest(
                capabilityId = CapabilityId(PROVIDER_CAPABILITY_ID),
                url = url,
                method = "POST",
                headers = headers + mapOf(
                    "Content-Type" to "application/json",
                    "Accept" to "application/json",
                ),
                body = bodyJson.toByteArray(Charsets.UTF_8),
                timeoutSeconds = 120,
                projectId = projectId,
                attemptId = attemptId,
                contentSensitivity = sensitivity,
                runTaint = runTaint,
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
