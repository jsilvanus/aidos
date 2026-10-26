package fi.italeino.aidos.engine.handshake

import dev.aidos.kernel.ModelRuntime
import fi.italeino.aidos.engine.http.Capabilities
import fi.italeino.aidos.engine.http.ModelInfo
import fi.italeino.aidos.engine.http.TokenManager
import fi.italeino.aidos.engine.http.deriveQuantization
import fi.italeino.aidos.engine.inference.placementOf
import fi.italeino.aidos.sdk.client.EngineCapabilities
import fi.italeino.aidos.sdk.client.EngineHandshakeResult
import fi.italeino.aidos.sdk.client.EngineHandshakeSource
import fi.italeino.aidos.sdk.client.EngineModel

/** What the handshake advertises (RFC-0103): the `/v1/` endpoints and the runtime's catalog. */
suspend fun ModelRuntime.handshakeCapabilities(): Capabilities = Capabilities(
    // "locality" tells a client this Engine enforces X-Aidos-Locality (RFC-0104). A client that
    // requires a limit must not send it to an Engine that does not advertise this: it would be ignored.
    endpoints = listOf("models", "chat.completions", "embeddings", "audio.transcriptions", "locality"),
    models = catalog().map { descriptor ->
        ModelInfo(
            id = descriptor.id,
            kind = descriptor.kind.toString().lowercase(),
            context_window = descriptor.contextWindow,
            quantization = deriveQuantization(descriptor.id),
            placement = placementOf(descriptor).wire
        )
    }
)

internal fun Capabilities.toSdk() = EngineCapabilities(
    endpoints = endpoints,
    models = models.map { EngineModel(it.id, it.kind, it.context_window, it.quantization, it.placement) }
)

/**
 * Engine talking to its own `/v1/` endpoint through the Aidos SDK client (Test Chat, the desktop
 * debug app) instead of a private copy of the transport. Engine trusts its own process, so no
 * approval is involved: it uses [TokenManager.ENGINE_SUBJECT]'s token, reusing a still-valid one.
 */
class InProcessHandshakeSource(
    private val tokenManager: TokenManager,
    private val boundPort: suspend () -> Int?,
    private val capabilities: suspend () -> Capabilities
) : EngineHandshakeSource {
    override suspend fun handshake(): EngineHandshakeResult {
        val port = boundPort() ?: return EngineHandshakeResult.Failed
        val token = tokenManager.currentValidToken() ?: tokenManager.generateNewToken().token
        return EngineHandshakeResult.Approved(port, token, capabilities().toSdk())
    }
}
