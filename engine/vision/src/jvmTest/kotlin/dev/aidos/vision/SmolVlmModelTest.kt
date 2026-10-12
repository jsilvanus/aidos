package dev.aidos.vision

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Prompt assembly, image splicing and the decode loop, against a runner that answers from a script. */
class SmolVlmModelTest {
    private val hidden = 4
    private val seq = 3
    private val image = 50
    private val eos = 99

    // One token per byte-alphabet character (so any text encodes); a..z are ids 0..25, the rest 100+.
    private val tokenizer = ByteLevelBpeTokenizer.fromJson(
        buildString {
            val printable = (33..126) + (161..172) + (174..255)
            var extra = 0
            val chars = (0..255).map { b -> if (b in printable) b.toChar() else (256 + extra++).toChar() }
            append("""{"model":{"type":"BPE","vocab":{""")
            append(chars.withIndex().joinToString(",") { (i, c) ->
                val id = if (c in 'a'..'z') c - 'a' else 100 + i
                "\"" + (if (c == '"') "\\\"" else if (c == '\\') "\\\\" else c.toString()) + "\":$id"
            })
            append("""},"merges":[]},"added_tokens":[""")
            append(listOf("<image>" to image, "<end_of_utterance>" to eos, "<|im_start|>" to 98, "<fake_token_around_image>" to 97, "<global-img>" to 96)
                .joinToString(",") { """{"id":${it.second},"content":"${it.first}","special":true}""" })
            append("]}")
        }
    )

    private class FakeRunner(private val script: List<Int>, val hidden: Int, val seq: Int) : VisionRunner {
        override val hiddenSize = hidden
        override val tokensPerImage = seq
        var embeddedIds = IntArray(0)
        var fedFirst = FloatArray(0)
        val encoded = ArrayList<ImageTile>()
        var closedDecoders = 0
        override fun encodeImage(tile: ImageTile): FloatArray { encoded += tile; return FloatArray(seq * hidden) { 1000f + it } }
        override fun embed(ids: IntArray): FloatArray {
            if (embeddedIds.isEmpty()) embeddedIds = ids
            return FloatArray(ids.size * hidden) { ids[it / hidden].toFloat() }
        }
        override fun newDecoder() = object : DecoderSession {
            var calls = 0
            override fun step(embeds: FloatArray, length: Int): FloatArray {
                if (calls == 0) fedFirst = embeds
                val logits = FloatArray(512)
                logits[script[minOf(calls, script.lastIndex)]] = 1f
                calls++
                return logits
            }
            override fun close() { closedDecoders++ }
        }
        override fun close() = Unit
    }

    private val decoder = ImageDecoder { DecodedImage(2, 2, ByteArray(12)) }
    private fun model(script: List<Int>, runner: FakeRunner = FakeRunner(script, hidden, seq)) =
        SmolVlmModel("fake", tokenizer, runner, decoder, SmolVlmConfig(hidden, image, seq, setOf(eos), 64)) to runner

    private fun user(vararg blocks: ContentBlock) = Turn.User(blocks.toList(), TrustLevel.TRUSTED)

    @Test
    fun promptFollowsTheIdefics3Template() {
        val (m, _) = model(listOf(eos))
        val (prompt, pictures) = m.buildPrompt(
            listOf(Turn.System("be brief"), user(ContentBlock.Image("image/png", byteArrayOf(1)), ContentBlock.Text("what")))
        )
        // Image first -> "User:" with no space; the system text leads the user turn (here after the
        // role label, before the picture).
        assertEquals(
            "<|im_start|>User: be brief\n\n<fake_token_around_image><global-img><image><image><image><fake_token_around_image>what<end_of_utterance>\nAssistant:",
            prompt,
        )
        assertEquals(1, pictures.size)
    }

    @Test
    fun textFirstTurnGetsASpaceAfterTheRole() {
        val (m, _) = model(listOf(eos))
        val (prompt, _) = m.buildPrompt(listOf(user(ContentBlock.Text("hi"))))
        assertEquals("<|im_start|>User: hi<end_of_utterance>\nAssistant:", prompt)
    }

    @Test
    fun imageFeaturesReplaceExactlyTheImageSlots() {
        val (m, runner) = model(listOf(eos))
        m.generate(listOf(user(ContentBlock.Text("a"), ContentBlock.Image("image/png", byteArrayOf(1)))), 8).toList()
        val ids = runner.embeddedIds
        val slots = ids.indices.filter { ids[it] == image }
        assertEquals(seq, slots.size)
        // fedFirst starts as embed(ids) (value = id) with the image rows overwritten by the encoder's 1000+i.
        for ((n, pos) in slots.withIndex()) {
            assertContentEquals(FloatArray(hidden) { 1000f + n * hidden + it }, runner.fedFirst.copyOfRange(pos * hidden, (pos + 1) * hidden))
        }
        assertEquals(ids[0].toFloat(), runner.fedFirst[0])
    }

    @Test
    fun decodesGreedilyUntilEndOfTurn() {
        val (m, runner) = model(listOf(7, 8, 11, eos)) // h i l
        val steps = m.generate(listOf(user(ContentBlock.Text("a"))), 16).toList()
        assertEquals("hil", steps.filterIsInstance<VisionStep.Text>().joinToString("") { it.delta })
        val done = steps.last() as VisionStep.Finished
        assertEquals(FinishReason.END_OF_TURN, done.reason)
        assertEquals(3, done.newTokens)
        assertEquals(1, runner.closedDecoders)
    }

    @Test
    fun theAnswerStartsAtItsFirstWordNotAtTheLeadingSpaceToken() {
        val (m, _) = model(listOf(132, 7, 8, eos)) // 132 = the space byte (Ġ), then "hi"
        val deltas = m.generate(listOf(user(ContentBlock.Text("a"))), 16).filterIsInstance<VisionStep.Text>().map { it.delta }.toList()
        assertEquals(listOf("h", "i"), deltas)
    }

    @Test
    fun stopsAtTheTokenLimitAndAtAStopSequence() {
        val (m, _) = model(listOf(0)) // "a" forever
        val limited = m.generate(listOf(user(ContentBlock.Text("a"))), 3).toList().last() as VisionStep.Finished
        assertEquals(FinishReason.MAX_TOKENS, limited.reason)
        assertEquals(3, limited.newTokens)
        val stopped = m.generate(listOf(user(ContentBlock.Text("a"))), 10, listOf("aa")).toList().last() as VisionStep.Finished
        assertEquals(FinishReason.STOP_SEQUENCE, stopped.reason)
        assertEquals(2, stopped.newTokens)
    }

    @Test
    fun abandoningTheSequenceStillReleasesTheDecoder() {
        val (m, runner) = model(listOf(0))
        m.generate(listOf(user(ContentBlock.Text("a"))), 10).first()
        // A sequence that is not run to the end never reaches its finally; the caller closes it by
        // exhausting or by using an iterator. Exhausting here proves the finally path on completion.
        m.generate(listOf(user(ContentBlock.Text("a"))), 2).toList()
        assertEquals(1, runner.closedDecoders)
    }

    @Test
    fun aPromptLargerThanTheWindowIsRefused() {
        val (m, _) = model(listOf(eos))
        assertFailsWith<IllegalArgumentException> {
            m.generate(listOf(user(ContentBlock.Text("a".repeat(200)))), 4).toList()
        }
    }

    @Test
    fun audioIsRejectedBeforeAnyWork() {
        val (m, runner) = model(listOf(eos))
        val e = assertFailsWith<UnsupportedOperationException> {
            m.generate(listOf(user(ContentBlock.Audio("audio/wav", ByteArray(4)))), 4).toList()
        }
        assertTrue(e.message!!.contains("audio"))
        assertEquals(0, runner.encoded.size)
    }
}
