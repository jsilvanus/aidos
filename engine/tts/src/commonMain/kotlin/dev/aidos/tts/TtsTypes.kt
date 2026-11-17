package dev.aidos.tts

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse

/**
 * One speech request, OpenAI-shaped (`POST /v1/audio/speech`). [speed] is a playback factor where 1.0 is the voice's
 * normal rate; the route checks the range before a request reaches a model.
 */
class SpeechRequest(
    val input: String,
    val voice: String? = null,
    val speed: Double = 1.0,
)

/** Mono 16-bit PCM audio at [sampleRate]. The route wraps it in a WAV header or sends the bytes as-is. */
class SpeechResult(
    val pcm16: ByteArray,
    val sampleRate: Int,
) {
    val durationSeconds: Double get() = (pcm16.size / 2).toDouble() / sampleRate
}

/** A loaded text-to-speech voice. Blocking; the host serialises calls through the inference manager. */
interface TtsModel : AutoCloseable {
    val modelId: String
    fun synthesize(request: SpeechRequest): SpeechResult
}

/** Thrown for input the voice cannot speak (empty, too long, no usable phonemes); maps to HTTP 400, not 500. */
class TtsRequestException(message: String) : IllegalArgumentException(message)

/** A loaded TTS voice as the ModelRuntime sees it: an adapter that speaks text, not chat turns. */
interface TtsModelAdapter : ModelAdapter {
    suspend fun synthesize(request: SpeechRequest): Result<SpeechResult>
}

/** Bridges a [TtsModel] onto the kernel's [ModelAdapter] so GlobalModelRuntime can load and unload it. */
class TtsModelAdapterImpl(
    private val model: TtsModel,
    override val contextWindow: Int = SpeechText.MAX_INPUT_CHARS,
    override val modelVersion: String = "onnx",
) : TtsModelAdapter, AutoCloseable {
    override val providerId: String = "local"
    override val modelId: String get() = model.modelId
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> =
        Result.failure(UnsupportedOperationException("Model $modelId is a text-to-speech model; use /v1/audio/speech"))

    override suspend fun synthesize(request: SpeechRequest): Result<SpeechResult> = runCatching { model.synthesize(request) }

    override fun close() = model.close()
}
