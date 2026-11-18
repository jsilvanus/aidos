package dev.aidos.ui

import dev.aidos.api.CapabilityCommands
import dev.aidos.api.EventFilter
import dev.aidos.api.EventSubscriptions
import dev.aidos.api.IntentCommands
import dev.aidos.api.MockRuntimeClient
import dev.aidos.api.ParkedEffectRequest
import dev.aidos.api.RealRuntimeClient
import dev.aidos.api.RuntimeClient
import dev.aidos.api.RuntimeEvent
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.frontdoors.gates.ApprovalGatePresenter
import dev.aidos.ui.workspace.LIVE_TEXT_MAX_CHARS
import dev.aidos.ui.workspace.ProjectGraphStore
import dev.aidos.ui.workspace.WorkspaceController
import dev.aidos.ui.workspace.applyLiveEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// RFC-0052 (UI-70): the UI reacts to live runtime events and keeps its polls as the fallback.
class LiveEventsTest {

    private val now = Clock.System.now()

    private fun toolDone(run: String, tool: String) = RuntimeEvent.ToolCallCompleted("e-$tool", now, "p1", "s1", run, "c-$tool", tool, "OK")

    private suspend fun <T> eventually(read: () -> T?): T = withTimeout(3_000) {
        var v = read()
        while (v == null) { delay(10); v = read() }
        v
    }

    private class CountingIntents(private val inner: IntentCommands) : IntentCommands by inner {
        val loads = AtomicInteger()
        override suspend fun listNodes(
            projectId: String, includeArchived: Boolean, actorKind: IntentActorKind?, parentId: String?, filterByParent: Boolean,
        ): List<IntentNodeRecord> {
            loads.incrementAndGet()
            return inner.listNodes(projectId, includeArchived, actorKind, parentId, filterByParent)
        }
    }

    private class CountingClient(private val inner: RealRuntimeClient) : RuntimeClient by inner {
        val counting = CountingIntents(inner.intents)
        override val intents: IntentCommands = counting
    }

    @Test
    fun `the store refreshes at once on a completed tool call and tracks live runs`() = runBlocking {
        val real = RealRuntimeClient()
        val client = CountingClient(real)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val store = ProjectGraphStore("p1", client, scope, pollMillis = 600_000, debounceMillis = 10)
        store.start()
        eventually { client.counting.loads.get().takeIf { it >= 1 } }
        delay(200) // let the subscription start
        val before = client.counting.loads.get()

        real.emit(toolDone("r1", "present_ui"))

        eventually { client.counting.loads.get().takeIf { it > before } }
        assertEquals(listOf("present_ui"), store.liveRuns.value["r1"]?.toolCalls)
        scope.cancel()
    }

    @Test
    fun `live state keeps the last 2000 characters and the last five tool calls and drops ended runs`() {
        var runs = emptyMap<String, dev.aidos.ui.workspace.LiveRun>()
        repeat(7) { runs = applyLiveEvent(runs, toolDone("r1", "t$it")) }
        assertEquals(listOf("t2", "t3", "t4", "t5", "t6"), runs.getValue("r1").toolCalls)

        val long = "a".repeat(3_000) + "END"
        runs = applyLiveEvent(runs, RuntimeEvent.AiResponseDelta("d", now, "p1", "s1", "r1", long, true))
        assertEquals(LIVE_TEXT_MAX_CHARS, runs.getValue("r1").text.length)
        assertTrue(runs.getValue("r1").text.endsWith("END"))

        runs = applyLiveEvent(runs, RuntimeEvent.RunCompleted("x", now, "p1", "s1", "r1", emptyList()))
        assertTrue(runs.isEmpty())
    }

    private class Runtime(
        private val caps: CapabilityCommands,
        override val events: EventSubscriptions,
    ) : RuntimeClient by MockRuntimeClient() {
        override val capabilities: CapabilityCommands = caps
    }

    @Test
    fun `Gates shows a parked tool call on the approval event without waiting for the poll`() = runBlocking {
        val effects = mutableListOf<ParkedEffectRequest>()
        val caps = object : CapabilityCommands by MockRuntimeClient().capabilities {
            override suspend fun listParkedEffects(): List<ParkedEffectRequest> = effects.toList()
        }
        val bus = MutableSharedFlow<RuntimeEvent>(extraBufferCapacity = 16)
        val runtime = Runtime(caps, object : EventSubscriptions {
            override fun subscribe(filter: EventFilter): Flow<RuntimeEvent> =
                bus.filter { filter.types.isEmpty() || it is RuntimeEvent.ToolApprovalRequired }
        })
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = WorkspaceController(runtime, scope, "p", "gates")
        val presenter = ApprovalGatePresenter(runtime, controller, scope, Dispatchers.Unconfined)
        presenter.start()
        delay(300) // first poll done, subscription running; the next poll is 3 s away

        effects += ParkedEffectRequest("run-1", "task-1", "p", "fs_write", "{}", "PROJECT")
        bus.emit(RuntimeEvent.ToolApprovalRequired("e1", now, "p", "s1", "run-1", "task-1", "fs_write", "preview"))

        eventually { presenter.state.value.toolApprovals.takeIf { it.isNotEmpty() } }
        scope.cancel()
    }
}
