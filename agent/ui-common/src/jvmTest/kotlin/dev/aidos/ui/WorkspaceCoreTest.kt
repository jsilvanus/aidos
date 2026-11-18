package dev.aidos.ui

import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.EventFilter
import dev.aidos.api.EventSubscriptions
import dev.aidos.api.IntentCommands
import dev.aidos.api.RealRuntimeClient
import dev.aidos.api.RuntimeClient
import dev.aidos.api.RuntimeEvent
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.workspace.ProjectGraphStore
import dev.aidos.ui.workspace.WorkspaceBootstrap
import dev.aidos.ui.workspace.WorkspaceController
import dev.aidos.ui.workspace.WorkspaceKind
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

// RFC-0124 (UI-10): WorkspaceBootstrap, ProjectGraphStore and WorkspaceController.
class WorkspaceCoreTest {

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private suspend fun <T> eventually(timeoutMillis: Long = 2_000, read: () -> T?): T = withTimeout(timeoutMillis) {
        var value = read()
        while (value == null) { delay(10); value = read() }
        value
    }

    /** The same client with event delivery switched off, so only the poll can refresh a store. */
    private class NoEvents(private val inner: RuntimeClient) : RuntimeClient by inner {
        override val events = object : EventSubscriptions {
            override fun subscribe(filter: EventFilter): Flow<RuntimeEvent> = emptyFlow()
        }
    }

    /** Fails `listNodes` the first [failures] times it is called. */
    private class FlakyIntents(private val inner: IntentCommands, failures: Int) : IntentCommands by inner {
        val left = AtomicInteger(failures)
        override suspend fun listNodes(
            projectId: String, includeArchived: Boolean, actorKind: IntentActorKind?, parentId: String?, filterByParent: Boolean,
        ): List<IntentNodeRecord> {
            if (left.getAndDecrement() > 0) throw IllegalStateException("boom")
            return inner.listNodes(projectId, includeArchived, actorKind, parentId, filterByParent)
        }
    }

    private class FlakyClient(private val inner: RuntimeClient, failures: Int) : RuntimeClient by inner {
        private val flaky = FlakyIntents(inner.intents, failures)
        override val intents: IntentCommands = flaky
        fun failNext(count: Int) { flaky.left.set(count) }
    }

    @Test
    fun `bootstrap creates Workspace once and resolve maps legacy ids onto it`() = runBlocking {
        val client = RealRuntimeClient()
        val first = WorkspaceBootstrap.ensureProject(client, WorkspaceKind.WORKSPACE)
        assertEquals(first, WorkspaceBootstrap.ensureProject(client, WorkspaceKind.WORKSPACE))
        assertEquals(1, client.projects.list().count { it.name == "Workspace" })
        assertEquals(first, WorkspaceBootstrap.resolve(client, "default-workspace", WorkspaceKind.WORKSPACE))
        assertEquals(first, WorkspaceBootstrap.resolve(client, null, WorkspaceKind.WORKSPACE))
        assertEquals(first, WorkspaceBootstrap.resolve(client, first, WorkspaceKind.LIFE))
    }

    @Test
    fun `resolve maps the legacy life id to the Life project`() = runBlocking {
        val client = RealRuntimeClient()
        val life = WorkspaceBootstrap.resolve(client, "life", WorkspaceKind.WORKSPACE)
        assertEquals("Life", client.projects.get(life)?.summary?.name)
    }

    @Test
    fun `store shows a new node through the event path`() = runBlocking {
        val client = RealRuntimeClient()
        val s = scope()
        val store = ProjectGraphStore("p1", client, s, pollMillis = 60_000, debounceMillis = 20)
        store.start()
        store.start() // idempotent
        delay(100)
        client.intents.createNode(CreateIntentNodeRequest(projectId = "p1", title = "Via event"))
        eventually { store.snapshot.value.nodes.firstOrNull { it.title == "Via event" } }
        s.cancel()
    }

    @Test
    fun `store shows a new node after one poll when events are off`() = runBlocking {
        val client = RealRuntimeClient()
        val s = scope()
        val store = ProjectGraphStore("p1", NoEvents(client), s, pollMillis = 50, debounceMillis = 20)
        store.start()
        client.intents.createNode(CreateIntentNodeRequest(projectId = "p1", title = "Via poll"))
        eventually { store.snapshot.value.nodes.firstOrNull { it.title == "Via poll" } }
        s.cancel()
    }

    @Test
    fun `store keeps old nodes and sets error when listNodes throws once`() = runBlocking {
        val real = RealRuntimeClient()
        real.intents.createNode(CreateIntentNodeRequest(projectId = "p1", title = "Kept"))
        val s = scope()
        // The first load succeeds because failures are counted from the second call on.
        val flaky = FlakyClient(real, failures = 0)
        val store = ProjectGraphStore("p1", flaky, s)
        store.refreshNow()
        assertEquals(1, store.snapshot.value.nodes.size)

        flaky.failNext(1)
        store.refreshNow()
        assertEquals("boom", store.snapshot.value.error)
        assertEquals(1, store.snapshot.value.nodes.size) // old data kept

        store.refreshNow()
        assertNull(store.snapshot.value.error) // recovered
        s.cancel()
    }

    @Test
    fun `controller navigates and completes a human node`() = runBlocking {
        val client = RealRuntimeClient()
        val s = scope()
        val controller = WorkspaceController(client, s, "p1", "inbox")
        controller.navigate("outliner", "n1")
        assertEquals("outliner", controller.state.value.viewId)
        assertEquals("n1", controller.state.value.selectedNodeId)

        val node = client.intents.createNode(
            CreateIntentNodeRequest(
                projectId = "p1", title = "Call the vicar", type = IntentNodeType.ATOMIC_TASK, actorKind = IntentActorKind.HUMAN,
            )
        )
        controller.completeHumanNode(node.id)
        eventually(3_000) {
            controller.storeFor("p1").snapshot.value.statuses[node.id]?.takeIf { it.derivedStatus == DerivedStatus.DONE }
        }
        s.cancel()
    }
}
