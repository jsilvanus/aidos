package dev.aidos.ui

import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.RealRuntimeClient
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.inspector.buildInspectorState
import dev.aidos.ui.workspace.GraphSnapshot
import dev.aidos.ui.workspace.WorkspaceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// RFC-0124 (UI-21): the shared Node Inspector's state and its wiring to startNodeRun.
class NodeInspectorTest {

    private fun node(
        id: String,
        type: IntentNodeType = IntentNodeType.ATOMIC_TASK,
        actor: IntentActorKind = IntentActorKind.AGENT,
        parentId: String? = null,
    ) = IntentNodeRecord(
        id = id, projectId = "p", type = type, title = "Title $id", actorKind = actor, parentId = parentId,
        createdAt = "2026-11-18T00:00:00Z", createdByKind = "USER", createdById = "u",
        modifiedAt = "2026-11-18T00:00:00Z", modifiedByKind = "USER", modifiedById = "u",
    )

    private fun edge(from: String, to: String, kind: IntentEdgeKind) = IntentEdgeRecord(
        id = "$from-$kind-$to", projectId = "p", fromNodeId = from, toNodeId = to, edgeKind = kind,
        createdAt = "2026-11-18T00:00:00Z",
    )

    private fun status(n: IntentNodeRecord, derived: DerivedStatus, unmet: List<String> = emptyList()) = IntentNodeStatusView(
        node = n,
        derivedStatus = derived,
        effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
        unmetDependencyIds = unmet,
        activeRunIds = emptyList(),
        pendingPlatformChecks = false,
    )

    private fun snapshot(
        nodes: List<IntentNodeRecord>,
        edges: List<IntentEdgeRecord> = emptyList(),
        statuses: Map<String, DerivedStatus> = emptyMap(),
    ): GraphSnapshot {
        val byId = nodes.associateBy { it.id }
        return GraphSnapshot(
            projectId = "p",
            nodes = nodes,
            edges = edges,
            statuses = statuses.mapValues { (id, s) -> status(byId.getValue(id), s) },
            proposals = emptyList(),
            loadedAt = 1L,
        )
    }

    @Test
    fun `dependencies and dependents are found in both directions`() {
        val a = node("a")
        val b = node("b")
        val c = node("c")
        // a depends on b; c depends on a.
        val snap = snapshot(
            nodes = listOf(a, b, c),
            edges = listOf(edge("a", "b", IntentEdgeKind.DEPENDS_ON), edge("c", "a", IntentEdgeKind.DEPENDS_ON)),
            statuses = mapOf("a" to DerivedStatus.NOT_STARTED, "b" to DerivedStatus.IN_PROGRESS, "c" to DerivedStatus.NOT_STARTED),
        )
        val state = assertNotNull(buildInspectorState(snap, "a"))
        assertEquals(listOf("b"), state.unmetDependencies.map { it.id })
        assertEquals(listOf("c"), state.dependents.map { it.id })
    }

    @Test
    fun `criteria come from child criteria nodes and from ACCEPTS edges`() {
        val goal = node("g", type = IntentNodeType.GOAL)
        val childCriterion = node("c1", type = IntentNodeType.ACCEPTANCE_CRITERION, parentId = "g")
        val linkedCriterion = node("c2", type = IntentNodeType.ACCEPTANCE_CRITERION)
        val snap = snapshot(
            nodes = listOf(goal, childCriterion, linkedCriterion),
            edges = listOf(edge("g", "c2", IntentEdgeKind.ACCEPTS)),
        )
        val state = assertNotNull(buildInspectorState(snap, "g"))
        assertEquals(setOf("c1", "c2"), state.criteria.map { it.id }.toSet())
    }

    @Test
    fun `a human task cannot be started and a blocked agent task cannot either`() {
        val human = node("h", actor = IntentActorKind.HUMAN)
        val blocked = node("b")
        val prereq = node("p")
        val snap = snapshot(
            nodes = listOf(human, blocked, prereq),
            edges = listOf(edge("b", "p", IntentEdgeKind.DEPENDS_ON)),
            statuses = mapOf("h" to DerivedStatus.NOT_STARTED, "b" to DerivedStatus.BLOCKED, "p" to DerivedStatus.IN_PROGRESS),
        )
        assertFalse(assertNotNull(buildInspectorState(snap, "h")).canStart)
        assertFalse(assertNotNull(buildInspectorState(snap, "b")).canStart)
        assertNotNull(buildInspectorState(snap, "b")!!.startBlockedReason)
    }

    @Test
    fun `an agent task that is not blocked can start, a human task can complete`() {
        val agent = node("a")
        val human = node("h", actor = IntentActorKind.HUMAN)
        val snap = snapshot(
            nodes = listOf(agent, human),
            statuses = mapOf("a" to DerivedStatus.NOT_STARTED, "h" to DerivedStatus.NOT_STARTED),
        )
        assertTrue(assertNotNull(buildInspectorState(snap, "a")).canStart)
        assertFalse(assertNotNull(buildInspectorState(snap, "a")).canComplete)
        assertTrue(assertNotNull(buildInspectorState(snap, "h")).canComplete)
    }

    @Test
    fun `confirm is offered only for a node that needs review`() {
        val n = node("n")
        for (s in DerivedStatus.entries) {
            val snap = snapshot(listOf(n), statuses = mapOf("n" to s))
            assertEquals(s == DerivedStatus.NEEDS_REVIEW, assertNotNull(buildInspectorState(snap, "n")).canConfirm, "status $s")
        }
    }

    @Test
    fun `an unknown node gives no state`() {
        assertNull(buildInspectorState(snapshot(listOf(node("x"))), "missing"))
    }

    @Test
    fun `archived nodes are not treated specially by the builder`() {
        val archived = node("z").copy(lifecycle = IntentLifecycle.ARCHIVED)
        assertNotNull(buildInspectorState(snapshot(listOf(archived)), "z"))
    }

    @Test
    fun `startNodeRun on an agent task shows the runtime's answer, not the placeholder`() = runBlocking {
        val client = RealRuntimeClient()
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val controller = WorkspaceController(client, s, "p1", "inbox")
        val task = client.intents.createNode(CreateIntentNodeRequest(projectId = "p1", title = "Agent task"))
        controller.startNodeRun(task.id)
        val message = withTimeout(3_000) {
            var m = controller.state.value.message
            while (m == null) { delay(10); m = controller.state.value.message }
            m
        }
        assertFalse(message.contains("not available yet"), message)
        assertTrue(message.isNotBlank())
        s.cancel()
    }
}
