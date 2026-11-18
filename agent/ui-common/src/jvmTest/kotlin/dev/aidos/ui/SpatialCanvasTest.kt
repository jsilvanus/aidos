package dev.aidos.ui

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.CreateIntentProposalRequest
import dev.aidos.api.IntentCommands
import dev.aidos.api.UpdateIntentNodeRequest
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
import dev.aidos.ui.spatial.canvas.LodLevel
import dev.aidos.ui.spatial.canvas.SpatialCanvasIntent
import dev.aidos.ui.spatial.canvas.SpatialCanvasPresenter
import dev.aidos.ui.spatial.canvas.ViewportTransform
import dev.aidos.ui.spatial.edges.EdgeCollisionRouter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** RFC-0106 spatial canvas: edge anchoring, viewport maths and the MVI presenter. */
class SpatialCanvasTest {
    private val box = Rect(0f, 0f, 100f, 100f)

    @Test
    fun edgeLeavesSourceBoxOnItsBoundary() {
        val (s, e) = EdgeCollisionRouter.routeAroundObstacles(
            start = Offset(50f, 50f),
            end = Offset(300f, 50f),
            obstacles = listOf(box),
        )
        assertEquals(Offset(100f, 50f), s)
        assertEquals(Offset(300f, 50f), e) // target outside every obstacle: untouched
    }

    @Test
    fun bothEndpointsAreClippedToTheirOwnBoxes() {
        val target = Rect(200f, 0f, 300f, 100f)
        val (s, e) = EdgeCollisionRouter.routeAroundObstacles(
            Offset(50f, 50f), Offset(250f, 50f), listOf(box, target),
        )
        assertEquals(Offset(100f, 50f), s)
        assertEquals(Offset(200f, 50f), e)
    }

    @Test
    fun diagonalEdgeExitsThroughTheNearerSide() {
        // Heading mostly downward: leaves through the bottom, not the right side.
        val (s, _) = EdgeCollisionRouter.routeAroundObstacles(
            Offset(50f, 50f), Offset(60f, 400f), listOf(box),
        )
        assertEquals(100f, s.y, 0.001f)
        assertTrue(s.x in 50f..60f)
    }

    @Test
    fun edgeEntirelyInsideOneBoxIsNotCollapsed() {
        val (s, e) = EdgeCollisionRouter.routeAroundObstacles(
            Offset(10f, 10f), Offset(90f, 90f), listOf(box),
        )
        assertEquals(Offset(10f, 10f), s)
        assertEquals(Offset(90f, 90f), e)
    }

    @Test
    fun degenerateAndObstaclelessEdgesAreUnchanged() {
        val p = Offset(5f, 5f)
        assertEquals(p to p, EdgeCollisionRouter.routeAroundObstacles(p, p, listOf(box)))
        val a = Offset(500f, 500f)
        val b = Offset(900f, 700f)
        assertEquals(a to b, EdgeCollisionRouter.routeAroundObstacles(a, b, emptyList()))
    }

    @Test
    fun viewportRoundTripsAndCullsWithMargin() {
        val vp = ViewportTransform(panX = 40f, panY = -20f, zoom = 0.5f)
        val screen = vp.toScreenCoordinates(300f, 200f)
        val world = vp.toWorldCoordinates(screen.x, screen.y)
        assertEquals(300f, world.x, 0.001f)
        assertEquals(200f, world.y, 0.001f)
        // Just outside the screen but inside the 100px margin still draws; far outside is culled.
        assertTrue(ViewportTransform().isPointInViewport(-50f, 10f, 800f, 600f))
        assertFalse(ViewportTransform().isPointInViewport(-500f, 10f, 800f, 600f))
    }

    @Test
    fun presenterLoadsGraphLaysItOutAndHandlesGestures() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val nodes = (0 until 3).map { node("n$it") }
        val edges = listOf(edge("e0", "n0", "n1"), edge("e1", "n1", "n2"))
        val presenter = SpatialCanvasPresenter(MutableStateFlow(testSnapshot("p", nodes, edges)), scope)

        val loaded = presenter.state.value
        assertFalse(loaded.isLoading)
        assertEquals(3, loaded.nodes.size)
        assertEquals(setOf("n0", "n1", "n2"), loaded.positions.keys)

        presenter.send(SpatialCanvasIntent.Pan(10f, -5f))
        presenter.send(SpatialCanvasIntent.Pan(5f, 5f))
        assertEquals(15f, presenter.state.value.viewport.panX)
        assertEquals(0f, presenter.state.value.viewport.panY)

        presenter.send(SpatialCanvasIntent.Zoom(0.1f))
        assertEquals(0.1f, presenter.state.value.viewport.zoom, 0.0001f)
        assertEquals(LodLevel.MACRO_GALAXY, presenter.state.value.lodLevel)

        // Zoom is clamped to the 0.05..2.0 range however hard the gesture pushes.
        presenter.send(SpatialCanvasIntent.Zoom(0.0001f))
        assertEquals(0.05f, presenter.state.value.viewport.zoom, 0.0001f)
        presenter.send(SpatialCanvasIntent.Zoom(1_000_000f))
        assertEquals(2.0f, presenter.state.value.viewport.zoom, 0.0001f)
        assertEquals(LodLevel.MICRO_BLUEPRINT, presenter.state.value.lodLevel)

        presenter.send(SpatialCanvasIntent.ResetViewport)
        assertEquals(ViewportTransform(), presenter.state.value.viewport)
        assertEquals(LodLevel.MESO_CLUSTER, presenter.state.value.lodLevel)
        assertNotEquals(0, presenter.state.value.positions.size)
    }

    @Test
    fun presenterSurvivesALoadFailure() {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val presenter = SpatialCanvasPresenter(MutableStateFlow(GraphSnapshot.empty("p").copy(error = "boom")), scope)
        assertFalse(presenter.state.value.isLoading)
        assertTrue(presenter.state.value.nodes.isEmpty())
    }

    private fun node(id: String) = IntentNodeRecord(
        id = id, projectId = "p", type = IntentNodeType.ATOMIC_TASK, title = id,
        granularity = IntentGranularity.ATOMIC, lifecycle = IntentLifecycle.ACTIVE,
        actorKind = IntentActorKind.AGENT, dispatchPolicy = NodeDispatchPolicy.MANUAL_START,
        targetProfile = IntentTargetProfile.ANY, minLocality = IntentMinLocality.LOCAL_ONLY,
        createdAt = "2026-11-15T00:00:00Z", createdByKind = "USER", createdById = "u",
        modifiedAt = "2026-11-15T00:00:00Z", modifiedByKind = "USER", modifiedById = "u",
    )

    private fun edge(id: String, from: String, to: String) = IntentEdgeRecord(
        id = id, projectId = "p", fromNodeId = from, toNodeId = to,
        edgeKind = IntentEdgeKind.DEPENDS_ON, createdAt = "2026-11-15T00:00:00Z",
    )
}
