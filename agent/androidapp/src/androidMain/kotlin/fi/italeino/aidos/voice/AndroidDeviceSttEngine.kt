package fi.italeino.aidos.voice

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.content.ContextCompat
import dev.aidos.androidapp.voice.LocalSttEngine
import dev.aidos.ui.assistant.SttBackend
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Local Android device Speech-to-Text engine backed by [SpeechRecognizer] (RFC-0050, RFC-0057).
 *
 * Prefers [SpeechRecognizer.createOnDeviceSpeechRecognizer] on Android 12+ (API 31+) when
 * available, and sets [RecognizerIntent.EXTRA_PREFER_OFFLINE] for offline recognition.
 */
class AndroidDeviceSttEngine(
    private val context: Context,
) : LocalSttEngine {

    override val backend: SttBackend = SttBackend.ANDROID_DEVICE

    private val mainHandler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var resultDeferred: CompletableDeferred<String>? = null
    private var lastPartial: String = ""

    override fun isAvailable(): Boolean {
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return false
        val onDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
        return onDevice || SpeechRecognizer.isRecognitionAvailable(context)
    }

    override suspend fun start(
        language: String?,
        onPartial: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        lastPartial = ""
        val deferred = CompletableDeferred<String>()
        resultDeferred = deferred

        val started = CompletableDeferred<Unit>()
        mainHandler.post {
            try {
                recognizer?.destroy()
                val sr = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                    SpeechRecognizer.isOnDeviceRecognitionAvailable(context)
                ) {
                    SpeechRecognizer.createOnDeviceSpeechRecognizer(context)
                } else {
                    SpeechRecognizer.createSpeechRecognizer(context)
                }
                recognizer = sr

                sr.setRecognitionListener(
                    object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {}
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}

                        override fun onError(error: Int) {
                            val message = "Android SpeechRecognizer error ($error)"
                            if (!deferred.isCompleted) {
                                if (lastPartial.isNotBlank()) {
                                    deferred.complete(lastPartial)
                                } else {
                                    deferred.completeExceptionally(IllegalStateException(message))
                                    onError(message)
                                }
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            val text = results
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                            val resolved = text.ifBlank { lastPartial }
                            if (!deferred.isCompleted) {
                                deferred.complete(resolved)
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {
                            val partial = partialResults
                                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                ?.firstOrNull()
                                .orEmpty()
                            if (partial.isNotBlank()) {
                                lastPartial = partial
                                onPartial(partial)
                            }
                        }

                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    }
                )

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(
                        RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                        RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                    )
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
                    if (!language.isNullOrBlank()) {
                        putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    }
                }
                sr.startListening(intent)
                started.complete(Unit)
            } catch (t: Throwable) {
                started.completeExceptionally(t)
            }
        }
        started.await()
    }

    override suspend fun stop(): Result<String> {
        val deferred = resultDeferred
        mainHandler.post {
            runCatching { recognizer?.stopListening() }
        }
        val text = if (deferred != null) {
            runCatching {
                withTimeoutOrNull(4_000L) { deferred.await() } ?: lastPartial
            }
        } else {
            Result.success(lastPartial)
        }
        mainHandler.post {
            runCatching { recognizer?.destroy() }
            recognizer = null
        }
        resultDeferred = null
        return text
    }

    override fun cancel() {
        resultDeferred?.cancel()
        resultDeferred = null
        mainHandler.post {
            runCatching {
                recognizer?.cancel()
                recognizer?.destroy()
            }
            recognizer = null
        }
    }
}
