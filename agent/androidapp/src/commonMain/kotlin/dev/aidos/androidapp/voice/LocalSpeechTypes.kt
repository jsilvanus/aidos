package dev.aidos.androidapp.voice

import dev.aidos.ui.assistant.SttBackend

/**
 * A single speech-to-text engine backend (e.g. Aidos Engine Whisper or Android on-device [SpeechRecognizer]).
 */
interface LocalSttEngine {
    val backend: SttBackend
    fun isAvailable(): Boolean
    suspend fun start(
        language: String?,
        onPartial: (String) -> Unit,
        onError: (String) -> Unit,
    )
    suspend fun stop(): Result<String>
    fun cancel()
}

/**
 * Local text-to-speech engine (e.g. Android `TextToSpeech` with audio focus ducking).
 */
interface LocalTtsEngine {
    fun isAvailable(): Boolean
    suspend fun speak(text: String): Result<Unit>
    fun stop()
}
