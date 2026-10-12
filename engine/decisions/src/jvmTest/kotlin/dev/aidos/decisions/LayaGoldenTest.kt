package dev.aidos.decisions

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Golden parity with the Python reference (laya 0.4.6, multilingual checkpoint), produced by
 * scripts/laya/golden.py. Tests that need the checkpoint read AIDOS_LAYA_DIR (a directory holding
 * tokenizer/tokenizer.json and rl_agent_config.json) and, for the full pass, AIDOS_LAYA_ONNX; they
 * are skipped when unset because weights and tokenizer are never stored in the repository.
 */
class LayaGoldenTest {
    private val golden: JsonObject = Json.parseToJsonElement(
        javaClass.getResource("/laya_golden.json")!!.readText(),
    ).jsonObject

    private fun tokenizer(): GemmaBpeTokenizer? {
        val dir = System.getenv("AIDOS_LAYA_DIR") ?: return null
        return GemmaBpeTokenizer.fromJson(File(dir, "tokenizer/tokenizer.json").readText())
    }

    private fun question(q: JsonObject): DecisionQuestion {
        val ins = q.getValue("instructions").jsonPrimitive.content
        val crit = q["criteria"]
        return when (q.getValue("type").jsonPrimitive.content) {
            "choice" -> DecisionQuestion.Choice(ins, (crit as JsonObject).map { (k, v) -> k to (v as? JsonPrimitive)?.content })
            "score" -> DecisionQuestion.Score(ins, (crit as JsonArray).map { it.jsonPrimitive.content })
            else -> DecisionQuestion.Noul(ins)
        }
    }

    @Test
    fun decoderReproducesRecordedAnswersFromRecordedLogits() {
        // Needs no model: the recorded raw logits must decode to Laya's published answer.
        for (e in golden.getValue("sequences").jsonArray) {
            val s = e.jsonObject
            val q = question(s.getValue("question").jsonObject)
            val logits = s.getValue("logits").jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
            val p = LayaDecoder.softmax(logits, 0, logits.size, 1.0)
            val got = LayaDecoder.decode(q, p, null)
            val want = s.getValue("answer").jsonObject
            when (got) {
                is DecisionAnswer.Choice -> {
                    assertEquals(want.getValue("choice").jsonPrimitive.content, got.choice)
                    for ((k, v) in want.getValue("probabilities").jsonObject) {
                        assertClose(v.jsonPrimitive.double, got.probabilities.getValue(k), "p[$k]")
                    }
                    assertClose(want.getValue("confidence").jsonPrimitive.double, got.confidence, "confidence")
                }
                is DecisionAnswer.Score -> {
                    assertClose(want.getValue("score").jsonPrimitive.double, got.score, "score")
                    assertClose(want.getValue("confidence").jsonPrimitive.double, got.confidence, "confidence")
                }
                is DecisionAnswer.Noul -> {
                    assertClose(want.getValue("noul").jsonPrimitive.double, got.probability, "noul")
                    assertClose(want.getValue("confidence").jsonPrimitive.double, got.confidence, "confidence")
                }
            }
            assertClose(want.getValue("answer_confidence").jsonPrimitive.double, got.answerConfidence, "answer_confidence")
        }
    }

    @Test
    fun tokenizerMatchesHuggingFaceIds() {
        val tok = tokenizer() ?: return println("skipped: AIDOS_LAYA_DIR not set")
        for (c in golden.getValue("tokenizer_cases").jsonArray) {
            val o = c.jsonObject
            val text = o.getValue("text").jsonPrimitive.content
            assertEquals(o.getValue("ids").jsonArray.map { it.jsonPrimitive.int }, tok.encode(text), "tokens for \"$text\"")
        }
        assertEquals(golden.getValue("mask_id").jsonPrimitive.int, tok.maskId)
        assertEquals(golden.getValue("cls_id").jsonPrimitive.int, tok.clsId)
        assertEquals(golden.getValue("sep_id").jsonPrimitive.int, tok.sepId)
    }

    @Test
    fun encoderMatchesReferenceSequences() {
        val tok = tokenizer() ?: return println("skipped: AIDOS_LAYA_DIR not set")
        val enc = LayaEncoder(tok, golden.getValue("max_len").jsonPrimitive.int, golden.getValue("head_max_len").jsonPrimitive.int)
        for (e in golden.getValue("sequences").jsonArray) {
            val s = e.jsonObject
            val state = enc.encodeState(s.getValue("state").jsonPrimitive.content)
            val (row, _) = enc.encode(s.getValue("qid").jsonPrimitive.content, question(s.getValue("question").jsonObject), state)
            val label = "${s.getValue("state").jsonPrimitive.content} / ${s.getValue("qid").jsonPrimitive.content}"
            assertEquals(s.getValue("ids").jsonArray.map { it.jsonPrimitive.int }, row.ids.toList(), "ids $label")
            assertEquals(s.getValue("markers").jsonArray.map { it.jsonPrimitive.int }, row.markers.toList(), "markers $label")
        }
    }

    @Test
    fun endToEndThroughOnnxRuntimeMatchesReference() {
        val tok = tokenizer() ?: return println("skipped: AIDOS_LAYA_DIR not set")
        val onnx = System.getenv("AIDOS_LAYA_ONNX")?.let(::File) ?: return println("skipped: AIDOS_LAYA_ONNX not set")
        OrtDecisionRunner(onnx).use { runner ->
            val model = LayaDecisionModel("laya-multilingual", tok, runner)
            val byState = golden.getValue("sequences").jsonArray.map { it.jsonObject }.groupBy { it.getValue("state").jsonPrimitive.content }
            for ((state, entries) in byState) {
                val qs = LinkedHashMap<String, DecisionQuestion>()
                for (s in entries) qs[s.getValue("qid").jsonPrimitive.content] = question(s.getValue("question").jsonObject)
                val result = model.decide(DecisionRequest(state, qs))
                for (s in entries) {
                    val qid = s.getValue("qid").jsonPrimitive.content
                    val want = s.getValue("answer").jsonObject
                    val got = result.answers.getValue(qid)
                    when (got) {
                        is DecisionAnswer.Choice -> assertEquals(want.getValue("choice").jsonPrimitive.content, got.choice, "$state/$qid")
                        is DecisionAnswer.Score -> assertClose(want.getValue("score").jsonPrimitive.double, got.score, "$state/$qid", 0.01)
                        is DecisionAnswer.Noul -> assertClose(want.getValue("noul").jsonPrimitive.double, got.probability, "$state/$qid", 0.01)
                    }
                    assertClose(want.getValue("answer_confidence").jsonPrimitive.double, got.answerConfidence, "$state/$qid conf", 0.01)
                }
            }
        }
    }

    private fun assertClose(want: Double, got: Double, what: String, tol: Double = 2e-4) =
        assertTrue(abs(want - got) <= tol, "$what: want $want got $got")
}
