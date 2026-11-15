package fi.italeino.aidos.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import dev.aidos.androidapp.voice.LocalSttEngine
import dev.aidos.ui.assistant.SttBackend
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID

/**
 * Aidos Engine STT client calling local Engine `/v1/audio/transcriptions` over loopback HTTP
 * with `X-Aidos-Locality: local_only` (RFC-0050, RFC-0057, RFC-0103, RFC-0104).
 */
class AidosEngineSttClient(
    private val context: Context,
    private val endpointUrlProvider: () -> String? = { "http://127.0.0.1:11434/v1/audio/transcriptions" },
    private val isEngineAvailable: () -> Boolean = { true },
) : LocalSttEngine {

    override val backend: SttBackend = SttBackend.AIDOS_ENGINE

    private val scope = CoroutineScope(Dispatchers.IO)
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private val pcmBuffer = ByteArrayOutputStream()
    private var activeLanguage: String? = null

    override fun isAvailable(): Boolean {
        if (!isEngineAvailable()) return false
        if (endpointUrlProvider().isNullOrBlank()) return false
        val hasMic = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.RECORD_AUDIO,
        ) == PackageManager.PERMISSION_GRANTED
        if (!hasMic) return false
        return AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        ) > 0
    }

    override suspend fun start(
        language: String?,
        onPartial: (String) -> Unit,
        onError: (String) -> Unit,
    ) {
        activeLanguage = language
        synchronized(pcmBuffer) { pcmBuffer.reset() }

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        require(minBuf > 0) { "AudioRecord unavailable on this device" }

        @Suppress("MissingPermission")
        val record = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE_HZ,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBuf * 2,
        )
        check(record.state == AudioRecord.STATE_INITIALIZED) {
            record.release()
            "AudioRecord failed to initialize"
        }

        audioRecord = record
        record.startRecording()
        onPartial("Listening (Aidos STT)...")

        recordingJob = scope.launch {
            val chunk = ByteArray(minBuf)
            while (isActive) {
                val read = record.read(chunk, 0, chunk.size)
                if (read > 0) {
                    synchronized(pcmBuffer) {
                        pcmBuffer.write(chunk, 0, read)
                    }
                }
            }
        }
    }

    override suspend fun stop(): Result<String> = withContext(Dispatchers.IO) {
        runCatching {
            recordingJob?.cancel()
            recordingJob = null
            runCatching {
                audioRecord?.stop()
                audioRecord?.release()
            }
            audioRecord = null

            val pcm = synchronized(pcmBuffer) { pcmBuffer.toByteArray() }
            if (pcm.isEmpty()) return@runCatching ""
            val wav = wrapPcmAsWav(pcm, SAMPLE_RATE_HZ, CHANNELS, BITS_PER_SAMPLE)
            val endpoint = endpointUrlProvider()
                ?: error("Aidos Engine STT endpoint not configured")
            transcribeWav(endpoint, wav, activeLanguage)
        }
    }

    override fun cancel() {
        recordingJob?.cancel()
        recordingJob = null
        runCatching {
            audioRecord?.stop()
            audioRecord?.release()
        }
        audioRecord = null
        synchronized(pcmBuffer) { pcmBuffer.reset() }
    }

    private fun transcribeWav(endpointUrl: String, wavBytes: ByteArray, language: String?): String {
        val boundary = "----AidosStt-${UUID.randomUUID()}"
        val conn = (URL(endpointUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5_000
            readTimeout = 30_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setRequestProperty("X-Aidos-Locality", "local_only")
        }
        try {
            DataOutputStream(conn.outputStream).use { out ->
                out.writeBytes("--$boundary\r\n")
                out.writeBytes("Content-Disposition: form-data; name=\"file\"; filename=\"speech.wav\"\r\n")
                out.writeBytes("Content-Type: audio/wav\r\n\r\n")
                out.write(wavBytes)
                out.writeBytes("\r\n")
                if (!language.isNullOrBlank()) {
                    out.writeBytes("--$boundary\r\n")
                    out.writeBytes("Content-Disposition: form-data; name=\"language\"\r\n\r\n")
                    out.writeBytes("$language\r\n")
                }
                out.writeBytes("--$boundary--\r\n")
                out.flush()
            }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) {
                error("Aidos STT HTTP $code: $body")
            }
            val parsed = Json.parseToJsonElement(body).jsonObject
            return parsed["text"]?.jsonPrimitive?.content.orEmpty()
        } finally {
            conn.disconnect()
        }
    }

    private fun wrapPcmAsWav(
        pcm: ByteArray,
        sampleRate: Int,
        channels: Int,
        bitsPerSample: Int,
    ): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val blockAlign = channels * bitsPerSample / 8
        val out = ByteArrayOutputStream(44 + pcm.size)
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray(Charsets.US_ASCII))
            putInt(36 + pcm.size)
            put("WAVE".toByteArray(Charsets.US_ASCII))
            put("fmt ".toByteArray(Charsets.US_ASCII))
            putInt(16)
            putShort(1.toShort())
            putShort(channels.toShort())
            putInt(sampleRate)
            putInt(byteRate)
            putShort(blockAlign.toShort())
            putShort(bitsPerSample.toShort())
            put("data".toByteArray(Charsets.US_ASCII))
            putInt(pcm.size)
        }
        out.write(header.array())
        out.write(pcm)
        return out.toByteArray()
    }

    private companion object {
        const val SAMPLE_RATE_HZ = 16_000
        const val CHANNELS = 1
        const val BITS_PER_SAMPLE = 16
    }
}
