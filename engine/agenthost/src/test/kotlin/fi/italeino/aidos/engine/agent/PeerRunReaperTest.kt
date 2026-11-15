package fi.italeino.aidos.engine.agent

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallOutput
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/** A model that always asks for a client tool, so every run parks waiting for the origin. */
private class AlwaysToolAdapter : ModelAdapter {
    override val providerId = "test"
    override val modelId = "scripted"
    override val modelVersion = "1"
    override val contextWindow = 4096
    override val isLocal = true
    override fun supportsNativeToolCalls() = true
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> =
        Result.success(ModelResponse(listOf(ToolCallOutput(ToolCall("c", "lookup", buildJsonObject { }, null))), StopReason.TOOL_USE, null, null))
}

/** RFC-0108 Phase 5: a hosted run survives a dropped origin for a bounded window, then is cancelled. */
class PeerRunReaperTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun runtimeWithRun(owner: String): Pair<AgentRuntime, String> {
        val runtime = AgentRuntime(scope = scope, adapterFor = { AlwaysToolAdapter() })
        val r = runtime.start(owner, AgentTurnRequest(model = "scripted", message = "go", tools = listOf(ClientToolSpec("lookup")), async = true))
        return runtime to r.runId!!
    }

    @Test
    fun `a run keeps going inside the window, is cancelled after it, and a returning peer resets the clock`(): Unit = runBlocking {
        val (runtime, runId) = runtimeWithRun("peer:nodeA:app")
        val reaper = PeerRunReaper(runtime, window = 5.minutes)
        val t0 = 1_000_000L

        assertTrue(reaper.check(connectedPeers = setOf("nodeA"), nowMillis = t0).isEmpty())
        // Link drops: the clock starts, nothing is cancelled inside the window.
        assertTrue(reaper.check(emptySet(), t0).isEmpty())
        assertTrue(reaper.check(emptySet(), t0 + 4.minutes.inWholeMilliseconds).isEmpty())
        // The peer returns just in time: no clock, the run is still there to resume by seq.
        assertTrue(reaper.check(setOf("nodeA"), t0 + 4.minutes.inWholeMilliseconds).isEmpty())
        assertTrue(runtime.listAll().single().status != AgentTurnResponse.CANCELLED)

        // It drops again: a fresh window, so the earlier absence does not count.
        val t1 = t0 + 10.minutes.inWholeMilliseconds
        assertTrue(reaper.check(emptySet(), t1).isEmpty())
        assertTrue(reaper.check(emptySet(), t1 + 4.minutes.inWholeMilliseconds).isEmpty())
        assertEquals(listOf(runId), reaper.check(emptySet(), t1 + 5.minutes.inWholeMilliseconds))
        assertTrue(runtime.listAll().single().status == AgentTurnResponse.CANCELLED)
    }

    @Test
    fun `local apps' runs are never reaped, and a run for another peer is not touched`(): Unit = runBlocking {
        val runtime = AgentRuntime(scope = scope, adapterFor = { AlwaysToolAdapter() })
        val tools = listOf(ClientToolSpec("lookup"))
        runtime.start("local-app", AgentTurnRequest(model = "scripted", message = "go", tools = tools, async = true))
        val other = runtime.start("peer:nodeB:app", AgentTurnRequest(model = "scripted", message = "go", tools = tools, async = true))
        val reaper = PeerRunReaper(runtime, window = 1.minutes)
        reaper.check(emptySet(), 0L)
        val cancelled = reaper.check(setOf("nodeB"), 2.minutes.inWholeMilliseconds)
        assertTrue(cancelled.isEmpty(), "nodeA has no runs, nodeB is connected, local apps have no peer")
        assertTrue(runtime.listAll().none { it.status == AgentTurnResponse.CANCELLED })
        assertTrue(other.runId != null)
    }
}
