package dev.aidos.ui

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
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.ui.frontdoors.inbox.ActionInboxPresenter
import dev.aidos.ui.paradigms.kanban.CartridgeDockPresenter
import dev.aidos.ui.paradigms.outliner.CausalOutlinerIntent
import dev.aidos.ui.paradigms.outliner.CausalOutlinerPresenter
import dev.aidos.ui.paradigms.pipeline.RelayPipelinePresenter
import dev.aidos.ui.paradigms.timeline.TimelineGanttPresenter
import dev.aidos.ui.spatial.canvas.SpatialCanvasPresenter
import dev.aidos.ui.spatial.radar.FocusRadarPresenter
import dev.aidos.ui.spatial.radar.RadarZone
import dev.aidos.ui.spatial.schematic.BreadboardSchematicPresenter
import dev.aidos.ui.spatial.schematic.TransitMetroPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC-0124 amendment (UI-20): every graph presenter reads a [GraphSnapshot] and rebuilds itself when the
 * snapshot changes, with no reload call. Each test pushes a second snapshot in which the human task "h" is
 * DONE and checks that the presenter shows it.
 */
class PresentersOnStoreTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    // g (GOAL) — h (HUMAN task) → a (AGENT task that depends on h)
    private val goal = node("g", IntentNodeType.GOAL, IntentActorKind.AGENT)
    private val human = node("h", IntentNodeType.ATOMIC_TASK, IntentActorKind.HUMAN)
    private val agent = node("a", IntentNodeType.ATOMIC_TASK, IntentActorKind.AGENT)
    private val nodes = listOf(goal, human, agent)
    private val edges = listOf(
        IntentEdgeRecord(
            id = "e1", projectId = "p", fromNodeId = "h", toNodeId = "a",
            edgeKind = IntentEdgeKind.DEPENDS_ON, createdAt = "2026-11-15T00:00:00Z",
        ),
    )

    private fun snapshot(humanStatus: DerivedStatus, agentStatus: DerivedStatus = DerivedStatus.NOT_STARTED) =
        testSnapshot(
            projectId = "p",
            nodes = nodes,
            edges = edges,
            statuses = mapOf(
                "g" to view(goal, DerivedStatus.NOT_STARTED),
                "h" to view(human, humanStatus),
                "a" to view(agent, agentStatus),
            ),
        )

    @Test
    fun outlinerRebuildsWhenTheStoreChanges() {
        val store = MutableStateFlow(snapshot(DerivedStatus.NOT_STARTED))
        val presenter = CausalOutlinerPresenter(store, scope)
        assertFalse(presenter.state.value.isLoading)
        assertEquals(setOf("g", "h", "a"), presenter.state.value.visibleRows.map { it.node.id }.toSet())

        store.value = snapshot(DerivedStatus.DONE)
        assertEquals(DerivedStatus.DONE, presenter.state.value.visibleRows.first { it.node.id == "h" }.derivedStatus)
    }

    @Test
    fun outlinerKeepsItsOwnStateAcrossSnapshots() {
        val store = MutableStateFlow(snapshot(DerivedStatus.NOT_STARTED))
        val presenter = CausalOutlinerPresenter(store, scope)
        presenter.send(CausalOutlinerIntent.SelectNode("h"))
        store.value = snapshot(DerivedStatus.DONE)
        assertEquals("h", presenter.state.value.focusedNodeId)
    }

    @Test
    fun outlinerShowsLoadingUntilTheFirstLoadAndShowsErrors() {
        val loading = CausalOutlinerPresenter(MutableStateFlow(testSnapshot("p", nodes, loadedAt = 0L)), scope)
        assertTrue(loading.state.value.isLoading)

        val failed = CausalOutlinerPresenter(
            MutableStateFlow(GraphSnapshot.empty("p").copy(error = "offline")),
            scope,
        )
        assertFalse(failed.state.value.isLoading)
        assertEquals("offline", failed.state.value.error)
    }

    @Test
    fun relayPipelineRebuildsWhenTheStoreChanges() {
        val store = MutableStateFlow(snapshot(DerivedStatus.NOT_STARTED))
        val presenter = RelayPipelinePresenter(store, scope)
        assertEquals(1, presenter.state.value.stages.size)
        assertEquals(DerivedStatus.NOT_STARTED, presenter.state.value.stages.single().statuses["h"]?.derivedStatus)

        store.value = snapshot(DerivedStatus.DONE)
        assertEquals(DerivedStatus.DONE, presenter.state.value.stages.single().statuses["h"]?.derivedStatus)
    }

    @Test
    fun cartridgeDockRebuildsWhenTheStoreChanges() {
        val store = MutableStateFlow(snapshot(DerivedStatus.NOT_STARTED))
        val presenter = CartridgeDockPresenter(store, scope)
        assertEquals(5, presenter.state.value.columns.size)
        assertTrue(presenter.state.value.columns.first { it.status == DerivedStatus.NOT_STARTED }.tasks.any { it.first.id == "h" })

        store.value = snapshot(DerivedStatus.DONE)
        assertTrue(presenter.state.value.columns.first { it.status == DerivedStatus.DONE }.tasks.any { it.first.id == "h" })
    }

    @Test
    fun timelineRebuildsWhenTheStoreChanges() {
        val store = MutableStateFlow(snapshot(DerivedStatus.NOT_STARTED))
        val presenter = TimelineGanttPresenter(store, scope)
        val bar = { id: String -> presenter.state.value.bars.first { it.node.id == id } }
        assertEquals(1, bar("a").startSlot)

        store.value = snapshot(DerivedStatus.DONE)
        assertEquals(DerivedStatus.DONE, bar("h").status)
    }

    @Test
    fun transitMetroRebuildsWhenTheStoreChanges() {
        val store = MutableStateFlow(snapshot(DerivedStatus.NOT_STARTED))
        val presenter = TransitMetroPresenter(store, scope)
        val station = { id: String -> presenter.state.value.lines.flatMap { it.stations }.first { it.node.id == id } }
        assertEquals(DerivedStatus.NOT_STARTED, station("h").status)

        store.value = snapshot(DerivedStatus.DONE)
        assertEquals(DerivedStatus.DONE, station("h").status)
    }

    @Test
    fun breadboardRebuildsWhenTheStoreChanges() {
        val store = MutableStateFlow(snapshot(DerivedStatus.NOT_STARTED))
        val presenter = BreadboardSchematicPresenter(store, scope)
        val chip = { id: String -> presenter.state.value.chips.first { it.node.id == id } }
        assertFalse(chip("a").isEnergized)

        store.value = snapshot(DerivedStatus.DONE)
        assertTrue(chip("a").isEnergized)
    }

    @Test
    fun focusRadarRebuildsWhenTheStoreChanges() {
        val store = MutableStateFlow(snapshot(DerivedStatus.NOT_STARTED))
        val presenter = FocusRadarPresenter(store, scope)
        val zone = { presenter.state.value.radarNodes.first { it.node.id == "h" }.zone }
        assertEquals(RadarZone.INTERMEDIATE_ORBIT, zone())

        store.value = snapshot(DerivedStatus.DONE)
        assertEquals(RadarZone.OUTER_PERIMETER, zone())
    }

    @Test
    fun spatialCanvasRebuildsWhenTheStoreChanges() {
        val store = MutableStateFlow(snapshot(DerivedStatus.NOT_STARTED))
        val presenter = SpatialCanvasPresenter(store, scope)
        assertEquals(setOf("g", "h", "a"), presenter.state.value.positions.keys)

        store.value = snapshot(DerivedStatus.DONE)
        assertEquals(DerivedStatus.DONE, presenter.state.value.statuses["h"]?.derivedStatus)
    }

    @Test
    fun actionableForHumanMatchesTheRuntimeFilter() {
        // Human work is actionable until it is done; agent work only while it waits for review.
        assertEquals(listOf("h"), ActionInboxPresenter.actionableForHuman(snapshot(DerivedStatus.NOT_STARTED)).map { it.node.id })
        assertEquals(emptyList(), ActionInboxPresenter.actionableForHuman(snapshot(DerivedStatus.DONE)).map { it.node.id })
        assertEquals(
            listOf("h", "a"),
            ActionInboxPresenter.actionableForHuman(snapshot(DerivedStatus.IN_PROGRESS, DerivedStatus.NEEDS_REVIEW)).map { it.node.id },
        )
    }

    private fun node(id: String, type: IntentNodeType, actor: IntentActorKind) = IntentNodeRecord(
        id = id, projectId = "p", type = type, title = id,
        granularity = IntentGranularity.ATOMIC, lifecycle = IntentLifecycle.ACTIVE,
        actorKind = actor, dispatchPolicy = NodeDispatchPolicy.MANUAL_START,
        targetProfile = IntentTargetProfile.ANY, minLocality = IntentMinLocality.LOCAL_ONLY,
        createdAt = "2026-11-15T00:00:00Z", createdByKind = "USER", createdById = "u",
        modifiedAt = "2026-11-15T00:00:00Z", modifiedByKind = "USER", modifiedById = "u",
    )

    private fun view(node: IntentNodeRecord, status: DerivedStatus) = IntentNodeStatusView(
        node = node,
        derivedStatus = status,
        effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
        unmetDependencyIds = emptyList(),
        activeRunIds = emptyList(),
        pendingPlatformChecks = false,
    )
}

/** A snapshot for presenter tests; `loadedAt` 1 means "loaded", 0 means "not yet". */
internal fun testSnapshot(
    projectId: String,
    nodes: List<IntentNodeRecord>,
    edges: List<IntentEdgeRecord> = emptyList(),
    statuses: Map<String, IntentNodeStatusView> = emptyMap(),
    loadedAt: Long = 1L,
) = GraphSnapshot(
    projectId = projectId,
    nodes = nodes,
    edges = edges,
    statuses = statuses,
    proposals = emptyList(),
    loadedAt = loadedAt,
)
