package dev.aidos.stt

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals

/** How [WhisperSttModel] windows audio when a [SpeechVad] is present. */
class WhisperSttModelVadTest {
    private val tok = WhisperTokenizer.fromJson(TINY_TOKENIZER_JSON)
    private val rate = 16_000

    private class Graphs : WhisperGraphs {
        override val melBins = 80
        val encodes = ArrayList<Int>()
        override fun encode(features: FloatArray): WhisperEncoded {
            encodes += features.size
            return object : WhisperEncoded {
                override fun decoding() = object : WhisperDecoding {
                    // Says nothing: immediately end-of-text. Only the number of windows matters here.
                    override fun step(tokens: IntArray) = FloatArray(14) { -10f }.also { it[tok().eot] = 5f }
                    override fun close() = Unit
                }
                override fun close() = Unit
            }
        }
        override fun close() = Unit
        private fun tok() = WhisperTokenizer.fromJson(TINY_TOKENIZER_JSON)
    }

    private class FixedVad(val segs: List<SpeechSegment>) : SpeechVad, AutoCloseable {
        var closed = false
        override fun segments(samples: FloatArray) = segs
        override fun close() { closed = true }
    }

    private fun tone(seconds: Int) =
        Wavs.pcm16(ShortArray(seconds * rate) { (8000 * sin(2 * PI * 300.0 * it / rate)).toInt().toShort() }, rate)

    private fun windows(seconds: Int, vararg segs: SpeechSegment): Int {
        val g = Graphs()
        WhisperSttModel("m", tok, g, vad = FixedVad(segs.toList())).transcribe(SttRequest(tone(seconds), language = "fi"))
        return g.encodes.size
    }

    private fun s(fromSec: Double, toSec: Double) = SpeechSegment((fromSec * rate).toInt(), (toSec * rate).toInt())

    @Test
    fun noSpeechMeansNoDecoding() {
        assertEquals(0, windows(10))
    }

    @Test
    fun segmentsThatFitOneWindowShareIt() {
        assertEquals(1, windows(40, s(1.0, 5.0), s(20.0, 28.0)))
    }

    @Test
    fun aSegmentThatWouldOverflowStartsANewWindowInsteadOfBeingCut() {
        // 2..20 and 25..38: together the span is 36 s, over 30 s, so two windows, each whole.
        assertEquals(2, windows(40, s(2.0, 20.0), s(25.0, 38.0)))
    }

    @Test
    fun unbrokenSpeechOverThirtySecondsStillGetsCut() {
        assertEquals(2, windows(45, s(0.0, 44.0)))
    }

    @Test
    fun theVadIsClosedWithTheModel() {
        val vad = FixedVad(emptyList())
        WhisperSttModel("m", tok, Graphs(), vad = vad).close()
        assertEquals(true, vad.closed)
    }

    @Test
    fun withoutAVadTheOldQuietestPointCutStillApplies() {
        val g = Graphs()
        WhisperSttModel("m", tok, g).transcribe(SttRequest(tone(40), language = "fi"))
        assertEquals(2, g.encodes.size)
    }
}
