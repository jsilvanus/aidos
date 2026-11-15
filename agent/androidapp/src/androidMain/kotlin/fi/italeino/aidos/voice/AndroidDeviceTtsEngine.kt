package fi.italeino.aidos.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import dev.aidos.androidapp.voice.LocalTtsEngine
import kotlinx.coroutines.CompletableDeferred
import java.util.Locale
import java.util.UUID

/**
 * Local Android [TextToSpeech] engine with [AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK]
 * audio-focus ducking (RFC-0057).
 */
class AndroidDeviceTtsEngine(
    context: Context,
) : LocalTtsEngine {

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var ready = false
    private var tts: TextToSpeech? = null
    private var focusRequest: AudioFocusRequest? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            ready = status == TextToSpeech.SUCCESS
            if (ready) {
                tts?.language = Locale.getDefault()
            }
        }
    }

    override fun isAvailable(): Boolean = ready && tts != null

    override suspend fun speak(text: String): Result<Unit> {
        val engine = tts
        if (!ready || engine == null) {
            return Result.failure(IllegalStateException("Android TextToSpeech is not ready"))
        }
        val utteranceId = UUID.randomUUID().toString()
        val done = CompletableDeferred<Result<Unit>>()
        engine.setOnUtteranceProgressListener(
            object : UtteranceProgressListener() {
                override fun onStart(id: String?) {}

                override fun onDone(id: String?) {
                    if (id == utteranceId && !done.isCompleted) {
                        abandonFocus()
                        done.complete(Result.success(Unit))
                    }
                }

                @Deprecated("Deprecated in Android API")
                override fun onError(id: String?) {
                    if (id == utteranceId && !done.isCompleted) {
                        abandonFocus()
                        done.complete(Result.failure(IllegalStateException("TTS utterance failed")))
                    }
                }

                override fun onError(id: String?, errorCode: Int) {
                    if (id == utteranceId && !done.isCompleted) {
                        abandonFocus()
                        done.complete(Result.failure(IllegalStateException("TTS utterance failed ($errorCode)")))
                    }
                }
            }
        )

        requestDuckFocus()
        val queued = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (queued != TextToSpeech.SUCCESS) {
            abandonFocus()
            return Result.failure(IllegalStateException("TextToSpeech.speak failed ($queued)"))
        }
        return done.await()
    }

    override fun stop() {
        runCatching { tts?.stop() }
        abandonFocus()
    }

    fun shutdown() {
        stop()
        runCatching { tts?.shutdown() }
        tts = null
        ready = false
    }

    private fun requestDuckFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .build()
            focusRequest = req
            audioManager.requestAudioFocus(req)
        } else {
            @Suppress("DEPRECATION")
            audioManager.requestAudioFocus(
                null,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK,
            )
        }
    }

    private fun abandonFocus() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
            focusRequest = null
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(null)
        }
    }
}
