package fi.italeino.aidos.engine.agent

import dev.aidos.amp.AmpToolExecutionResult
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/** RFC-0115: the Engine runs adopted MCP operations for an agent loop, and asks the user before it changes anything. */
class EngineToolsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun cleanUp() { scope.cancel() }

    /** Calls [tool] once when it is offered, then repeats what the tool said. Answers plainly when no tool is offered. */
    private class Caller(private val tool: String) : ModelAdapter {
        override val providerId = "test"
        override val modelId = "scripted"
        override val modelVersion = "1"
        override val contextWindow = 4096
        override val isLocal = true
        override fun supportsNativeToolCalls() = true
        val offered = mutableListOf<String>()

        override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
            if (offered.isEmpty()) offered += request.tools.map { it.name }
            val last = request.messages.last()
            return Result.success(
                when {
                    last is Turn.ToolResult -> ModelResponse(listOf(TextOutput("tool said: " + last.result.content.joinToString { (it as ContentBlock.Text).text })), StopReason.END_TURN, null, null)
                    request.tools.any { it.name == tool } -> ModelResponse(listOf(ToolCallOutput(ToolCall("m1", tool, buildJsonObject { put("q", "x") }, null))), StopReason.TOOL_USE, null, null)
                    else -> ModelResponse(listOf(TextOutput("plain answer")), StopReason.END_TURN, null, null)
                },
            )
        }
    }

    private fun runtime(adapter: Caller, tools: List<EngineTool>) = AgentRuntime(scope, adapterFor = { adapter }, toolWait = 2000.milliseconds).also {
        it.engineTools = EngineToolSource { _ -> tools }
    }

    private fun tool(name: String, readOnly: Boolean, calls: AtomicInteger = AtomicInteger(), result: suspend (JsonObject) -> AmpToolExecutionResult = { AmpToolExecutionResult("ok:${it["q"]}", false) }) =
        EngineTool(name, "does $name", buildJsonObject { put("type", "object") }, readOnly) { calls.incrementAndGet(); result(it) }

    private fun turn(rt: AgentRuntime, engine: Boolean) = runBlocking {
        rt.start("app", AgentTurnRequest(model = "scripted", message = "go", useEngineTools = engine, maxSteps = 4))
    }

    @Test
    fun `engine tools are offered only when the request asks for them`() {
        val adapter = Caller("notes:read")
        val rt = runtime(adapter, listOf(tool("notes:read", readOnly = true)))
        assertEquals("plain answer", turn(rt, engine = false).reply)
        assertTrue(adapter.offered.isEmpty())

        val asking = Caller("notes:read")
        val reply = turn(runtime(asking, listOf(tool("notes:read", readOnly = true))), engine = true).reply
        assertEquals(listOf("notes:read"), asking.offered)
        assertEquals("tool said: ok:\"x\"", reply)
    }

    @Test
    fun `a read-only tool runs without asking, a tool that changes things waits for the user`() {
        val calls = AtomicInteger()
        val rt = runtime(Caller("notes:delete"), listOf(tool("notes:delete", readOnly = false, calls)))
        val answer = scope.launchTurn(rt)
        val approval = waitFor { rt.engineToolApprovals.pending.value.singleOrNull() }
        assertEquals("app", approval.app)
        assertEquals("notes:delete", approval.tool)
        assertEquals(0, calls.get(), "nothing ran before the user answered")
        assertTrue(rt.engineToolApprovals.decide(approval.id, true))
        assertEquals("tool said: ok:\"x\"", runBlocking { answer.await() }.reply)
        assertEquals(1, calls.get())
        assertTrue(rt.engineToolApprovals.pending.value.isEmpty())
    }

    @Test
    fun `a refused call never runs and the model is told`() {
        val calls = AtomicInteger()
        val rt = runtime(Caller("notes:delete"), listOf(tool("notes:delete", readOnly = false, calls)))
        val answer = scope.launchTurn(rt)
        val approval = waitFor { rt.engineToolApprovals.pending.value.singleOrNull() }
        rt.engineToolApprovals.decide(approval.id, false)
        val reply = runBlocking { answer.await() }.reply.orEmpty()
        assertTrue(reply.contains("did not approve"), reply)
        assertEquals(0, calls.get())
    }

    @Test
    fun `a failing tool is reported to the model and the run still completes`() {
        val rt = runtime(Caller("notes:read"), listOf(tool("notes:read", readOnly = true) { throw IllegalStateException("server went away") }))
        val result = turn(rt, engine = true)
        assertEquals(AgentTurnResponse.COMPLETED, result.status)
        assertTrue(result.reply.orEmpty().contains("server went away"), result.reply)
    }

    @Test
    fun `a credential in a tool result is replaced before the model sees it`() {
        // RFC-0035 "Secrets found in content", now in the Engine's loop (desktop and Android).
        val key = "sk-" + "a".repeat(10) + "B".repeat(10) + "c".repeat(10)
        val rt = runtime(Caller("notes:read"), listOf(tool("notes:read", readOnly = true) { AmpToolExecutionResult("config uses $key for calls", false) }))
        val result = turn(rt, engine = true)
        val reply = result.reply.orEmpty()
        assertFalse(reply.contains(key), reply)
        assertTrue(reply.contains("redaction"), reply)
        assertFalse(result.executedToolCalls.single().resultText.contains(key))
    }

    @Test
    fun `a value the host's vault knows is caught even without a pattern`() {
        val redactor = dev.aidos.vault.Redactor().apply { register("id1", "hf_token", "plainwordsvalue".toCharArray()) }
        val adapter = Caller("notes:read")
        val rt = AgentRuntime(scope, adapterFor = { adapter }, toolWait = 2000.milliseconds, toolResultScreen = SecretToolResultScreen(redactor)).also {
            it.engineTools = EngineToolSource { _ -> listOf(tool("notes:read", readOnly = true) { AmpToolExecutionResult("token is plainwordsvalue", false) }) }
        }
        val reply = turn(rt, engine = true).reply.orEmpty()
        assertFalse(reply.contains("plainwordsvalue"), reply)
    }

    @Test
    fun `a credentials file is withheld whole`() {
        val pem = "-----BEGIN RSA PRIVATE KEY-----\nMIIEowIBAAKCAQEA\n-----END RSA PRIVATE KEY-----"
        val rt = runtime(Caller("notes:read"), listOf(tool("notes:read", readOnly = true) { AmpToolExecutionResult(pem, false) }))
        val reply = turn(rt, engine = true).reply.orEmpty()
        assertFalse(reply.contains("MIIEowIBAAKCAQEA"), reply)
        assertTrue(reply.contains("withheld"), reply)
    }

    @Test
    fun `a client tool with the same name wins`() {
        val calls = AtomicInteger()
        val rt = runtime(Caller("lookup"), listOf(tool("lookup", readOnly = true, calls)))
        val first = runBlocking {
            rt.start("app", AgentTurnRequest(model = "scripted", message = "go", useEngineTools = true, async = true, maxSteps = 4,
                tools = listOf(ClientToolSpec("lookup", "the app's own", buildJsonObject { put("type", "object") }))))
        }
        val parked = waitFor { rt.listAll().firstOrNull { it.runId == first.runId!! && it.pendingToolCall != null } }
        assertEquals("lookup", parked.pendingToolCall!!.name, "the app was asked to run it")
        assertEquals(0, calls.get(), "the Engine's tool of the same name was not offered")
        rt.cancelAny(first.runId!!)
    }

    @Test
    fun `no answer within the time allowed is a refusal`() {
        val approvals = EngineToolApprovals(timeout = 50.milliseconds)
        assertFalse(runBlocking { approvals.request("app", "notes:delete") })
        assertTrue(approvals.pending.value.isEmpty())
        assertFalse(approvals.decide("gone", true))
    }

    private fun CoroutineScope.launchTurn(rt: AgentRuntime) = async { turn(rt, engine = true) }

    private fun <T : Any> waitFor(read: () -> T?): T {
        val end = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < end) { read()?.let { return it }; Thread.sleep(10) }
        throw AssertionError("timed out")
    }
}
