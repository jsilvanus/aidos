package dev.aidos.stt

import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The decode loop against scripted logits, so it runs without weights. */
class WhisperSttModelTest {
    private val tok = WhisperTokenizer.fromJson(TINY_TOKENIZER_JSON)
    private val vocab = 14

    /** [script] sees every token fed so far (prefix first) and returns the next logits. */
    private class Fake(private val vocab: Int, val script: (List<Int>) -> Map<Int, Float>) : WhisperGraphs {
        override val melBins = 80
        val encodes = ArrayList<Int>()
        val decodings = ArrayList<List<Int>>()

        override fun encode(features: FloatArray): WhisperEncoded {
            encodes += features.size
            return object : WhisperEncoded {
                override fun decoding(): WhisperDecoding {
                    val seen = ArrayList<Int>().also { decodings += it }
                    return object : WhisperDecoding {
                        override fun step(tokens: IntArray): FloatArray {
                            tokens.forEach { seen += it }
                            return FloatArray(vocab) { -10f }.also { l -> script(seen).forEach { (id, v) -> l[id] = v } }
                        }
                        override fun close() = Unit
                    }
                }
                override fun close() = Unit
            }
        }

        override fun close() = Unit
    }

    private fun speech(seconds: Double = 2.0) =
        Wavs.pcm16(ShortArray((seconds * 16_000).toInt()) { (8000 * sin(2 * PI * 300.0 * it / 16_000)).toInt().toShort() }, 16_000)

    /** Says " ab" then "ab" then ends, whatever the prefix. */
    private fun sayAbab(seen: List<Int>): Map<Int, Float> {
        val spoken = seen.size - seen.indexOf(12) - 1
        return when (spoken) { 0 -> mapOf(4 to 5f); 1 -> mapOf(3 to 5f); else -> mapOf(5 to 5f) }
    }

    @Test
    fun feedsTheForcedPrefixAndDecodesGreedily() {
        val g = Fake(vocab, ::sayAbab)
        val r = WhisperSttModel("m", tok, g).transcribe(SttRequest(speech(), language = "fi"))
        assertEquals("abab", r.text)
        assertEquals("fi", r.language)
        assertEquals(listOf(6, 7, 9, 12), g.decodings.single().take(4))
        assertEquals(80 * 3000, g.encodes.single())
    }

    @Test
    fun detectsLanguageFromTheStartTokenWhenNoneIsGiven() {
        val g = Fake(vocab) { seen -> if (seen == listOf(6)) mapOf(7 to 1f, 8 to 3f) else sayAbab(seen) }
        val r = WhisperSttModel("m", tok, g).transcribe(SttRequest(speech()))
        assertEquals("en", r.language)
        assertEquals(listOf(6, 8, 9, 12), g.decodings.last().take(4))
    }

    @Test
    fun promptBecomesPreviousTextContext() {
        val g = Fake(vocab, ::sayAbab)
        WhisperSttModel("m", tok, g).transcribe(SttRequest(speech(), language = "fi", prompt = "ab"))
        assertEquals(listOf(10, 4, 6, 7, 9, 12), g.decodings.single().take(6))
    }

    @Test
    fun neverEmitsMarkersOrTimestamps() {
        // A timestamp (13) and <|nospeech|> (11) outscore the text token; they must be skipped.
        val g = Fake(vocab) { seen -> if (seen.size < 5) mapOf(13 to 9f, 11 to 9f, 4 to 5f) else mapOf(5 to 5f) }
        assertEquals("ab", WhisperSttModel("m", tok, g).transcribe(SttRequest(speech(), language = "fi")).text)
    }

    @Test
    fun beginSuppressedTokensAreSkippedOnlyForTheFirstToken() {
        val g = Fake(vocab) { seen ->
            when (seen.size) { 4 -> mapOf(2 to 9f, 3 to 5f); 5 -> mapOf(2 to 9f, 5 to 1f); else -> mapOf(5 to 5f) }
        }
        val cfg = WhisperGenerationConfig(beginSuppressTokens = setOf(2))
        // First token: 2 is begin-suppressed so 3 wins. Second: 2 (" ") is allowed and outscores EOT.
        assertEquals("ab", WhisperSttModel("m", tok, g, cfg).transcribe(SttRequest(speech(), language = "fi")).text.replace(" ", ""))
    }

    @Test
    fun cutsARepeatingLoop() {
        val g = Fake(vocab) { mapOf(3 to 5f) } // never ends on its own
        val text = WhisperSttModel("m", tok, g).transcribe(SttRequest(speech(), language = "fi")).text
        assertEquals("ab", text)
    }

    @Test
    fun silenceIsNotSentToTheModel() {
        val g = Fake(vocab, ::sayAbab)
        val r = WhisperSttModel("m", tok, g).transcribe(SttRequest(Wavs.pcm16(ShortArray(32_000), 16_000), language = "fi"))
        assertEquals("", r.text)
        assertTrue(g.encodes.isEmpty())
    }

    @Test
    fun longAudioIsCutIntoWindowsAndJoined() {
        val g = Fake(vocab, ::sayAbab)
        val r = WhisperSttModel("m", tok, g).transcribe(SttRequest(speech(70.0), language = "fi"))
        assertEquals(3, g.encodes.size)
        assertEquals("abab abab abab", r.text)
        assertEquals(70.0, r.durationSeconds, 0.01)
    }

    @Test
    fun refusesNonWavAndUnknownLanguage() {
        val m = WhisperSttModel("m", tok, Fake(vocab, ::sayAbab))
        assertFailsWith<SttRequestException> { m.transcribe(SttRequest(byteArrayOf(1, 2, 3))) }
        assertFailsWith<SttRequestException> { m.transcribe(SttRequest(speech(), language = "zz")) }
    }
}
