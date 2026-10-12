package dev.aidos.stt

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** A hand-made byte-level vocabulary: just enough to prove the BPE and the special-token handling. */
internal val TINY_TOKENIZER_JSON = """
{
  "added_tokens": [
    {"id": 5, "content": "<|endoftext|>", "special": true},
    {"id": 6, "content": "<|startoftranscript|>", "special": true},
    {"id": 7, "content": "<|fi|>", "special": true},
    {"id": 8, "content": "<|en|>", "special": true},
    {"id": 9, "content": "<|transcribe|>", "special": true},
    {"id": 10, "content": "<|startofprev|>", "special": true},
    {"id": 11, "content": "<|nospeech|>", "special": true},
    {"id": 12, "content": "<|notimestamps|>", "special": true},
    {"id": 13, "content": "<|0.00|>", "special": false}
  ],
  "model": {
    "vocab": {"a": 0, "b": 1, "Ġ": 2, "ab": 3, "Ġab": 4},
    "merges": ["a b", "Ġ ab"]
  }
}
""".trimIndent()

class WhisperTokenizerTest {
    private val tok = WhisperTokenizer.fromJson(TINY_TOKENIZER_JSON)

    @Test
    fun findsTheMarkers() {
        assertEquals(5, tok.eot)
        assertEquals(6, tok.sot)
        assertEquals(12, tok.noTimestamps)
        assertEquals(13, tok.timestampBegin)
        assertEquals(mapOf("fi" to 7, "en" to 8), tok.languages)
        assertEquals(7, tok.languageToken("fi-FI"))
        assertFailsWith<SttRequestException> { tok.languageToken("xx") }
    }

    @Test
    fun decodesBytesAndSkipsMarkers() {
        assertEquals(" abab", tok.decode(listOf(6, 7, 4, 3, 13, 5)))
    }

    @Test
    fun encodesWithMerges() {
        assertEquals(listOf(4, 4), tok.encode(" ab ab"))
        assertEquals(listOf(3, 0), tok.encode("aba"))
    }
}
