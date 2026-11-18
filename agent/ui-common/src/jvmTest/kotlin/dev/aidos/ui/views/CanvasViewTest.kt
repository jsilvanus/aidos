package dev.aidos.ui.views

import androidx.compose.ui.geometry.Offset
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
import dev.aidos.ui.spatial.canvas.CanvasDetail
import dev.aidos.ui.spatial.canvas.CanvasEdges
import dev.aidos.ui.spatial.canvas.CanvasFocus
import dev.aidos.ui.spatial.canvas.CanvasHitTest
import dev.aidos.ui.spatial.canvas.ForceDirectedLayout
import dev.aidos.ui.spatial.canvas.ViewportTransform
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/** UI-35 mind-map canvas: pure rules only (zoom tiers, progress, edge style, focus, layout seeding, hit test). */
class CanvasViewTest {

    @Test
    fun zoomThresholdsPickTheTier() {
        assertEquals(CanvasDetail.Tier.GROUPS_ONLY, CanvasDetail.tierFor(0.1f))
        assertEquals(CanvasDetail.Tier.GROUPS_ONLY, CanvasDetail.tierFor(0.29f))
        assertEquals(CanvasDetail.Tier.GOALS, CanvasDetail.tierFor(0.3f))
        assertEquals(CanvasDetail.Tier.GOALS, CanvasDetail.tierFor(0.7f))
        assertEquals(CanvasDetail.Tier.FULL, CanvasDetail.tierFor(0.71f))
    }

    @Test
    fun eachTierDrawsItsNodeTypes() {
        assertTrue(CanvasDetail.isDrawn(CanvasDetail.Tier.GROUPS_ONLY, IntentNodeType.GROUP))
        assertFalse(CanvasDetail.isDrawn(CanvasDetail.Tier.GROUPS_ONLY, IntentNodeType.GOAL))
        assertTrue(CanvasDetail.isDrawn(CanvasDetail.Tier.GOALS, IntentNodeType.GOAL))
        assertTrue(CanvasDetail.isDrawn(CanvasDetail.Tier.GOALS, IntentNodeType.SUB_GOAL))
        assertFalse(CanvasDetail.isDrawn(CanvasDetail.Tier.GOALS, IntentNodeType.ATOMIC_TASK))
        assertTrue(CanvasDetail.isDrawn(CanvasDetail.Tier.FULL, IntentNodeType.ATOMIC_TASK))
        assertTrue(CanvasDetail.isDrawn(CanvasDetail.Tier.FULL, IntentNodeType.CONSTRAINT))
    }

    @Test
    fun progressArcIsDoneChildrenOverAllChildren() {
        assertEquals(0f, CanvasDetail.progressFraction(emptyList()))
        assertEquals(0.5f, CanvasDetail.progressFraction(listOf(DerivedStatus.DONE, DerivedStatus.IN_PROGRESS)))
        assertEquals(1f, CanvasDetail.progressFraction(listOf(DerivedStatus.DONE, DerivedStatus.DONE)))

        val goal = node("g", IntentNodeType.GOAL)
        val a = node("a", IntentNodeType.ATOMIC_TASK, parent = "g")
        val b = node("b", IntentNodeType.ATOMIC_TASK, parent = "g")
        val progress = CanvasDetail.progressByNode(
            listOf(goal, a, b),
            mapOf("a" to status(a, DerivedStatus.DONE), "b" to status(b, DerivedStatus.NOT_STARTED)),
        )
        assertEquals(0.5f, progress["g"])
        assertNull(progress["a"]) // leaves have no arc
    }

    @Test
    fun edgeStylePerKind() {
        val depends = CanvasEdges.style(IntentEdgeKind.DEPENDS_ON, conflicted = false)
        assertTrue(depends.arrowAtEnd)
        assertFalse(depends.dashed)

        val constrains = CanvasEdges.style(IntentEdgeKind.CONSTRAINS, conflicted = false)
        assertTrue(constrains.dashed)
        assertFalse(constrains.arrowAtEnd)

        val accepts = CanvasEdges.style(IntentEdgeKind.ACCEPTS, conflicted = false)
        assertFalse(accepts.dashed)
        assertTrue(accepts.strokeWidth < depends.strokeWidth)

        // A conflicted node turns its edges red whatever the kind.
        val red = CanvasEdges.style(IntentEdgeKind.ACCEPTS, conflicted = true)
        assertEquals(CanvasEdges.style(IntentEdgeKind.DEPENDS_ON, conflicted = true).color, red.color)
        assertTrue(red.color != accepts.color)
    }

    @Test
    fun dependsOnIsDrawnFromPrerequisiteToDependent() {
        val dependsOn = edge("e", from = "dependent", to = "prerequisite", kind = IntentEdgeKind.DEPENDS_ON)
        assertEquals("prerequisite" to "dependent", CanvasEdges.drawnEndpoints(dependsOn))
        val constrains = edge("c", from = "x", to = "y", kind = IntentEdgeKind.CONSTRAINS)
        assertEquals("x" to "y", CanvasEdges.drawnEndpoints(constrains))
    }

    @Test
    fun compactFocusPrefersSelectedThenFirstUnfinishedGoal() {
        val done = node("done", IntentNodeType.GOAL)
        val open = node("open", IntentNodeType.GOAL)
        val task = node("task", IntentNodeType.ATOMIC_TASK)
        val nodes = listOf(done, task, open)
        val statuses = mapOf("done" to status(done, DerivedStatus.DONE), "open" to status(open, DerivedStatus.IN_PROGRESS))

        assertEquals("task", CanvasFocus.initialFocus(nodes, statuses, selectedNodeId = "task"))
        assertEquals("open", CanvasFocus.initialFocus(nodes, statuses, selectedNodeId = null))
        assertEquals("open", CanvasFocus.initialFocus(nodes, statuses, selectedNodeId = "missing"))
        assertNull(CanvasFocus.initialFocus(listOf(done), statuses, selectedNodeId = null))
    }

    @Test
    fun breadcrumbIsTheAncestorChainRootFirstAndCycleSafe() {
        val root = node("root", IntentNodeType.GROUP)
        val mid = node("mid", IntentNodeType.GOAL, parent = "root")
        val leaf = node("leaf", IntentNodeType.ATOMIC_TASK, parent = "mid")
        val crumbs = CanvasFocus.breadcrumb("leaf", listOf(root, mid, leaf))
        assertEquals(listOf("root", "mid"), crumbs.map { it.id })
        assertTrue(CanvasFocus.breadcrumb("root", listOf(root, mid, leaf)).isEmpty())

        val a = node("a", IntentNodeType.GOAL, parent = "b")
        val b = node("b", IntentNodeType.GOAL, parent = "a")
        assertEquals(listOf("b"), CanvasFocus.breadcrumb("a", listOf(a, b)).map { it.id })
    }

    @Test
    fun compactOpenCentresTheFocusNodeAtZoomEightTenths() {
        val vp = CanvasFocus.centredViewport(worldX = 700f, worldY = 300f, viewWidth = 400f, viewHeight = 800f)
        assertEquals(0.8f, vp.zoom, 0.0001f)
        val screen = vp.toScreenCoordinates(700f, 300f)
        assertEquals(200f, screen.x, 0.01f)
        assertEquals(400f, screen.y, 0.01f)
    }

    @Test
    fun layoutSeedingKeepsPositionsWhenTheNodeSetIsUnchanged() {
        val nodes = listOf(node("a", IntentNodeType.GOAL), node("b", IntentNodeType.GOAL))
        val previous = mapOf("a" to (10f to 20f), "b" to (300f to 400f))
        val result = ForceDirectedLayout.layoutSeeded(nodes, emptyList(), previous)
        assertEquals(previous, result)
    }

    @Test
    fun layoutSeedingMovesOnlyNewNodes() {
        val a = node("a", IntentNodeType.GOAL)
        val b = node("b", IntentNodeType.GOAL)
        val previous = mapOf("a" to (10f to 20f), "b" to (300f to 400f))
        val edges = listOf(edge("e", from = "a", to = "c", kind = IntentEdgeKind.DEPENDS_ON))
        val c = node("c", IntentNodeType.GOAL)

        val result = ForceDirectedLayout.layoutSeeded(listOf(a, b, c), edges, previous)
        assertEquals(10f to 20f, result["a"])
        assertEquals(300f to 400f, result["b"])
        assertNotNull(result["c"])
        assertEquals(3, result.size)
    }

    @Test
    fun layoutOfAFreshGraphIsFiniteAndCoversEveryNode() {
        val nodes = (0 until 30).map { node("n$it", IntentNodeType.GOAL) }
        val edges = (1 until 30).map { edge("e$it", from = "n$it", to = "n${it - 1}", kind = IntentEdgeKind.DEPENDS_ON) }
        val result = ForceDirectedLayout.layoutSeeded(nodes, edges, emptyMap())
        assertEquals(30, result.size)
        result.values.forEach { (x, y) ->
            assertTrue(x.isFinite() && y.isFinite())
        }
    }

    @Test
    fun hitTestPicksTheNearestDrawnNodeWithinRadius() {
        val vp = ViewportTransform(zoom = 1f)
        val positions = mapOf("near" to (100f to 100f), "far" to (160f to 100f))
        assertEquals("near", CanvasHitTest.nodeAt(Offset(104f, 100f), vp, positions))
        assertNull(CanvasHitTest.nodeAt(Offset(400f, 400f), vp, positions))
    }

    private fun node(id: String, type: IntentNodeType, parent: String? = null) = IntentNodeRecord(
        id = id,
        projectId = "p",
        type = type,
        title = id,
        parentId = parent,
        granularity = IntentGranularity.GOAL,
        lifecycle = IntentLifecycle.ACTIVE,
        actorKind = IntentActorKind.AGENT,
        dispatchPolicy = NodeDispatchPolicy.MANUAL_START,
        targetProfile = IntentTargetProfile.ANY,
        minLocality = IntentMinLocality.LOCAL_ONLY,
        createdAt = "2026-11-18T00:00:00Z",
        createdByKind = "USER",
        createdById = "u",
        modifiedAt = "2026-11-18T00:00:00Z",
        modifiedByKind = "USER",
        modifiedById = "u",
    )

    private fun edge(id: String, from: String, to: String, kind: IntentEdgeKind) = IntentEdgeRecord(
        id = id,
        projectId = "p",
        fromNodeId = from,
        toNodeId = to,
        edgeKind = kind,
        createdAt = "2026-11-18T00:00:00Z",
    )

    private fun status(node: IntentNodeRecord, derived: DerivedStatus) = IntentNodeStatusView(
        node = node,
        derivedStatus = derived,
        effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
        unmetDependencyIds = emptyList(),
        activeRunIds = emptyList(),
        pendingPlatformChecks = false,
    )
}
