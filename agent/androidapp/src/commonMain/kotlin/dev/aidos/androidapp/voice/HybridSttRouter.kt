package dev.aidos.androidapp.voice

import dev.aidos.ui.assistant.SttBackend
import dev.aidos.ui.assistant.VoiceCaptureController
import dev.aidos.ui.assistant.VoiceCaptureState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Routes voice capture between Aidos Engine STT (`/v1/audio/transcriptions`) and local Android
 * device STT (`SpeechRecognizer`), supporting explicit selection and `AUTO` fallback (RFC-0050, RFC-0057).
 */
class HybridSttRouter(
    private val aidosStt: LocalSttEngine? = null,
    private val androidDeviceStt: LocalSttEngine? = null,
    defaultPreferred: SttBackend = SttBackend.AUTO,
) : VoiceCaptureController {

    private var activeEngine: LocalSttEngine? = null

    private val _state = MutableStateFlow(
        VoiceCaptureState(
            preferredBackend = defaultPreferred,
            availableBackends = computeAvailableBackends(),
        )
    )
    override val state: StateFlow<VoiceCaptureState> = _state.asStateFlow()

    fun refreshAvailability() {
        _state.update { it.copy(availableBackends = computeAvailableBackends()) }
    }

    override fun selectBackend(backend: SttBackend) {
        _state.update { it.copy(preferredBackend = backend, errorMessage = null) }
    }

    override suspend fun startListening(language: String?) {
        activeEngine?.cancel()
        activeEngine = null

        val available = computeAvailableBackends()
        val candidates = resolveCandidates(_state.value.preferredBackend)
        if (candidates.isEmpty()) {
            _state.update {
                it.copy(
                    isListening = false,
                    activeBackend = null,
                    availableBackends = available,
                    errorMessage = "No speech recognition engine available",
                )
            }
            return
        }

        var lastError: String? = null
        for (engine in candidates) {
            val started = runCatching {
                _state.update {
                    it.copy(
                        isListening = true,
                        activeBackend = engine.backend,
                        availableBackends = available,
                        partialText = "",
                        errorMessage = null,
                    )
                }
                activeEngine = engine
                engine.start(
                    language = language,
                    onPartial = { partial ->
                        _state.update { st -> st.copy(partialText = partial) }
                    },
                    onError = { err ->
                        _state.update { st ->
                            st.copy(
                                isListening = false,
                                errorMessage = err,
                            )
                        }
                    },
                )
            }
            if (started.isSuccess) {
                return
            }
            activeEngine = null
            lastError = started.exceptionOrNull()?.message ?: "Failed to start ${engine.backend.label}"
        }

        _state.update {
            it.copy(
                isListening = false,
                activeBackend = null,
                availableBackends = available,
                errorMessage = lastError ?: "Speech recognition failed",
            )
        }
    }

    override suspend fun stopListening(): String? {
        val engine = activeEngine ?: run {
            _state.update { it.copy(isListening = false, activeBackend = null) }
            return _state.value.finalText.takeIf { it.isNotBlank() }
        }
        val result = engine.stop()
        activeEngine = null
        return result.fold(
            onSuccess = { text ->
                val resolved = text.ifBlank { _state.value.partialText }
                _state.update {
                    it.copy(
                        isListening = false,
                        activeBackend = null,
                        partialText = resolved,
                        finalText = resolved,
                        errorMessage = null,
                    )
                }
                resolved
            },
            onFailure = { err ->
                _state.update {
                    it.copy(
                        isListening = false,
                        activeBackend = null,
                        errorMessage = err.message ?: "Speech recognition failed",
                    )
                }
                null
            },
        )
    }

    override fun cancel() {
        activeEngine?.cancel()
        activeEngine = null
        _state.update {
            it.copy(
                isListening = false,
                activeBackend = null,
            )
        }
    }

    override fun updateTranscript(text: String) {
        _state.update {
            it.copy(
                partialText = text,
                finalText = text,
                errorMessage = null,
            )
        }
    }

    private fun resolveCandidates(preferred: SttBackend): List<LocalSttEngine> {
        val ordered = when (preferred) {
            SttBackend.AUTO -> listOfNotNull(aidosStt, androidDeviceStt)
            SttBackend.AIDOS_ENGINE -> listOfNotNull(aidosStt)
            SttBackend.ANDROID_DEVICE -> listOfNotNull(androidDeviceStt)
        }
        return ordered.filter { it.isAvailable() }
    }

    private fun computeAvailableBackends(): Set<SttBackend> {
        val set = linkedSetOf<SttBackend>()
        val hasAidos = aidosStt?.isAvailable() == true
        val hasAndroid = androidDeviceStt?.isAvailable() == true
        if (hasAidos || hasAndroid) {
            set += SttBackend.AUTO
        }
        if (hasAndroid) {
            set += SttBackend.ANDROID_DEVICE
        }
        if (hasAidos) {
            set += SttBackend.AIDOS_ENGINE
        }
        return set
    }
}
