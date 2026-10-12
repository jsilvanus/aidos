package dev.aidos.decisions

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * The `/v1/systemone` JSON shape: request `{model, state, questions, min_confidence?}`, response
 * `{model, answers, usage}` -- the de facto Jev / OpenJev / laya-serve shape, so existing System One
 * clients work unchanged. Unknown request fields are ignored (API v1 additive rule, sdk/CONTRACT.md).
 *
 * Question shapes: `{"type":"choice","instructions":..,"criteria":{label: description|null,..} | [label,..]}`,
 * `{"type":"score","instructions":..,"criteria":[level,..]}`, `{"type":"noul","instructions":..,
 * "criteria":{"false":..,"true":..}?}`.
 */
object DecisionWire {
    class Parsed(val model: String, val request: DecisionRequest)

    fun parseRequest(body: JsonObject): Parsed {
        val model = body.str("model") ?: throw DecisionRequestException("model is required")
        val state = body["state"]
        val stateText = when (state) {
            is JsonPrimitive -> if (state.isString) state.content else throw DecisionRequestException("state must be a string")
            null, JsonNull -> throw DecisionRequestException("state is required")
            else -> state.toString() // structured state is serialised as JSON text, as Laya does
        }
        val questions = body["questions"] as? JsonObject ?: throw DecisionRequestException("questions must be an object")
        if (questions.isEmpty()) throw DecisionRequestException("at least one question is required")
        val parsed = LinkedHashMap<String, DecisionQuestion>()
        for ((id, q) in questions) {
            if (id.isBlank()) throw DecisionRequestException("question ids must not be blank")
            parsed[id] = parseQuestion(id, q as? JsonObject ?: throw DecisionRequestException("question '$id' must be an object"))
        }
        val min = (body["min_confidence"] as? JsonPrimitive)?.doubleOrNull
        if (min != null && (min < 0.0 || min > 1.0)) throw DecisionRequestException("min_confidence must be within [0, 1]")
        return Parsed(model, DecisionRequest(stateText, parsed, min))
    }

    private fun parseQuestion(id: String, q: JsonObject): DecisionQuestion {
        val ins = q.str("instructions") ?: throw DecisionRequestException("question '$id': instructions is required")
        val crit = q["criteria"]
        return when (val type = q.str("type")) {
            "choice" -> {
                val options = when (crit) {
                    is JsonObject -> crit.map { (k, v) -> k to (v as? JsonPrimitive)?.contentOrNull?.takeIf { v !is JsonNull } }
                    is JsonArray -> crit.map { (it as? JsonPrimitive)?.content.orEmpty() to null }
                    else -> throw DecisionRequestException("question '$id': choice needs criteria (object or array)")
                }
                if (options.size < 2) throw DecisionRequestException("question '$id': choice needs at least two options")
                if (options.map { it.first }.toSet().size != options.size) throw DecisionRequestException("question '$id': duplicate option labels")
                DecisionQuestion.Choice(ins, options)
            }
            "score" -> {
                val levels = (crit as? JsonArray)?.map { (it as? JsonPrimitive)?.content ?: it.toString() }
                    ?: throw DecisionRequestException("question '$id': score needs criteria (ordered array)")
                if (levels.size < 2) throw DecisionRequestException("question '$id': score needs at least two levels")
                DecisionQuestion.Score(ins, levels)
            }
            "noul" -> {
                val o = crit as? JsonObject
                DecisionQuestion.Noul(ins, o?.str("false"), o?.str("true"))
            }
            else -> throw DecisionRequestException("question '$id': unknown type '$type' (choice, score, noul)")
        }
    }

    fun renderResult(result: DecisionResult): JsonObject = buildJsonObject {
        put("model", result.model)
        putJsonObject("answers") {
            for ((id, a) in result.answers) {
                put(id, buildJsonObject {
                    when (a) {
                        is DecisionAnswer.Choice -> {
                            put("type", "choice"); put("choice", a.choice)
                            putJsonObject("probabilities") { a.probabilities.forEach { (k, v) -> put(k, v) } }
                        }
                        is DecisionAnswer.Score -> {
                            put("type", "score"); put("score", a.score)
                            putJsonObject("legend") { a.levels.forEachIndexed { i, l -> put(i.toString(), l) } }
                            putJsonObject("probabilities") { a.probabilities.forEachIndexed { i, v -> put(i.toString(), v) } }
                        }
                        is DecisionAnswer.Noul -> { put("type", "noul"); put("noul", a.probability) }
                    }
                    put("confidence", a.confidence)
                    put("answer_confidence", a.answerConfidence)
                    // Present only when min_confidence was requested and not met, never null-padded.
                    if (a.abstained) put("abstained", true)
                })
            }
        }
        putJsonObject("usage") {
            put("input_tokens", result.usage.inputTokens)
            put("output_tokens", 0)
            put("state_tokens", result.usage.stateTokens)
            put("state_tokens_dropped", result.usage.stateTokensDropped)
            put("truncated", result.usage.truncated)
        }
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
