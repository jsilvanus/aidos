package dev.aidos.decisions

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** Tokenizer behaviour on a tiny hand-written tokenizer.json, so it runs without any model files. */
class GemmaBpeTokenizerTest {
    private val json = """
    {"added_tokens":[
      {"id":0,"content":"<pad>","lstrip":false},{"id":1,"content":"<eos>","lstrip":false},
      {"id":2,"content":"<bos>","lstrip":false},{"id":3,"content":"<unk>","lstrip":false},
      {"id":4,"content":"<mask>","lstrip":true}],
     "normalizer":{"type":"Replace","pattern":{"String":" "},"content":"▁"},
     "pre_tokenizer":{"type":"Metaspace","replacement":"▁","prepend_scheme":"always","split":true},
     "model":{"type":"BPE","dropout":null,"unk_token":"<unk>","byte_fallback":true,
       "vocab":{"<pad>":0,"<eos>":1,"<bos>":2,"<unk>":3,"<mask>":4,"▁":5,"a":6,"b":7,"▁a":8,"▁ab":9,"ab":10,"<0xC3>":11,"<0xA4>":12},
       "merges":["▁ a","▁a b"]}}
    """.trimIndent()
    private val tok = GemmaBpeTokenizer.fromJson(json)

    @Test fun metaspacePrependsAndMerges() = assertEquals(listOf(9), tok.encode("ab"))
    @Test fun spacesStartNewPieces() = assertEquals(listOf(9, 9), tok.encode("ab ab"))
    @Test fun emptyTextHasNoTokens() = assertEquals(emptyList(), tok.encode(""))
    @Test fun unknownCodePointFallsBackToBytes() = assertEquals(listOf(5, 11, 12), tok.encode("ä"))
    @Test fun addedTokenSplitsAndLstripEatsWhitespace() = assertEquals(listOf(9, 4, 8), tok.encode("ab <mask> a"))
    @Test fun specialIds() { assertEquals(4, tok.maskId); assertEquals(2, tok.clsId); assertEquals(1, tok.sepId) }

    @Test fun refusesUnsupportedNormalizer() {
        assertFailsWith<IllegalArgumentException> { GemmaBpeTokenizer.fromJson(json.replace("\"Replace\"", "\"Lowercase\"")) }
    }
}
