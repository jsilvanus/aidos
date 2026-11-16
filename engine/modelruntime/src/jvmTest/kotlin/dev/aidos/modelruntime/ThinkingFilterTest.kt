package dev.aidos.modelruntime

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RFC-0129 section 10: a leading thinking block is removed from streamed, returned and parsed text. */
class ThinkingFilterTest {
    private fun streamed(vararg pieces: String): String {
        val filter = ThinkingFilter()
        val out = StringBuilder()
        for (piece in pieces) out.append(filter.accept(piece))
        out.append(filter.finish())
        return out.toString()
    }

    @Test
    fun replyWithoutThinkingIsUnchanged() {
        assertEquals("Hello there", streamed("Hello ", "there"))
        assertEquals("Hello there", ThinkingFilter.strip("Hello there"))
    }

    @Test
    fun blockInOnePieceIsRemoved() {
        assertEquals("Answer", streamed("<|channel>thought I plan.<channel|>Answer"))
    }

    @Test
    fun openingMarkerSplitOverThreePiecesIsRemoved() {
        assertEquals("Yes", streamed("<|", "chan", "nel>thought hidden<channel|>Yes"))
    }

    @Test
    fun closingMarkerSplitOverTwoPiecesIsRemoved() {
        assertEquals("Yes", streamed("<|channel>thought x<chan", "nel|>Yes"))
    }

    @Test
    fun oneCharacterPerPieceMatchesOnePiece() {
        val samples = listOf(
            "<|channel>thought plan {\"tool\":\"x\"}<channel|>Final",
            "\n<think>a</b>c</think>\n\nDone",
            "<|chan ge log",
            "<think>never closes",
            "Plain <think>kept</think> text",
        )
        for (sample in samples) {
            assertEquals(ThinkingFilter.strip(sample), streamed(*sample.map { it.toString() }.toTypedArray()), sample)
        }
    }

    @Test
    fun leadingWhitespaceBeforeOpeningMarkerIsRemoved() {
        assertEquals("Ok", streamed("\n  <think>x</think>Ok"))
    }

    @Test
    fun textThatOnlyLooksLikeAMarkerStartIsPassedThrough() {
        assertEquals("<|change log", streamed("<|chan", "ge log"))
    }

    @Test
    fun unclosedBlockGivesEmptyText() {
        assertEquals("", streamed("<think>never ", "closes"))
        assertEquals("", streamed("<|channel>thought still going"))
    }

    @Test
    fun markerLaterInTheReplyIsOrdinaryText() {
        assertEquals("Answer then <think>kept</think> text", streamed("Answer then <think>kept</think> text"))
    }

    @Test
    fun thinkPairIsRemoved() {
        assertEquals("Final", streamed("<think>plan</think>\nFinal"))
    }

    @Test
    fun whitespaceDirectlyAfterClosingIsRemovedAndNoMore() {
        assertEquals("Final  ", streamed("<think>x</think>\n\n", "Final  "))
    }

    @Test
    fun gemmaClosingVariantLessThanPipeIsTested() {
        assertEquals("A", streamed("<|channel>thought x<channel|>A"))
    }

    @Test
    fun gemmaClosingVariantPipeBeforeGreaterThanIsTested() {
        assertEquals("A", streamed("<|channel>thought x<|channel|>A"))
    }

    @Test
    fun thinkCloseVariantIsTested() {
        assertEquals("A", streamed("<think>x</think>A"))
    }

    @Test
    fun stripMatchesStreaming() {
        val whole = "\n<|channel>thought hidden<channel|>\nVisible <think>tail"
        assertEquals(streamed(whole), ThinkingFilter.strip(whole))
        assertEquals("Visible <think>tail", ThinkingFilter.strip(whole))
    }

    @Test
    fun reply_assembler_keeps_stop_sequence_inside_thought_from_ending_reply() {
        val reply = ReplyAssembler(listOf("STOP"))
        val shown = listOf("<think>", "STOP", "</think>", "ok").map { reply.accept(it) }
        assertEquals(listOf("", "", "", "ok"), shown)
        assertTrue(!reply.stoppedOnSequence)
        reply.finish()
        assertEquals("ok", reply.text)
    }

    @Test
    fun reply_assembler_stops_on_stop_sequence_in_visible_text() {
        val reply = ReplyAssembler(listOf("STOP"))
        listOf("<think>", "x", "</think>", "ok ", "STOP").forEach { reply.accept(it) }
        assertTrue(reply.stoppedOnSequence)
        assertEquals("ok STOP", reply.text)
    }

    @Test
    fun reply_assembler_text_has_no_tool_call_from_inside_thought() {
        val reply = ReplyAssembler(emptyList())
        val toolJson = "{\"tool\": \"shell\", \"args\": {}}"
        listOf("<|channel>thought ", toolJson, "<channel|>", "Answer").forEach { reply.accept(it) }
        reply.finish()
        assertEquals("Answer", reply.text)
    }
}
