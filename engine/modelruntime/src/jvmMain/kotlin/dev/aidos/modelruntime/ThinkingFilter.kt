package dev.aidos.modelruntime

/** One thinking-block marker pair: the opening marker and the closing markers that end it. */
data class ThinkingMarkers(val opening: String, val closings: List<String>)

/**
 * Removes one leading thinking block from a reply that arrives in pieces (RFC-0129 section 10).
 *
 * A reply may open with a block the model wrote for itself. That text must not reach the user,
 * the returned text, or the tool-call parser. Markers can be split across pieces at any character,
 * so the filter holds back only what could still be part of a marker.
 *
 * The Gemma 4 opener is the bare channel marker, followed by the channel name (for example thought).
 * The closer `<channel|>` is confirmed from the model's token table; `<|channel|>` is kept as a tolerated
 * variant and is tested. Special tokens such as `<|think|>` are not thinking blocks and are not stripped.
 */
class ThinkingFilter(private val pairs: List<ThinkingMarkers> = DEFAULT_PAIRS) {
    private enum class State { START, THINKING, SKIP_WHITESPACE, PASS }

    private var state = State.START
    private var active: ThinkingMarkers? = null

    /** Text held back: the start of a reply while undecided, or the tail of a thought while looking for its close. */
    private val pending = StringBuilder()

    private val keepWhileThinking: Int = (pairs.flatMap { it.closings }.maxOfOrNull { it.length } ?: 1) - 1

    /** Gives the next piece of model output; returns the text that may be shown now (possibly empty). */
    fun accept(piece: String): String = when (state) {
        State.START -> acceptStart(piece)
        State.THINKING -> acceptThinking(piece)
        State.SKIP_WHITESPACE -> {
            val rest = piece.trimStart()
            if (rest.isEmpty()) {
                ""
            } else {
                state = State.PASS
                rest
            }
        }
        State.PASS -> piece
    }

    /** Call once when generation ends; returns any held-back text that is safe to show. */
    fun finish(): String {
        val held = when (state) {
            State.START -> pending.toString()
            else -> ""
        }
        pending.clear()
        return held
    }

    private fun acceptStart(piece: String): String {
        pending.append(piece)
        val body = pending.indexOfFirst { !it.isWhitespace() }
        if (body < 0) return ""
        val rest = pending.substring(body)
        val opened = pairs.firstOrNull { rest.startsWith(it.opening) }
        if (opened != null) {
            active = opened
            pending.setLength(0)
            state = State.THINKING
            return accept(rest.substring(opened.opening.length))
        }
        if (pairs.any { it.opening.startsWith(rest) }) return ""
        state = State.PASS
        val whole = pending.toString()
        pending.clear()
        return whole
    }

    private fun acceptThinking(piece: String): String {
        pending.append(piece)
        val markers = active ?: return ""
        var found = -1
        var foundLength = 0
        for (closing in markers.closings) {
            val at = pending.indexOf(closing)
            if (at >= 0 && (found < 0 || at < found || (at == found && closing.length > foundLength))) {
                found = at
                foundLength = closing.length
            }
        }
        if (found < 0) {
            if (pending.length > keepWhileThinking) pending.delete(0, pending.length - keepWhileThinking)
            return ""
        }
        val rest = pending.substring(found + foundLength)
        pending.clear()
        active = null
        state = State.SKIP_WHITESPACE
        return accept(rest)
    }

    companion object {
        /** The marker pairs of RFC-0129 section 10. */
        val DEFAULT_PAIRS: List<ThinkingMarkers> = listOf(
            ThinkingMarkers("<|channel>", listOf("<channel|>", "<|channel|>")),
            ThinkingMarkers("<think>", listOf("</think>")),
        )

        /** A whole reply at once, with the same result as streaming it. */
        fun strip(whole: String): String = ThinkingFilter().let { it.accept(whole) + it.finish() }
    }
}

/**
 * The visible reply of one generation (RFC-0129 section 10): the tokens minus any leading thinking
 * block, with stop sequences checked on what is visible, so a stop string inside a thought does not
 * end the reply. Shared by the desktop and Android adapters so they cannot drift.
 */
class ReplyAssembler(private val stopSequences: List<String>) {
    private val filter = ThinkingFilter()
    private val longestStop = stopSequences.maxOfOrNull { it.length } ?: 0
    private val visible = StringBuilder()

    /** True once a stop sequence has completed in the visible text. */
    var stoppedOnSequence: Boolean = false
        private set

    /** The visible reply so far. */
    val text: String get() = visible.toString()

    /** Adds one token; returns the visible text it contributed (empty when it was thinking or held back). */
    fun accept(token: String): String {
        val shown = filter.accept(token)
        if (shown.isEmpty()) return shown
        visible.append(shown)
        if (longestStop > 0 && endsWithStop(shown.length)) stoppedOnSequence = true
        return shown
    }

    /** Call once when generation ends; returns held-back visible text, if any. */
    fun finish(): String {
        val tail = filter.finish()
        visible.append(tail)
        return tail
    }

    /** Whether a stop sequence was completed by the last [appended] characters. */
    private fun endsWithStop(appended: Int): Boolean {
        val from = (visible.length - appended - longestStop + 1).coerceAtLeast(0)
        return stopSequences.any { it.isNotEmpty() && visible.indexOf(it, from) >= 0 }
    }
}
