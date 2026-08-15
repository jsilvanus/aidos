package fi.italeino.aidos.sdk

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TokenUsage

/**
 * Speech-to-Text (STT) ModelAdapter for Aidos Engine (RFC-0103, RFC-0021).
 *
 * Adapts Aidos Engine's OpenAI-compatible `/v1/audio/transcriptions` endpoint
 * to the RFC-0021 ModelAdapter interface for speech transcription operations.
 *
 * RFC-0103 MVP: STT is one of three modalities (LLM, embedding, STT) that ship
 * with local inference support via Aidos Engine (whisper.cpp backend).
 *
 * Note: Like EngineEmbeddingAdapter, this adapter's invoke() signature is designed
 * for LLM inference. For STT, the request contains audio data (not text messages),
 * which is a structural mismatch. This adapter gracefully handles the constraint
 * and returns an error indicating that STT must be called through a specialized
 * pathway, not the standard ModelAdapter flow.
 *
 * TODO(RFC-0103): Add ModelKind.STT variant to routing that provides a different
 * request type optimized for speech (audio data, no messages/tools).
 */
class EngineStlAdapter(
    override val modelId: String,
    override val modelVersion: String,
    override val contextWindow: Int,  // Not used for STT, but required by interface
    private val httpClient: EngineHttpClient,
) : ModelAdapter {
    override val providerId = "aidos-engine"
    override val isLocal = true

    override fun supportsNativeToolCalls(): Boolean = false

    /**
     * Run STT inference via Aidos Engine's transcription endpoint.
     *
     * Note: The current ModelRequest type does not contain audio data — it contains
     * messages (text). This adapter cannot fulfill STT requests through the standard
     * ModelAdapter interface. STT is deferred to a specialized transcribe() method
     * or a Future Work refinement adding ModelKind.STT to routing.
     *
     * For MVP, this adapter returns an error indicating the limitation.
     * A calling app must use EngineHttpClient.transcribe() directly.
     */
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        return Result.failure(
            UnsupportedOperationException(
                "STT (speech-to-text) cannot be invoked through the standard ModelAdapter interface. " +
                "Use EngineHttpClient.transcribe() directly or call Engine's dedicated STT pathway. " +
                "RFC-0103 Future Work: add ModelKind.STT routing variant."
            )
        )
    }
}
