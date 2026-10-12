package dev.aidos.stt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Runs a real Whisper package end to end. Skipped unless `AIDOS_STT_MODEL_DIR` points at one (weights do not
 * live in the repo): `node scripts/stt/fetch-whisper.mjs <dir>` downloads whisper-tiny (~40 MB, int8) for a
 * quick check. `AIDOS_STT_FI_AUDIO` (a WAV of Finnish speech) switches on the Finnish check; what it says is
 * read from `<that file>.txt` (UTF-8; an environment variable would mangle ä and ö under a POSIX locale).
 * `AIDOS_STT_FI_MAX_CER` (default 0.3) is the character error rate tolerated; whisper-tiny
 * is far worse than that on Finnish, whisper-small and up are the usable sizes.
 */
class WhisperRealModelTest {
    private val dir: File? = System.getenv("AIDOS_STT_MODEL_DIR")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun open(): WhisperSttModel {
        assumeTrue("AIDOS_STT_MODEL_DIR not set; skipping real-model STT test", dir != null)
        return WhisperPackage.open("whisper-test", dir!!)
    }

    private fun words(s: String) = s.lowercase().replace(Regex("[^\\p{L}\\p{N} ]"), " ").split(' ').filter { it.isNotEmpty() }

    private fun jfk(): ByteArray = javaClass.getResourceAsStream("/jfk.wav")!!.readBytes()

    @Test
    fun transcribesEnglishSpeech() {
        open().use { model ->
            val r = model.transcribe(SttRequest(jfk(), language = "en"))
            println("STT en: ${r.text}")
            assertEquals("en", r.language)
            val said = words("And so my fellow Americans ask not what your country can do for you ask what you can do for your country")
            val got = words(r.text)
            // Tiny/quantised models slip on a word or two; most of the sentence must be there.
            val hit = said.count { it in got }
            assertTrue(hit >= said.size - 3, "expected most of the JFK quote, got: ${r.text}")
            assertTrue(r.durationSeconds in 10.0..12.0)
        }
    }

    @Test
    fun detectsLanguageWhenNoneIsGiven() {
        open().use { model ->
            val r = model.transcribe(SttRequest(jfk()))
            assertEquals("en", r.language)
            assertTrue(r.text.contains("country", ignoreCase = true), r.text)
        }
    }

    @Test
    fun silenceYieldsNoText() {
        open().use { model ->
            val wav = Wavs.pcm16(ShortArray(16_000 * 3), 16_000)
            assertEquals("", model.transcribe(SttRequest(wav, language = "fi")).text)
        }
    }

    @Test
    fun transcribesFinnishWhenProvided() {
        val audio = System.getenv("AIDOS_STT_FI_AUDIO")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("AIDOS_STT_FI_AUDIO not set; skipping Finnish STT test", audio != null)
        val expected = File(audio!!.path + ".txt").readText(Charsets.UTF_8)
        val maxCer = System.getenv("AIDOS_STT_FI_MAX_CER")?.toDoubleOrNull() ?: 0.3
        open().use { model ->
            val r = model.transcribe(SttRequest(audio.readBytes(), language = "fi"))
            println("STT fi: ${r.text}")
            val want = words(expected).joinToString(" ")
            val got = words(r.text).joinToString(" ")
            val cer = editDistance(want, got).toDouble() / want.length
            println("STT fi CER: $cer")
            assertTrue(cer <= maxCer, "expected '$expected', got '${r.text}' (CER $cer)")
        }
    }

    private fun editDistance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1).also { it[0] = i }
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }
}
