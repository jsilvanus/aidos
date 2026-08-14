package dev.aidos.voice

import dev.aidos.kernel.ModelKind

/**
 * Speech-to-Text provider (M33, RFC-0050, RFC-0057).
 *
 * Converts audio input to transcribed text. Local models are preferred for offline-first
 * operation. The provider is responsible for managing model lifecycle and audio encoding.
 */
interface SttProvider {
    /**
     * Returns whether a local STT model is available and ready.
     * Used to gate voice capture UI (RFC-0057).
     */
    suspend fun isAvailable(): Boolean

    /**
     * Transcribe audio bytes to text.
     * @param audioBytes raw audio data (format depends on provider's model)
     * @return transcribed text or error
     */
    suspend fun transcribe(audioBytes: ByteArray): Result<String>
}

/**
 * Text-to-Speech provider (M33, RFC-0050, RFC-0057).
 *
 * Converts text to spoken audio output. Local models are preferred for offline-first
 * operation. The provider is responsible for managing model lifecycle and audio synthesis.
 */
interface TtsProvider {
    /**
     * Returns whether a local TTS model is available and ready.
     * Used to gate voice output UI and settings (RFC-0057).
     */
    suspend fun isAvailable(): Boolean

    /**
     * Convert text to speech.
     * @param text the text to speak (no model-generated prose, only runtime-owned fields per RFC-0057)
     * @param speed playback speed factor (1.0 = normal)
     * @return audio bytes or error
     */
    suspend fun synthesize(text: String, speed: Float = 1.0f): Result<ByteArray>
}

/**
 * Factory for obtaining STT and TTS providers based on available models.
 *
 * M33 uses local models only; remote providers are not part of the MVP.
 */
interface VoiceProviderFactory {
    suspend fun getSttProvider(modelId: String): Result<SttProvider>
    suspend fun getTtsProvider(modelId: String): Result<TtsProvider>
}

/** Placeholder implementation for testing (no actual speech synthesis). */
class NoOpSttProvider : SttProvider {
    override suspend fun isAvailable(): Boolean = false
    override suspend fun transcribe(audioBytes: ByteArray): Result<String> =
        Result.failure(IllegalStateException("No STT provider available"))
}

/** Placeholder implementation for testing (no actual speech synthesis). */
class NoOpTtsProvider : TtsProvider {
    override suspend fun isAvailable(): Boolean = false
    override suspend fun synthesize(text: String, speed: Float): Result<ByteArray> =
        Result.failure(IllegalStateException("No TTS provider available"))
}
