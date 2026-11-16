package dev.aidos.stt

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.Assume.assumeTrue

/**
 * Runs the real Silero VAD v5 graph. Skipped unless `AIDOS_VAD_MODEL` points at `silero_vad.onnx`
 * (`onnx/model.onnx` of Hugging Face `onnx-community/silero-vad`; `node scripts/stt/fetch-silero-vad.mjs <dir>`).
 */
class SileroRealModelTest {
    private val file: File? = System.getenv("AIDOS_VAD_MODEL")?.takeIf { it.isNotBlank() }?.let(::File)

    private fun vad(): SileroVad {
        assumeTrue("AIDOS_VAD_MODEL not set; skipping real Silero VAD test", file != null)
        return SileroVad(OrtSileroGraph(file!!))
    }

    private fun jfk(): FloatArray =
        WavDecoder.decode16k(javaClass.getResourceAsStream("/jfk.wav")!!.readBytes()).samples

    @Test
    fun findsTheSpeechInAClipAndNothingInSilence() {
        vad().use { vad ->
            val speech = jfk()
            val segs = vad.segments(speech)
            println("silero segments over ${speech.size / 16_000.0}s: $segs")
            assertTrue(segs.isNotEmpty(), "no speech found in the JFK clip")
            val covered = segs.sumOf { it.end - it.start }
            assertTrue(covered > speech.size / 2, "only $covered of ${speech.size} samples marked as speech")
            assertEquals(emptyList(), vad.segments(FloatArray(16_000 * 3)))
        }
    }

    @Test
    fun leadingAndTrailingSilenceIsExcluded() {
        vad().use { vad ->
            val speech = jfk()
            val padded = FloatArray(16_000 * 2) + speech + FloatArray(16_000 * 2)
            val segs = vad.segments(padded)
            assertTrue(segs.first().start > 16_000, "leading 2 s of silence should be outside the first segment: $segs")
            assertTrue(segs.last().end < padded.size - 16_000, "trailing silence should be outside the last segment: $segs")
        }
    }

    @Test
    fun theSameClipGivesTheSameSegmentsTwice() {
        vad().use { vad ->
            val speech = jfk()
            assertEquals(vad.segments(speech), vad.segments(speech), "state must be reset between recordings")
        }
    }
}
