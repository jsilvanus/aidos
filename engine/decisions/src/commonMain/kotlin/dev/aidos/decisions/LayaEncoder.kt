package dev.aidos.decisions

/** One question rendered as model input: `[CLS] <type> question: ins [SEP] [MASK] opt0 [MASK] opt1 ... [SEP] state [SEP]`. */
internal class EncodedRow(val ids: IntArray, val markers: IntArray, val qtype: Int)

internal const val QTYPE_CHOICE = 0
internal const val QTYPE_SCORE = 1
internal const val QTYPE_NOUL = 2

/**
 * Port of Laya's `build_head` / `build_sequence` (laya/common.py), for the sequential option layout
 * only. Behaviour is pinned by the golden sequences in laya_golden.json; keep them in step with the
 * checkpoint's config rather than "improving" the rendering, because the head was trained on it.
 */
internal class LayaEncoder(
    private val tok: GemmaBpeTokenizer,
    private val maxLen: Int,
    private val headMaxLen: Int,
) {
    class State(val ids: List<Int>)

    fun encodeState(text: String): State =
        State(tok.encode(text.replace(tok.maskToken, " ")))

    fun encode(qid: String, q: DecisionQuestion, state: State): Pair<EncodedRow, Int> {
        val opts = renderOptions(q)
        if (opts.size < 1) throw DecisionRequestException("question '$qid' has no options")
        if (opts.size > MAX_OPTIONS) throw DecisionRequestException("question '$qid' has more than $MAX_OPTIONS options")
        val ins = q.instructions.replace(tok.maskToken, " ")
        var head = tok.encode("${qtypeName(q)} question: $ins")
        val optIds = opts.map { o ->
            listOf(tok.maskId) + tok.encode(" " + o.replace(tok.maskToken, " ")).take(OPTION_TOKENS)
        }.toMutableList()
        var budget = headMaxLen - optIds.sumOf { it.size }
        if (budget < 16) {
            val per = maxOf(4, (headMaxLen - 16) / maxOf(1, optIds.size))
            for (i in optIds.indices) optIds[i] = optIds[i].take(per)
            budget = headMaxLen - optIds.sumOf { it.size }
        }
        head = head.take(maxOf(8, budget))
        val ids = ArrayList<Int>()
        ids += tok.clsId; ids += head; ids += tok.sepId
        val markers = ArrayList<Int>()
        for (o in optIds) { markers += ids.size; ids += o }
        ids += tok.sepId
        val room = maxOf(0, maxLen - ids.size - 1)
        val st = state.ids.take(room)
        ids += st; ids += tok.sepId
        val clipped = ids.take(maxLen)
        val kept = markers.filter { it < maxLen }
        if (kept.size != opts.size) {
            throw DecisionRequestException(
                "question '$qid': only ${kept.size} of its ${opts.size} option markers fit in max_len=$maxLen " +
                    "with head_max_len=$headMaxLen; use fewer or shorter options",
            )
        }
        return EncodedRow(clipped.toIntArray(), kept.toIntArray(), qtypeOf(q)) to (state.ids.size - st.size)
    }

    companion object {
        const val MAX_OPTIONS = 255
        private const val OPTION_TOKENS = 48

        fun qtypeOf(q: DecisionQuestion): Int = when (q) {
            is DecisionQuestion.Choice -> QTYPE_CHOICE
            is DecisionQuestion.Score -> QTYPE_SCORE
            is DecisionQuestion.Noul -> QTYPE_NOUL
        }

        fun qtypeName(q: DecisionQuestion): String = when (q) {
            is DecisionQuestion.Choice -> "choice"
            is DecisionQuestion.Score -> "score"
            is DecisionQuestion.Noul -> "noul"
        }

        /** Option texts in label-index order (noul is always [false, true]). */
        fun renderOptions(q: DecisionQuestion): List<String> = when (q) {
            is DecisionQuestion.Choice ->
                q.options.map { (label, desc) -> if (desc.isNullOrEmpty()) label else "$label: $desc" }
            is DecisionQuestion.Score -> q.levels.mapIndexed { i, c -> "level $i: $c" }
            is DecisionQuestion.Noul -> listOf(
                "false: " + (q.falseDescription?.takeIf { it.isNotEmpty() } ?: "no, the statement does not hold"),
                "true: " + (q.trueDescription?.takeIf { it.isNotEmpty() } ?: "yes, the statement holds"),
            )
        }
    }
}
