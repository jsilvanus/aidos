package dev.aidos.api

import dev.aidos.api.socket.Wire
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentNodeType
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

// RFC-0124 (UI-02): intent graph mutations are announced as RuntimeEvent.IntentGraphChanged.
class IntentGraphChangedEventTest {

    private val projectId = "p1"
    private val filter = EventFilter(types = listOf(RuntimeEventType.INTENT_GRAPH_CHANGED))

    @Test
    fun `creating a node emits NODE_CREATED with that node id`() = runTest {
        val client = RealRuntimeClient()
        val changes = client.events.subscribe(filter)

        val node = client.intents.createNode(
            CreateIntentNodeRequest(projectId = projectId, title = "Write the view", type = IntentNodeType.GOAL)
        )

        val event = assertIs<RuntimeEvent.IntentGraphChanged>(
            withTimeout(5_000) { changes.take(1).toList() }.single()
        )
        assertEquals(listOf(node.id), event.nodeIds)
        assertEquals("NODE_CREATED", event.change)
        assertEquals(projectId, event.projectId)
    }

    @Test
    fun `adding an edge emits EDGE_ADDED with both node ids`() = runTest {
        val client = RealRuntimeClient()
        val from = client.intents.createNode(CreateIntentNodeRequest(projectId = projectId, title = "From"))
        val to = client.intents.createNode(CreateIntentNodeRequest(projectId = projectId, title = "To"))
        val changes = client.events.subscribe(filter)

        client.intents.addEdge(projectId, from.id, to.id, IntentEdgeKind.DEPENDS_ON)

        // Two NODE_CREATED events were emitted before the edge; take all three.
        val events = withTimeout(5_000) { changes.take(3).toList() }
        val edge = assertIs<RuntimeEvent.IntentGraphChanged>(events.last())
        assertEquals("EDGE_ADDED", edge.change)
        assertEquals(listOf(from.id, to.id), edge.nodeIds)
    }

    @Test
    fun `wire round trip keeps an IntentGraphChanged event without a session`() {
        val original = RuntimeEvent.IntentGraphChanged(
            eventId = "e9",
            timestamp = Clock.System.now(),
            projectId = projectId,
            nodeIds = listOf("n1", "n2"),
            change = "EDGE_ADDED",
        )
        val decoded = Wire.decodeRuntimeEvent(Wire.encodeRuntimeEvent(original))
        assertEquals(original, decoded)
    }
}
