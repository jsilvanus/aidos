package dev.aidos.ui

import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.ui.spatial.canvas.ForceDirectedLayout
import dev.aidos.ui.spatial.canvas.LodLevel
import dev.aidos.ui.spatial.canvas.SemanticZoomManager
import dev.aidos.ui.spatial.canvas.ViewportTransform
import dev.aidos.ui.spatial.radar.RadarZone
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhaseFiveTest {

    @Test
    fun testOneThousandNodesAndEdgesLayoutPerformance() {
        val nodeCount = 1_000
        val nodes = (1..nodeCount).map { i ->
            IntentNodeRecord(
                id = "node-$i",
                projectId = "proj-spatial",
                type = IntentNodeType.ATOMIC_TASK,
                title = "Task #$i",
                granularity = IntentGranularity.ATOMIC,
                lifecycle = IntentLifecycle.ACTIVE,
                actorKind = IntentActorKind.AGENT,
                dispatchPolicy = NodeDispatchPolicy.MANUAL_START,
                targetProfile = IntentTargetProfile.ANY,
                minLocality = IntentMinLocality.LOCAL_ONLY,
                createdAt = "2026-11-14T20:00:00Z",
                createdByKind = "USER",
                createdById = "local-user",
                modifiedAt = "2026-11-14T20:00:00Z",
                modifiedByKind = "USER",
                modifiedById = "local-user",
            )
        }

        val edges = (1..2_500).map { i ->
            val fromIndex = (i % nodeCount) + 1
            val toIndex = ((i + 1) % nodeCount) + 1
            IntentEdgeRecord(
                id = "edge-$i",
                projectId = "proj-spatial",
                fromNodeId = "node-$fromIndex",
                toNodeId = "node-$toIndex",
                edgeKind = IntentEdgeKind.DEPENDS_ON,
                createdAt = "2026-11-14T20:00:00Z",
            )
        }

        val elapsed = measureTimeMillis {
            val positions = ForceDirectedLayout.layoutDag(nodes, edges, 2000f, 2000f)
            assertEquals(1_000, positions.size)
        }

        assertTrue(elapsed < 100, "Layout calculation for 1,000 nodes took too long: ${elapsed}ms")
    }

    @Test
    fun testLodTransitionsAndCulling() {
        // LOD Zoom breakpoints
        assertEquals(LodLevel.MACRO_GALAXY, SemanticZoomManager.computeLod(0.1f))
        assertEquals(LodLevel.MACRO_GALAXY, SemanticZoomManager.computeLod(0.34f))
        assertEquals(LodLevel.MESO_CLUSTER, SemanticZoomManager.computeLod(0.35f))
        assertEquals(LodLevel.MESO_CLUSTER, SemanticZoomManager.computeLod(0.74f))
        assertEquals(LodLevel.MICRO_BLUEPRINT, SemanticZoomManager.computeLod(0.75f))
        assertEquals(LodLevel.MICRO_BLUEPRINT, SemanticZoomManager.computeLod(1.5f))

        // Viewport Culling Verification
        val viewport = ViewportTransform(panX = 0f, panY = 0f, zoom = 1.0f)
        val inScreen = viewport.isPointInViewport(500f, 500f, 1920f, 1080f)
        val offScreen = viewport.isPointInViewport(5000f, 5000f, 1920f, 1080f)

        assertTrue(inScreen)
        assertFalse(offScreen)
    }

    @Test
    fun testRadarZones() {
        val zones = listOf(RadarZone.STRIKE_ZONE, RadarZone.INTERMEDIATE_ORBIT, RadarZone.OUTER_PERIMETER)
        assertEquals(3, zones.size)
    }
}
