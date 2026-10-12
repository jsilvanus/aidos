package dev.aidos.decisions

/** One padded batch for the Laya graph: five inputs, one row per question. */
class TensorBatch(
    val inputIds: Array<LongArray>,
    val attentionMask: Array<LongArray>,
    val markerPos: Array<LongArray>,
    val markerMask: Array<BooleanArray>,
    val qtype: LongArray,
)

class TensorResult(
    /** [rows][markerCols] option logits (padded slots hold -1e4). */
    val logits: Array<FloatArray>,
    val actLogits: Array<FloatArray>,
)

/** Runs the exported Laya graph (RFC-0022 ONNX backend). Implementations own the session lifecycle. */
interface DecisionTensorRunner : AutoCloseable {
    fun run(batch: TensorBatch): TensorResult
}

class LayaConfig(
    val maxLen: Int = 1024,
    val headMaxLen: Int = 256,
    val temperatures: LayaTemperatures = LayaTemperatures(),
)

/**
 * The Laya adapter: encodes typed questions, runs them as one batch through [runner], decodes the
 * option logits. Knows nothing about ONNX; the runner is the only backend-facing seam, so the
 * runtime stays unaware of Laya (LAYA.md, "ONNX backend must remain completely unaware").
 */
class LayaDecisionModel(
    override val modelId: String,
    tokenizer: GemmaBpeTokenizer,
    private val runner: DecisionTensorRunner,
    private val config: LayaConfig = LayaConfig(),
) : DecisionModel {
    private val padId = tokenizer.padId.toLong()
    private val encoder = LayaEncoder(tokenizer, config.maxLen, config.headMaxLen)

    override fun decide(request: DecisionRequest): DecisionResult {
        if (request.questions.isEmpty()) throw DecisionRequestException("at least one question is required")
        val state = encoder.encodeState(request.state)
        val ids = request.questions.keys.toList()
        var dropped = 0
        val rows = ids.map { id ->
            val (row, d) = encoder.encode(id, request.questions.getValue(id), state)
            dropped = maxOf(dropped, d)
            row
        }
        val result = runner.run(collate(rows))
        val answers = LinkedHashMap<String, DecisionAnswer>()
        ids.forEachIndexed { r, id ->
            val q = request.questions.getValue(id)
            val k = rows[r].markers.size
            val t = config.temperatures.forBucket(rows[r].qtype, k)
            val p = LayaDecoder.softmax(result.logits[r], 0, k, t)
            answers[id] = LayaDecoder.decode(q, p, request.minConfidence)
        }
        return DecisionResult(
            model = modelId,
            answers = answers,
            usage = DecisionUsage(rows.sumOf { it.ids.size }, state.ids.size, dropped),
        )
    }

    private fun collate(rows: List<EncodedRow>): TensorBatch {
        val len = rows.maxOf { it.ids.size }
        // The exported head takes top-2 of the option probabilities (traced), so at least two marker
        // columns are required; the extra slot is masked and scores -1e4 (scripts/laya/export_onnx.py).
        val cols = maxOf(2, rows.maxOf { it.markers.size })
        return TensorBatch(
            inputIds = Array(rows.size) { r -> LongArray(len) { c -> rows[r].ids.getOrNull(c)?.toLong() ?: padId } },
            attentionMask = Array(rows.size) { r -> LongArray(len) { c -> if (c < rows[r].ids.size) 1L else 0L } },
            markerPos = Array(rows.size) { r -> LongArray(cols) { c -> rows[r].markers.getOrNull(c)?.toLong() ?: 0L } },
            markerMask = Array(rows.size) { r -> BooleanArray(cols) { c -> c < rows[r].markers.size } },
            qtype = LongArray(rows.size) { rows[it].qtype.toLong() },
        )
    }

    override fun close() = runner.close()
}
