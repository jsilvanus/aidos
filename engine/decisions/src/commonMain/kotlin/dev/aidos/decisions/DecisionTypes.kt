package dev.aidos.decisions

/**
 * Typed decisions ("System One"): one parallel model pass answers several typed questions about a
 * piece of text, with probabilities instead of generated text. Amends RFC-0022 (see
 * docs/rfcs/0022-inference-backend-architecture.md, "Decision capability").
 */
sealed interface DecisionQuestion {
    val instructions: String

    /** Pick one of [options]; [options] keep request order, a null description renders the label alone. */
    data class Choice(override val instructions: String, val options: List<Pair<String, String?>>) : DecisionQuestion

    /** Place the input on ordered [levels] (index 0 lowest); answered as an expected level index. */
    data class Score(override val instructions: String, val levels: List<String>) : DecisionQuestion

    /** Yes/no. [falseDescription]/[trueDescription] replace the default option texts when set. */
    data class Noul(
        override val instructions: String,
        val falseDescription: String? = null,
        val trueDescription: String? = null,
    ) : DecisionQuestion
}

data class DecisionRequest(
    val state: String,
    /** Question id -> question, in request order (answers come back keyed by the same ids). */
    val questions: Map<String, DecisionQuestion>,
    /** When set, answers whose [DecisionAnswer.answerConfidence] is lower are flagged [DecisionAnswer.abstained]. */
    val minConfidence: Double? = null,
)

sealed interface DecisionAnswer {
    /** Confidence of the reported answer: max probability after temperature scaling. */
    val answerConfidence: Double

    /** Normalised-entropy concentration of the distribution (1 = certain). Not calibrated. */
    val confidence: Double
    val abstained: Boolean

    data class Choice(
        val choice: String,
        val probabilities: Map<String, Double>,
        override val confidence: Double,
        override val answerConfidence: Double,
        override val abstained: Boolean = false,
    ) : DecisionAnswer

    data class Score(
        /** Expected level index: sum(i * p[i]). */
        val score: Double,
        val levels: List<String>,
        val probabilities: List<Double>,
        override val confidence: Double,
        override val answerConfidence: Double,
        override val abstained: Boolean = false,
    ) : DecisionAnswer

    data class Noul(
        /** Probability that the statement holds. */
        val probability: Double,
        override val confidence: Double,
        override val answerConfidence: Double,
        override val abstained: Boolean = false,
    ) : DecisionAnswer
}

data class DecisionUsage(
    val inputTokens: Int,
    val stateTokens: Int,
    val stateTokensDropped: Int,
) {
    val truncated: Boolean get() = stateTokensDropped > 0
}

data class DecisionResult(
    val model: String,
    val answers: Map<String, DecisionAnswer>,
    val usage: DecisionUsage,
)

/** A request the model cannot encode (too many options for the token budget, empty ids, ...). */
class DecisionRequestException(message: String) : IllegalArgumentException(message)

/** Anything that can answer a [DecisionRequest]; the Engine routes `/v1/systemone` here. */
interface DecisionModel : AutoCloseable {
    val modelId: String
    fun decide(request: DecisionRequest): DecisionResult
}
