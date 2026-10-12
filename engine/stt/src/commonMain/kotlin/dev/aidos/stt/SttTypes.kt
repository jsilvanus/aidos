package dev.aidos.stt

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse

/** One utterance to transcribe. [audio] is a WAV file (see [WavDecoder]). */
class SttRequest(
    val audio: ByteArray,
    /** ISO 639-1 code ("fi", "en"); null lets the model detect it. Detection is weak on short Finnish clips, so callers that know should say. */
    val language: String? = null,
    /** Text Whisper treats as what was said just before: spelling and vocabulary hints (names, liturgical terms). */
    val prompt: String? = null,
)

class SttResult(
    val text: String,
    /** The language used: the requested one, or the detected one. */
    val language: String,
    val durationSeconds: Double,
)

/** A loaded speech-to-text model. Blocking; the host serialises calls through the inference manager. */
interface SttModel : AutoCloseable {
    val modelId: String
    fun transcribe(request: SttRequest): SttResult
}

/** Thrown for audio the model cannot take; maps to HTTP 400, not 500. */
class SttRequestException(message: String) : IllegalArgumentException(message)

/** A loaded STT model as the ModelRuntime sees it: an adapter that transcribes audio, not chat turns. */
interface SttModelAdapter : ModelAdapter {
    suspend fun transcribe(request: SttRequest): Result<SttResult>
}

/** Bridges an [SttModel] onto the kernel's [ModelAdapter] so GlobalModelRuntime can load/unload it. */
class SttModelAdapterImpl(
    private val model: SttModel,
    override val contextWindow: Int = 448,
    override val modelVersion: String = "onnx",
) : SttModelAdapter, AutoCloseable {
    override val providerId: String = "local"
    override val modelId: String get() = model.modelId
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> =
        Result.failure(UnsupportedOperationException("Model $modelId is a speech-to-text model; use /v1/audio/transcriptions"))

    override suspend fun transcribe(request: SttRequest): Result<SttResult> = runCatching { model.transcribe(request) }

    override fun close() = model.close()
}
