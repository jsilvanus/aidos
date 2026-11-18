package dev.aidos.ui.views

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.paradigms.pipeline.BatonKind
import dev.aidos.ui.paradigms.pipeline.RelayPipelinePresenter
import dev.aidos.ui.paradigms.pipeline.RiverLane
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

// RFC-0111 Design 1 (UI-38): the Relay River's presenter functions.
class RelayRiverViewTest {

    private fun node(
        id: String,
        actor: IntentActorKind = IntentActorKind.AGENT,
        type: IntentNodeType = IntentNodeType.ATOMIC_TASK,
        priority: Int = 100,
        modifiedAt: String = "2026-11-18T10:00:00Z",
    ) = IntentNodeRecord(
        id = id, projectId = "p", type = type, title = "Title $id", actorKind = actor, priority = priority,
        createdAt = "2026-11-18T09:00:00Z", createdByKind = "HUMAN", createdById = "u",
        modifiedAt = modifiedAt, modifiedByKind = "HUMAN", modifiedById = "u",
    )

    private fun depends(from: String, to: String) = IntentEdgeRecord(
        id = "$from-$to", projectId = "p", fromNodeId = from, toNodeId = to,
        edgeKind = IntentEdgeKind.DEPENDS_ON, createdAt = "2026-11-18T09:00:00Z",
    )

    private fun status(node: IntentNodeRecord, derived: DerivedStatus) = IntentNodeStatusView(
        node = node, derivedStatus = derived, effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
        unmetDependencyIds = emptyList(), activeRunIds = emptyList(), pendingPlatformChecks = false,
    )

    private fun snapshot(
        nodes: List<IntentNodeRecord>,
        edges: List<IntentEdgeRecord> = emptyList(),
        statuses: Map<String, DerivedStatus> = emptyMap(),
    ) = GraphSnapshot(
        projectId = "p",
        nodes = nodes,
        edges = edges,
        statuses = nodes.filter { statuses.containsKey(it.id) }.associate { n ->
            n.id to status(n, statuses.getValue(n.id))
        },
        proposals = emptyList(),
        loadedAt = 1L,
    )

    @Test
    fun hybridNodesAndCriteriaGoToTheHandoffChannel() {
        val hybrid = node("h", actor = IntentActorKind.HYBRID)
        val criterion = node("c", actor = IntentActorKind.HUMAN, type = IntentNodeType.ACCEPTANCE_CRITERION)
        val review = node("r", actor = IntentActorKind.AGENT)

        assertEquals(RiverLane.CHANNEL, RelayPipelinePresenter.laneOf(hybrid))
        assertEquals(RiverLane.CHANNEL, RelayPipelinePresenter.laneOf(criterion))
        assertEquals(RiverLane.CHANNEL, RelayPipelinePresenter.laneOf(review, DerivedStatus.NEEDS_REVIEW))
        assertEquals(RiverLane.HUMAN, RelayPipelinePresenter.laneOf(node("hu", actor = IntentActorKind.HUMAN)))
        assertEquals(RiverLane.AGENT, RelayPipelinePresenter.laneOf(node("ag")))
    }

    @Test
    fun depthLevelsOfAChainAreZeroOneTwo() {
        val a = node("a")
        val b = node("b")
        val c = node("c")
        // B needs A, C needs B.
        val depths = RelayPipelinePresenter.depthLevels(
            snapshot(listOf(c, b, a), edges = listOf(depends("b", "a"), depends("c", "b"))),
        )

        assertEquals(0, depths["a"])
        assertEquals(1, depths["b"])
        assertEquals(2, depths["c"])
    }

    @Test
    fun aCrossLaneEdgeIsABatonAndASameLaneEdgeIsNot() {
        val agent = node("agent", actor = IntentActorKind.AGENT)
        val human = node("human", actor = IntentActorKind.HUMAN)
        val agent2 = node("agent2", actor = IntentActorKind.AGENT)
        val snap = snapshot(
            listOf(agent, human, agent2),
            // human needs agent (cross-lane); agent2 needs agent (same lane).
            edges = listOf(depends("human", "agent"), depends("agent2", "agent")),
            statuses = mapOf("agent" to DerivedStatus.DONE),
        )

        val batons = RelayPipelinePresenter.batonEdges(snap)

        assertEquals(1, batons.size)
        val baton = batons.single()
        assertEquals("agent", baton.upstreamId)
        assertEquals("human", baton.downstreamId)
        assertEquals(RiverLane.AGENT, baton.upstreamLane)
        assertEquals(RiverLane.HUMAN, baton.downstreamLane)
        assertTrue(baton.inTheAir, "upstream DONE and downstream not: the baton is in the air")
    }

    @Test
    fun currentBatonPicksTheReadyHumanNodeHandedOverByAnAgent() {
        val agent = node("agent", actor = IntentActorKind.AGENT)
        val human = node("human", actor = IntentActorKind.HUMAN, priority = 10)
        val otherHuman = node("other", actor = IntentActorKind.HUMAN, priority = 50)
        val snap = snapshot(
            listOf(agent, human, otherHuman),
            edges = listOf(depends("human", "agent"), depends("other", "agent")),
            statuses = mapOf("agent" to DerivedStatus.DONE),
        )

        val baton = RelayPipelinePresenter.currentBaton(snap)

        assertNotNull(baton)
        assertEquals("human", baton.node.id)
        assertEquals(BatonKind.HANDED_OVER, baton.kind)
    }

    @Test
    fun currentBatonFallsBackToAgentWorkInFlightWhenNoHandover() {
        val agent = node("agent", actor = IntentActorKind.AGENT)
        val human = node("human", actor = IntentActorKind.HUMAN)
        // The human is ready, but its most recent prerequisite is a human, so nothing was handed over.
        val prereqHuman = node("prereq", actor = IntentActorKind.HUMAN, modifiedAt = "2026-11-18T11:00:00Z")
        val snap = snapshot(
            listOf(agent, human, prereqHuman),
            edges = listOf(depends("human", "prereq")),
            statuses = mapOf("agent" to DerivedStatus.IN_PROGRESS, "prereq" to DerivedStatus.DONE),
        )

        val baton = RelayPipelinePresenter.currentBaton(snap)

        assertNotNull(baton)
        assertEquals("agent", baton.node.id)
        assertEquals(BatonKind.AGENT_IN_FLIGHT, baton.kind)
    }

    @Test
    fun currentBatonIsNullWhenNothingIsWaitingOrInFlight() {
        val snap = snapshot(listOf(node("human", actor = IntentActorKind.HUMAN)))
        assertNull(RelayPipelinePresenter.currentBaton(snap))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun presenterExposesTheRiverFromTheStore() = runTest {
        val a = node("a")
        val b = node("b", actor = IntentActorKind.HUMAN)
        val store = MutableStateFlow(
            snapshot(listOf(b, a), edges = listOf(depends("b", "a")), statuses = mapOf("a" to DerivedStatus.DONE)),
        )
        val presenter = RelayPipelinePresenter(store, backgroundScope)
        runCurrent()

        val state = presenter.state.value
        assertEquals(listOf("a", "b"), state.items.map { it.node.id })
        assertEquals(1, state.batons.size)
        assertEquals("b", state.currentBaton?.node?.id)
    }
}
