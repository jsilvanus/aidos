package dev.aidos.vision

import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import org.junit.Assume.assumeTrue

class ByteLevelBpeTokenizerTest {
    // A four-merge vocabulary over the byte alphabet: "Ġ"=space, so "Ġa" and "ab" exist.
    private val tiny = """
        {"model":{"type":"BPE","vocab":{"a":0,"b":1,"Ġ":2,"ab":3,"Ġab":4,"1":5,"é":6,"Ã":7,"©":8},
         "merges":["a b","Ġ ab"]},
         "added_tokens":[{"id":9,"content":"<eot>","special":true}]}
    """.trimIndent()

    @Test
    fun appliesMergesInRankOrder() {
        val t = ByteLevelBpeTokenizer.fromJson(tiny)
        assertContentEquals(intArrayOf(3), t.encode("ab"))
        // "Ġ ab" merges only after "a b", so " ab" ends up as one token.
        assertContentEquals(intArrayOf(4), t.encode(" ab"))
    }

    @Test
    fun addedTokensAreMatchedLiterallyAndNeverPrinted() {
        val t = ByteLevelBpeTokenizer.fromJson(tiny)
        assertContentEquals(intArrayOf(3, 9, 3), t.encode("ab<eot>ab"))
        assertEquals("abab", t.decode(intArrayOf(3, 9, 3)))
    }

    @Test
    fun digitsAreSplitOneByOne() {
        val t = ByteLevelBpeTokenizer.fromJson(tiny)
        assertContentEquals(intArrayOf(5, 5, 5), t.encode("111"))
    }

    @Test
    fun multiByteCharactersRoundTripThroughTheByteAlphabet() {
        // "é" is bytes C3 A9, which the alphabet writes as "Ã" "©". Only the two halves are in this
        // vocabulary, so it encodes as those two tokens and decodes back to one character.
        val t = ByteLevelBpeTokenizer.fromJson(tiny.replace("\"é\":6,", ""))
        assertContentEquals(intArrayOf(7, 8), t.encode("é"))
        assertEquals("é", t.decode(intArrayOf(7, 8)))
    }

    @Test
    fun rejectsATokenizerItCannotReproduce() {
        assertFailsWith<IllegalArgumentException> {
            ByteLevelBpeTokenizer.fromJson("""{"model":{"type":"Unigram","vocab":[]}}""")
        }
        assertFailsWith<IllegalArgumentException> {
            ByteLevelBpeTokenizer.fromJson("""{"normalizer":{"type":"NFC"},"model":{"type":"BPE","vocab":{},"merges":[]}}""")
        }
    }

    /** Golden ids from the reference `tokenizers` library, against the real SmolVLM tokenizer.json. */
    @Test
    fun matchesTheReferenceTokenizerOnSmolVlm() {
        val dir = System.getenv("AIDOS_VISION_MODEL_DIR")?.takeIf { it.isNotBlank() }?.let(::File)
        assumeTrue("set AIDOS_VISION_MODEL_DIR to a SmolVLM package", dir != null && File(dir, "tokenizer.json").isFile)
        val t = ByteLevelBpeTokenizer.fromJson(File(dir!!, "tokenizer.json").readText())
        assertContentEquals(intArrayOf(1780, 2380, 314, 260, 23710, 47), t.encode("What color is the rectangle?"))
        assertContentEquals(
            intArrayOf(19556, 28, 905, 17, 216, 34, 32, 34, 36, 5549, 114, 9023, 31022, 7466, 1614, 198),
            t.encode("Hello, world! 2024 ääkköset\n"),
        )
        assertContentEquals(
            intArrayOf(1, 11126, 42, 49190, 37964, 216, 33, 34, 35, 273, 747, 30, 49279, 198, 9519, 9531, 42),
            t.encode("<|im_start|>User:<image>Describe 123 pics.<end_of_utterance>\nAssistant:"),
        )
        assertEquals("Hello, world! 2024 ääkköset\n", t.decode(t.encode("Hello, world! 2024 ääkköset\n")))
    }
}
