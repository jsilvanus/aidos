package fi.italeino.aidos.engine.http

import dev.aidos.decisions.DecisionAnswer
import dev.aidos.decisions.DecisionModel
import dev.aidos.decisions.DecisionModelAdapterImpl
import dev.aidos.decisions.DecisionRequest
import dev.aidos.decisions.DecisionResult
import dev.aidos.decisions.DecisionUsage
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRuntime
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RFC-0022 D45: the /v1/systemone surface, against a fake decision model (no ONNX needed). */
class EngineHttpServerSystemOneTest {
    private val requestJson = """
        {"model":"fake-decider","state":"poista edellinen lause","questions":{
          "intent":{"type":"choice","instructions":"dictation or command?","criteria":{"DICTATION":"text","COMMAND":"an instruction"}},
          "explicit":{"type":"noul","instructions":"explicit action?"}}}
    """.trimIndent()

    private fun withServer(block: suspend ApplicationTestBuilder.(token: String) -> Unit) = testApplication {
        val tokens = TokenManager()
        val server = EngineHttpServer(tokens, FakeDecisionRuntime())
        application { server.installInto(this) }
        block(tokens.generateNewToken().token)
    }

    @Test
    fun systemoneAnswersTypedQuestionsInRequestOrder() = withServer { token ->
        val r = client.post("/v1/systemone") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(requestJson) }
        assertEquals(HttpStatusCode.OK, r.status)
        val json = Json.parseToJsonElement(r.bodyAsText()).jsonObject
        assertEquals("fake-decider", json.getValue("model").jsonPrimitive.content)
        val answers = json.getValue("answers").jsonObject
        assertEquals(listOf("intent", "explicit"), answers.keys.toList())
        assertEquals("COMMAND", answers.getValue("intent").jsonObject.getValue("choice").jsonPrimitive.content)
        assertEquals("0.9", answers.getValue("explicit").jsonObject.getValue("noul").jsonPrimitive.content)
    }

    @Test
    fun decisionsIsAnAliasForTheSameHandler() = withServer { token ->
        val r = client.post("/v1/decisions") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(requestJson) }
        assertEquals(HttpStatusCode.OK, r.status)
    }

    @Test
    fun requiresBearerToken() = withServer {
        val r = client.post("/v1/systemone") { contentType(ContentType.Application.Json); setBody(requestJson) }
        assertEquals(HttpStatusCode.Unauthorized, r.status)
    }

    @Test
    fun malformedQuestionIsABadRequest() = withServer { token ->
        val bad = """{"model":"fake-decider","state":"x","questions":{"q":{"type":"choice","instructions":"i","criteria":{"ONLY":"one"}}}}"""
        val r = client.post("/v1/systemone") { bearerAuth(token); contentType(ContentType.Application.Json); setBody(bad) }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("at least two options"))
    }

    @Test
    fun aChatModelIsNotADecisionModel() = withServer { token ->
        val r = client.post("/v1/systemone") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(requestJson.replace("fake-decider", "plain-model"))
        }
        assertEquals(HttpStatusCode.BadRequest, r.status)
        assertTrue(r.bodyAsText().contains("not a decision model"))
    }

    @Test
    fun unknownModelIsNotFound() = withServer { token ->
        val r = client.post("/v1/systemone") {
            bearerAuth(token); contentType(ContentType.Application.Json)
            setBody(requestJson.replace("fake-decider", "missing"))
        }
        assertEquals(HttpStatusCode.NotFound, r.status)
    }

    @Test
    fun modelsListAdvertisesTheDecisionsCapability() = withServer { token ->
        val r = client.get("/v1/models") { bearerAuth(token) }
        assertTrue(r.bodyAsText().contains("decisions"))
    }
}

private class FakeDecider : DecisionModel {
    override val modelId = "fake-decider"
    override fun decide(request: DecisionRequest) = DecisionResult(
        model = modelId,
        answers = request.questions.mapValues { (_, q) ->
            when (q) {
                is dev.aidos.decisions.DecisionQuestion.Choice -> DecisionAnswer.Choice(
                    "COMMAND", mapOf("DICTATION" to 0.1, "COMMAND" to 0.9), 0.5, 0.9,
                )
                is dev.aidos.decisions.DecisionQuestion.Noul -> DecisionAnswer.Noul(0.9, 0.9, 0.9)
                is dev.aidos.decisions.DecisionQuestion.Score -> DecisionAnswer.Score(1.0, q.levels, listOf(0.5, 0.5), 0.0, 0.5)
            }
        },
        usage = DecisionUsage(10, 3, 0),
    )
    override fun close() = Unit
}

private class FakeDecisionRuntime : ModelRuntime {
    private val adapter = DecisionModelAdapterImpl(FakeDecider(), contextWindow = 1024)
    private val plain = object : ModelAdapter {
        override val providerId = "test"; override val modelId = "plain-model"; override val modelVersion = "1"
        override val contextWindow = 1; override val isLocal = true
        override fun supportsNativeToolCalls() = false
        override suspend fun invoke(request: dev.aidos.kernel.ModelRequest) = Result.failure<dev.aidos.kernel.ModelResponse>(UnsupportedOperationException())
    }

    override suspend fun catalog() = listOf(
        ModelDescriptor("fake-decider", "Fake", ModelKind.DECISION, "test", true, 1024, 1L, "d"),
        ModelDescriptor("plain-model", "Plain", ModelKind.LLM, "test", true, 1, 1L, "d"),
    )
    override suspend fun installed() = catalog()
    override suspend fun load(modelId: String): Result<ModelAdapter> = when (modelId) {
        "fake-decider" -> Result.success(adapter)
        "plain-model" -> Result.success(plain)
        else -> Result.failure(IllegalStateException("Model $modelId is not installed"))
    }
    override suspend fun delete(modelId: String) = Unit
    override suspend fun unload(modelId: String) = Unit
    override fun loaded(): List<String> = listOf("fake-decider")
}
