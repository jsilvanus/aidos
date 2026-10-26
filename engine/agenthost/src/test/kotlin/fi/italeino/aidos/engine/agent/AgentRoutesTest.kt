package fi.italeino.aidos.engine.agent

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.Turn
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.utils.io.readUTF8Line
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.routing.routing
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }

/** Model that calls `lookup` once, then answers with whatever the tool returned. */
private class ScriptedAdapter : ModelAdapter {
    override val providerId = "test"
    override val modelId = "scripted"
    override val modelVersion = "1"
    override val contextWindow = 4096
    override val isLocal = true
    override fun supportsNativeToolCalls() = true
    private var calls = 0

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        calls++
        val last = request.messages.last()
        return Result.success(
            if (last is Turn.ToolResult) {
                val text = last.result.content.joinToString { (it as dev.aidos.kernel.ContentBlock.Text).text }
                ModelResponse(listOf(TextOutput("tool said: $text")), StopReason.END_TURN, null, null)
            } else if (request.tools.isEmpty()) {
                ModelResponse(listOf(TextOutput("plain answer")), StopReason.END_TURN, null, null)
            } else {
                ModelResponse(
                    listOf(ToolCallOutput(ToolCall("m1", "lookup", buildJsonObject { put("q", "x") }, null))),
                    StopReason.TOOL_USE, null, null,
                )
            }
        )
    }
}

class AgentRoutesTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private fun app(runtime: AgentRuntime, block: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        application {
            install(ContentNegotiation) { json(json) }
            routing { agentRoutes(runtime, subjectOf = { it.request.headers["X-Test-App"] ?: "app-a" }) }
        }
        block()
    }

    private fun runtime(toolWait: kotlin.time.Duration = 5_000.milliseconds, max: Int = 8) =
        AgentRuntime(scope, { ScriptedAdapter() }, maxConcurrentRuns = max, toolWait = toolWait, stepWait = 10_000.milliseconds)

    private suspend fun ApplicationTestBuilder.postJson(path: String, body: String) =
        client.post(path) { contentType(ContentType.Application.Json); setBody(body) }

    @Test
    fun plainTurnCompletesInOneRequest() = app(runtime()) {
        val res = postJson("/v1/agent/turns", """{"model":"m","message":"hi"}""")
        assertEquals(HttpStatusCode.OK, res.status)
        val body = json.decodeFromString<AgentTurnResponse>(res.bodyAsText())
        assertEquals("completed", body.status)
        assertEquals("plain answer", body.reply)
    }

    @Test
    fun clientToolRoundTrip() = app(runtime()) {
        val tools = """[{"name":"lookup","description":"find","inputSchema":{"type":"object"}}]"""
        val first = json.decodeFromString<AgentTurnResponse>(
            postJson("/v1/agent/turns", """{"model":"m","message":"hi","tools":$tools}""").bodyAsText()
        )
        assertEquals("tool_calls", first.status)
        val call = first.toolCalls.single()
        assertEquals("lookup", call.name)
        val runId = assertNotNull(first.runId)

        val done = json.decodeFromString<AgentTurnResponse>(
            postJson(
                "/v1/agent/runs/$runId/tool-results",
                json.encodeToString(ToolResultsRequest(listOf(ClientToolResult(call.callId, "42")))),
            ).bodyAsText()
        )
        assertEquals("completed", done.status)
        assertEquals("tool said: 42", done.reply)
        assertEquals("lookup", done.executedToolCalls.single().toolName)
        // finished runs are forgotten
        assertEquals(HttpStatusCode.NotFound, postJson("/v1/agent/runs/$runId/tool-results", """{"results":[]}""").status)
    }

    @Test
    fun unansweredToolCallFailsTheCallNotTheRuntime() = app(runtime(toolWait = 100.milliseconds)) {
        val tools = """[{"name":"lookup"}]"""
        val first = json.decodeFromString<AgentTurnResponse>(
            postJson("/v1/agent/turns", """{"model":"m","message":"hi","tools":$tools}""").bodyAsText()
        )
        val done = json.decodeFromString<AgentTurnResponse>(
            postJson("/v1/agent/runs/${first.runId}/tool-results", """{"results":[]}""").bodyAsText()
        )
        assertEquals("completed", done.status)
        assertTrue(done.reply!!.contains("did not answer"), done.reply)
    }

    @Test
    fun rejectsBadRequests() = app(runtime()) {
        assertEquals(HttpStatusCode.BadRequest, postJson("/v1/agent/turns", """{"model":"m","message":" "}""").status)
        assertEquals(HttpStatusCode.BadRequest, postJson("/v1/agent/turns", """{"nonsense":1}""").status)
        assertEquals(
            HttpStatusCode.BadRequest,
            postJson("/v1/agent/turns", """{"model":"m","message":"x","tools":[{"name":"a"},{"name":"a"}]}""").status,
        )
        val first = json.decodeFromString<AgentTurnResponse>(
            postJson("/v1/agent/turns", """{"model":"m","message":"x","tools":[{"name":"lookup"}]}""").bodyAsText()
        )
        assertEquals(
            HttpStatusCode.BadRequest,
            postJson("/v1/agent/runs/${first.runId}/tool-results", """{"results":[{"callId":"nope","output":"x"}]}""").status,
        )
    }

    @Test
    fun capsConcurrentRunsAndCancelFreesASlot() = app(runtime(max = 1)) {
        val tools = """[{"name":"lookup"}]"""
        val first = json.decodeFromString<AgentTurnResponse>(
            postJson("/v1/agent/turns", """{"model":"m","message":"x","tools":$tools}""").bodyAsText()
        )
        assertEquals(HttpStatusCode.TooManyRequests, postJson("/v1/agent/turns", """{"model":"m","message":"y"}""").status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/agent/runs/${first.runId}").status)
        assertEquals(HttpStatusCode.OK, postJson("/v1/agent/turns", """{"model":"m","message":"y"}""").status)
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/agent/runs/${first.runId}").status)
    }

    @Test
    fun infoDescribesLimits() = app(runtime()) {
        val info = json.decodeFromString<AgentInfo>(client.get("/v1/agent/info").bodyAsText())
        assertEquals(1, info.apiVersion)
        assertEquals(8, info.maxConcurrentRuns)
    }

    private val lookupTools = """[{"name":"lookup"}]"""

    private suspend fun ApplicationTestBuilder.snapshot(id: String?, app: String = "app-a", query: String = "") =
        client.get("/v1/agent/runs/$id$query") { headers.append("X-Test-App", app) }

    @Test
    fun asyncRunIsATaskYouCanObserveAndFinish() = app(runtime()) {
        val started = json.decodeFromString<AgentTurnResponse>(
            postJson("/v1/agent/turns", """{"model":"m","message":"x","async":true,"tools":$lookupTools}""").bodyAsText()
        )
        assertEquals("running", started.status)
        val id = assertNotNull(started.runId)

        // long poll returns once the run needs the app
        val waiting = json.decodeFromString<AgentTurnResponse>(snapshot(id, query = "?since=${started.seq}&waitMs=5000").bodyAsText())
        assertEquals("tool_calls", waiting.status)
        assertTrue(waiting.seq > started.seq)

        val ack = json.decodeFromString<AgentTurnResponse>(
            postJson("/v1/agent/runs/$id/tool-results", json.encodeToString(ToolResultsRequest(listOf(ClientToolResult(waiting.toolCalls.single().callId, "7")), async = true))).bodyAsText()
        )
        assertEquals("running", ack.status)

        val done = json.decodeFromString<AgentTurnResponse>(snapshot(id, query = "?since=${ack.seq}&waitMs=5000").bodyAsText())
        assertEquals("completed", done.status)
        assertEquals("tool said: 7", done.reply)
        // async runs stay readable after they finish, and a repeat read is the same snapshot
        assertEquals(done, json.decodeFromString<AgentTurnResponse>(snapshot(id).bodyAsText()))
        assertEquals(HttpStatusCode.Conflict, postJson("/v1/agent/runs/$id/tool-results", """{"results":[]}""").status)
    }

    @Test
    fun runsBelongToTheAppThatStartedThem() = app(runtime()) {
        val started = json.decodeFromString<AgentTurnResponse>(
            postJson("/v1/agent/turns", """{"model":"m","message":"x","async":true,"tools":$lookupTools}""").bodyAsText()
        )
        val id = started.runId
        assertEquals(HttpStatusCode.NotFound, snapshot(id, app = "app-b").status)
        assertEquals(
            HttpStatusCode.NotFound,
            client.post("/v1/agent/runs/$id/tool-results") {
                headers.append("X-Test-App", "app-b"); contentType(ContentType.Application.Json); setBody("""{"results":[]}""")
            }.status,
        )
        assertEquals(HttpStatusCode.NotFound, client.delete("/v1/agent/runs/$id") { headers.append("X-Test-App", "app-b") }.status)
        assertEquals("[]", client.get("/v1/agent/runs") { headers.append("X-Test-App", "app-b") }.bodyAsText())
        assertEquals(1, json.decodeFromString<List<AgentTurnResponse>>(client.get("/v1/agent/runs").bodyAsText()).size)
        assertEquals(HttpStatusCode.OK, snapshot(id).status)
    }

    @Test
    fun cancelledAsyncRunStaysCancelled() = app(runtime()) {
        val started = json.decodeFromString<AgentTurnResponse>(
            postJson("/v1/agent/turns", """{"model":"m","message":"x","async":true,"tools":$lookupTools}""").bodyAsText()
        )
        val id = started.runId
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/agent/runs/$id").status)
        assertEquals("cancelled", json.decodeFromString<AgentTurnResponse>(snapshot(id).bodyAsText()).status)
        assertEquals(HttpStatusCode.NoContent, client.delete("/v1/agent/runs/$id").status)
    }

    @Test
    fun finishedAsyncRunsAreRetainedOnlyForTheRetentionWindow() {
        var clock = 0L
        val rt = AgentRuntime(scope, { ScriptedAdapter() }, retention = 1_000.milliseconds, now = { clock })
        app(rt) {
            val done = json.decodeFromString<AgentTurnResponse>(
                postJson("/v1/agent/turns", """{"model":"m","message":"x","async":true}""").bodyAsText()
            )
            val finished = json.decodeFromString<AgentTurnResponse>(snapshot(done.runId, query = "?since=0&waitMs=5000").bodyAsText())
            assertEquals("completed", finished.status)
            clock = 5_000
            postJson("/v1/agent/turns", """{"model":"m","message":"y"}""") // any start prunes
            assertEquals(HttpStatusCode.NotFound, snapshot(done.runId).status)
        }
    }

    @Test
    fun eventsStreamReportsStateChangesOfTheCallersRunsOnly() = app(runtime()) {
        val lines = mutableListOf<String>()
        client.prepareGet("/v1/agent/events") { headers.append("X-Test-App", "app-a") }.execute { stream ->
            val ch = stream.bodyAsChannel()
            assertEquals(": connected", ch.readUTF8Line())
            // another app's run must not show up
            client.post("/v1/agent/turns") {
                headers.append("X-Test-App", "app-b"); contentType(ContentType.Application.Json); setBody("""{"model":"m","message":"x"}""")
            }
            val started = json.decodeFromString<AgentTurnResponse>(
                postJson("/v1/agent/turns", """{"model":"m","message":"x","async":true}""").bodyAsText()
            )
            while (lines.none { it.contains("\"completed\"") }) {
                val line = ch.readUTF8Line() ?: break
                if (line.startsWith("data:")) lines += line
            }
            assertTrue(lines.all { it.contains(started.runId!!) }, lines.toString())
            assertEquals(listOf("running", "completed"), lines.map { Regex("\"status\":\"(\\w+)\"").find(it)!!.groupValues[1] })
        }
    }
}
