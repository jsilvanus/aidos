package dev.aidos.decisions

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.round

/** Temperatures from the checkpoint's rl_agent_config.json; out-of-range values are clamped like Laya's. */
class LayaTemperatures(
    private val byQtype: DoubleArray = doubleArrayOf(1.0, 1.0, 1.0),
    private val byBucket: Map<String, Double> = emptyMap(),
) {
    internal fun forBucket(qtype: Int, k: Int): Double {
        val bucket = "${QTYPE_NAMES[qtype]}:${if (k <= 2) "2" else if (k <= 5) "3-5" else if (k <= 10) "6-10" else "11+"}"
        return clamp(byBucket[bucket] ?: byQtype[qtype])
    }

    private fun clamp(t: Double): Double = if (t.isNaN() || t.isInfinite()) 1.0 else min(TEMP_MAX, max(TEMP_MIN, t))

    companion object {
        private val QTYPE_NAMES = arrayOf("choice", "score", "noul")
        private const val TEMP_MIN = 0.5
        private const val TEMP_MAX = 5.0
    }
}

internal object LayaDecoder {
    fun softmax(logits: FloatArray, from: Int, k: Int, temperature: Double): DoubleArray {
        var mx = Double.NEGATIVE_INFINITY
        for (i in 0 until k) mx = max(mx, logits[from + i] / temperature)
        val e = DoubleArray(k) { exp(logits[from + it] / temperature - mx) }
        val sum = e.sum()
        return DoubleArray(k) { e[it] / sum }
    }

    fun entropyConfidence(p: DoubleArray): Double {
        if (p.size < 2) return 1.0
        var ent = 0.0
        for (v in p) ent -= v * ln(v.coerceIn(1e-12, 1.0))
        return (1.0 - ent / ln(p.size.toDouble())).coerceIn(0.0, 1.0)
    }

    fun r4(x: Double): Double = round(x * 10_000.0) / 10_000.0

    fun decode(q: DecisionQuestion, p: DoubleArray, minConfidence: Double?): DecisionAnswer {
        val answerConf = r4(p.max().coerceIn(0.0, 1.0))
        val abstained = minConfidence != null && answerConf < minConfidence
        return when (q) {
            is DecisionQuestion.Choice -> DecisionAnswer.Choice(
                choice = q.options[p.indices.maxByOrNull { p[it] }!!].first,
                probabilities = LinkedHashMap<String, Double>().also { m ->
                    q.options.forEachIndexed { i, o -> m[o.first] = r4(p[i]) }
                },
                confidence = r4(entropyConfidence(p)),
                answerConfidence = answerConf,
                abstained = abstained,
            )
            is DecisionQuestion.Score -> DecisionAnswer.Score(
                score = r4(p.indices.sumOf { it * p[it] }),
                levels = q.levels,
                probabilities = p.map { r4(it) },
                confidence = r4(entropyConfidence(p)),
                answerConfidence = answerConf,
                abstained = abstained,
            )
            is DecisionQuestion.Noul -> DecisionAnswer.Noul(
                probability = r4(p[1]),
                // Laya reports max(p_true, 1 - p_true) as the noul "confidence".
                confidence = r4(max(p[1], 1.0 - p[1])),
                answerConfidence = answerConf,
                abstained = abstained,
            )
        }
    }
}
