package dev.aidos.ui

import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.CreateIntentProposalRequest
import dev.aidos.api.IntentCommands
import dev.aidos.api.UpdateIntentNodeRequest
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentOperation
import dev.aidos.intent.IntentProposal
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.intent.ProposalResolutionDecision
import dev.aidos.kernel.PlatformProfile
import dev.aidos.ui.paradigms.kanban.CartridgeDockIntent
import dev.aidos.ui.paradigms.kanban.CartridgeDockPresenter
import dev.aidos.ui.paradigms.pipeline.RelayPipelinePresenter
import dev.aidos.ui.paradigms.timeline.TimelineGanttPresenter
import dev.aidos.ui.spatial.radar.FocusRadarIntent
import dev.aidos.ui.spatial.radar.FocusRadarPresenter
import dev.aidos.ui.spatial.radar.RadarZone
import dev.aidos.ui.spatial.schematic.BreadboardSchematicPresenter
import dev.aidos.ui.spatial.schematic.TransitMetroPresenter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhaseSevenParadigmsAndTimelineTest {

    @Test
    fun testTransitMetroLinesAndTurnstiles() {
        val goal = node("g1", "Auth Goal", IntentNodeType.GOAL, null)
        val sub = node("s1", "OAuth Hub", IntentNodeType.SUB_GOAL, "g1")
        val task = node("t1", "Verify JWT", IntentNodeType.ATOMIC_TASK, "s1")
        val statuses = mapOf(
            "g1" to DerivedStatus.IN_PROGRESS,
            "s1" to DerivedStatus.IN_PROGRESS,
            "t1" to DerivedStatus.NEEDS_REVIEW,
        )

        val lines = TransitMetroPresenter.buildMetroLines(listOf(goal, sub, task), statuses)
        assertEquals(1, lines.size)
        assertEquals(3, lines.first().stations.size)
        assertTrue(lines.first().stations[1].isTransferHub)
        assertTrue(lines.first().stations[2].hasTurnstileGate)
    }

    @Test
    fun testBreadboardCircuitEnergizationAndDiodeGate() {
        val n1 = node("n1", "Build Schema", IntentNodeType.ATOMIC_TASK, null)
        val n2 = node("n2", "Run Migration", IntentNodeType.ATOMIC_TASK, null)
        val n3 = node("n3", "Verify UI", IntentNodeType.ATOMIC_TASK, null)
        val edges = listOf(
            edge("e1", "n1", "n2"),
            edge("e2", "n2", "n3"),
        )
        val statuses = mapOf(
            "n1" to DerivedStatus.DONE,
            "n2" to DerivedStatus.NEEDS_REVIEW,
            "n3" to DerivedStatus.BLOCKED,
        )

        val chips = BreadboardSchematicPresenter.buildCircuitChips(listOf(n1, n2, n3), edges, statuses)
        assertEquals(3, chips.size)
        assertTrue(chips[0].isEnergized)
        assertTrue(chips[1].isEnergized)
        assertTrue(chips[1].hasVerificationDiode)
        assertFalse(chips[2].isEnergized)
        assertTrue(chips[2].isBlocked)
    }

    @Test
    fun testTimelineGanttTopologicalSlots() {
        val n1 = node("n1", "Design", IntentNodeType.ATOMIC_TASK, null, estimatedSteps = 2)
        val n2 = node("n2", "Implement", IntentNodeType.ATOMIC_TASK, null, estimatedSteps = 3)
        val n3 = node("n3", "Test", IntentNodeType.ATOMIC_TASK, null, estimatedSteps = 1)
        val edges = listOf(
            edge("e1", "n1", "n2"),
            edge("e2", "n2", "n3"),
        )
        val statuses = mapOf(
            "n1" to DerivedStatus.DONE,
            "n2" to DerivedStatus.IN_PROGRESS,
            "n3" to DerivedStatus.BLOCKED,
        )

        val bars = TimelineGanttPresenter.buildTimelineBars(listOf(n3, n2, n1), edges, statuses)
        assertEquals(listOf("n1", "n2", "n3"), bars.map { it.node.id })
        assertEquals(0, bars[0].startSlot)
        assertEquals(2, bars[0].durationSlots)
        assertEquals(1, bars[1].startSlot)
        assertEquals(2, bars[2].startSlot)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testPipelineKanbanAndRadarPresentersLoadFromIntentCommands() = runTest {
        val n1 = node("n1", "Human Review", IntentNodeType.ATOMIC_TASK, null, actorKind = IntentActorKind.HUMAN)
        val n2 = node("n2", "Agent Code", IntentNodeType.ATOMIC_TASK, null, actorKind = IntentActorKind.AGENT)
        val fake = InMemoryIntentCommands(
            nodes = listOf(n1, n2),
            statuses = mapOf(
                "n1" to statusView(n1, DerivedStatus.NEEDS_REVIEW),
                "n2" to statusView(n2, DerivedStatus.IN_PROGRESS),
            ),
        )

        val pipeline = RelayPipelinePresenter("proj", fake, this, Dispatchers.Unconfined)
        val kanban = CartridgeDockPresenter("proj", fake, this, Dispatchers.Unconfined)
        val radar = FocusRadarPresenter("proj", fake, this, Dispatchers.Unconfined)
        advanceUntilIdle()

        assertEquals(1, pipeline.state.value.stages.size)
        assertTrue(pipeline.state.value.stages.first().hasBatonCross)

        assertEquals(5, kanban.state.value.columns.size)
        kanban.send(CartridgeDockIntent.MoveTask("n2", DerivedStatus.DONE))
        assertEquals(1, kanban.state.value.columns.first { it.status == DerivedStatus.DONE }.tasks.size)

        assertEquals(2, radar.state.value.radarNodes.size)
        assertTrue(radar.state.value.radarNodes.all { it.zone == RadarZone.STRIKE_ZONE })
        radar.send(FocusRadarIntent.SelectZone(RadarZone.STRIKE_ZONE))
        assertEquals(RadarZone.STRIKE_ZONE, radar.state.value.activeZone)
    }

    private fun node(
        id: String,
        title: String,
        type: IntentNodeType,
        parentId: String?,
        actorKind: IntentActorKind = IntentActorKind.AGENT,
        estimatedSteps: Int? = null,
    ) = IntentNodeRecord(
        id = id,
        projectId = "proj",
        type = type,
        title = title,
        parentId = parentId,
        granularity = IntentGranularity.ATOMIC,
        lifecycle = IntentLifecycle.ACTIVE,
        actorKind = actorKind,
        dispatchPolicy = NodeDispatchPolicy.MANUAL_START,
        targetProfile = IntentTargetProfile.ANY,
        minLocality = IntentMinLocality.LOCAL_ONLY,
        estimatedSteps = estimatedSteps,
        createdAt = "2026-11-15T00:00:00Z",
        createdByKind = "USER",
        createdById = "local",
        modifiedAt = "2026-11-15T00:00:00Z",
        modifiedByKind = "USER",
        modifiedById = "local",
    )

    private fun edge(id: String, from: String, to: String) = IntentEdgeRecord(
        id = id,
        projectId = "proj",
        fromNodeId = from,
        toNodeId = to,
        edgeKind = IntentEdgeKind.DEPENDS_ON,
        createdAt = "2026-11-15T00:00:00Z",
    )

    private fun statusView(node: IntentNodeRecord, status: DerivedStatus) = IntentNodeStatusView(
        node = node,
        derivedStatus = status,
        effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
        unmetDependencyIds = emptyList(),
        activeRunIds = emptyList(),
        pendingPlatformChecks = false,
    )

    private class InMemoryIntentCommands(
        private val nodes: List<IntentNodeRecord>,
        private val statuses: Map<String, IntentNodeStatusView>,
    ) : IntentCommands {
        override suspend fun createNode(request: CreateIntentNodeRequest) = error("unused")
        override suspend fun updateNode(request: UpdateIntentNodeRequest) = error("unused")
        override suspend fun archiveNode(projectId: String, nodeId: String) = error("unused")
        override suspend fun getNodeStatus(projectId: String, nodeId: String, projectDefaultPolicy: EffectiveDispatchPolicy) = statuses[nodeId]
        override suspend fun listNodes(projectId: String, includeArchived: Boolean, actorKind: IntentActorKind?, parentId: String?, filterByParent: Boolean) = nodes
        override suspend fun listActionableForHuman(projectId: String, projectDefaultPolicy: EffectiveDispatchPolicy) = emptyList<IntentNodeStatusView>()
        override suspend fun listReadyForAgent(projectId: String, profile: PlatformProfile, locality: IntentMinLocality, projectDefaultPolicy: EffectiveDispatchPolicy, onlyAutoDispatch: Boolean) = emptyList<IntentNodeStatusView>()
        override suspend fun completeHumanNode(projectId: String, nodeId: String, userId: String, note: String?) = error("unused")
        override suspend fun addEdge(projectId: String, fromNodeId: String, toNodeId: String, edgeKind: IntentEdgeKind) = error("unused")
        override suspend fun removeEdge(projectId: String, fromNodeId: String, toNodeId: String, edgeKind: IntentEdgeKind) = false
        override suspend fun listEdges(projectId: String, fromNodeId: String?, toNodeId: String?, edgeKind: IntentEdgeKind?) = emptyList<IntentEdgeRecord>()
        override suspend fun propose(request: CreateIntentProposalRequest) = error("unused")
        override suspend fun listProposals(projectId: String, state: String?) = emptyList<IntentProposal>()
        override suspend fun resolveProposal(projectId: String, proposalId: String, decision: ProposalResolutionDecision, resolvedByUserId: String, editedOperations: List<IntentOperation>?) = error("unused")
        override suspend fun confirmImplementation(projectId: String, nodeId: String, confirmedByKind: String, confirmedById: String, criterionIds: List<String>?) {}
        override suspend fun exportSnapshotJson(projectId: String) = ""
        override suspend fun importAndReconcileSnapshot(projectId: String, snapshotJson: String) = error("unused")
    }
}
