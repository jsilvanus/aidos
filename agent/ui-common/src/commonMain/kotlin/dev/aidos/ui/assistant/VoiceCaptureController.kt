package dev.aidos.ui.assistant

import kotlinx.coroutines.flow.StateFlow

/**
 * Selectable Speech-to-Text backend on Android (RFC-0050, RFC-0057, RFC-0103).
 *
 * - [AUTO]: Prefers Aidos Engine STT when available and falls back to local Android device STT.
 * - [ANDROID_DEVICE]: Uses Android's on-device `SpeechRecognizer` directly.
 * - [AIDOS_ENGINE]: Uses Aidos Engine's `/v1/audio/transcriptions` (Whisper on ONNX Runtime).
 */
enum class SttBackend(val label: String) {
    AUTO("Auto"),
    ANDROID_DEVICE("Device STT"),
    AIDOS_ENGINE("Aidos STT"),
}

data class VoiceCaptureState(
    val isListening: Boolean = false,
    val partialText: String = "",
    val finalText: String = "",
    val preferredBackend: SttBackend = SttBackend.AUTO,
    val activeBackend: SttBackend? = null,
    val availableBackends: Set<SttBackend> = emptySet(),
    val errorMessage: String? = null,
)

/**
 * Platform-neutral controller driving voice dictation in [AssistantSheetOverlay] (RFC-0050, RFC-0057).
 */
interface VoiceCaptureController {
    val state: StateFlow<VoiceCaptureState>
    fun selectBackend(backend: SttBackend)
    suspend fun startListening(language: String? = null)
    suspend fun stopListening(): String?
    fun cancel()
    fun updateTranscript(text: String)
}
