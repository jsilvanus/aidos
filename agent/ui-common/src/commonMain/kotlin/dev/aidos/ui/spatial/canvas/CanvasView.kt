package dev.aidos.ui.spatial.canvas

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.theme.ColorTokens

/*
 * Pure rules for the Mind-Map Constellation canvas (RFC-0111 Design 7, RFC-0106 §1.1, UI-35).
 * Kept free of Compose drawing so the JVM tests can check them directly.
 */

/** Semantic zoom: which node types are drawn at a given zoom. Thresholds follow UI-35, not [SemanticZoomManager]. */
object CanvasDetail {
    enum class Tier { GROUPS_ONLY, GOALS, FULL }

    fun tierFor(zoom: Float): Tier = when {
        zoom < 0.3f -> Tier.GROUPS_ONLY
        zoom <= 0.7f -> Tier.GOALS
        else -> Tier.FULL
    }

    fun isDrawn(tier: Tier, type: IntentNodeType): Boolean = when (tier) {
        Tier.GROUPS_ONLY -> type == IntentNodeType.GROUP
        Tier.GOALS -> type == IntentNodeType.GOAL || type == IntentNodeType.SUB_GOAL
        Tier.FULL -> true
    }

    /** Done children over all children; 0 when there are none. */
    fun progressFraction(childStatuses: List<DerivedStatus>): Float {
        if (childStatuses.isEmpty()) return 0f
        return childStatuses.count { it == DerivedStatus.DONE }.toFloat() / childStatuses.size
    }

    /** Progress arc fraction for every node that has children, computed once per snapshot. */
    fun progressByNode(
        nodes: List<IntentNodeRecord>,
        statuses: Map<String, IntentNodeStatusView>,
    ): Map<String, Float> {
        val childrenByParent = nodes.groupBy { it.parentId }
        val result = HashMap<String, Float>()
        for ((parentId, children) in childrenByParent) {
            if (parentId == null) continue
            result[parentId] = progressFraction(
                children.map { statuses[it.id]?.derivedStatus ?: DerivedStatus.NOT_STARTED },
            )
        }
        return result
    }

    /** A node pulses while it has an active run (UI-35). */
    fun isRunning(status: IntentNodeStatusView?): Boolean = status?.activeRunIds?.isNotEmpty() == true
}

data class CanvasEdgeStyle(
    val color: Color,
    val strokeWidth: Float,
    val dashed: Boolean,
    val arrowAtEnd: Boolean,
)

object CanvasEdges {
    /** Style per edge kind; an edge touching a CONFLICTED node is red whatever its kind. */
    fun style(kind: IntentEdgeKind, conflicted: Boolean): CanvasEdgeStyle {
        if (conflicted) {
            return CanvasEdgeStyle(color = ColorTokens.StatusConflicted, strokeWidth = 2f, dashed = false, arrowAtEnd = kind == IntentEdgeKind.DEPENDS_ON)
        }
        return when (kind) {
            IntentEdgeKind.DEPENDS_ON -> CanvasEdgeStyle(Color(0x9938BDF8), 2f, dashed = false, arrowAtEnd = true)
            IntentEdgeKind.CONSTRAINS -> CanvasEdgeStyle(Color(0xAAF59E0B), 1.5f, dashed = true, arrowAtEnd = false)
            IntentEdgeKind.ACCEPTS -> CanvasEdgeStyle(Color(0x6694A3B8), 1f, dashed = false, arrowAtEnd = false)
        }
    }

    /**
     * DEPENDS_ON is stored as "from depends on to", so it is drawn from the prerequisite to the dependent
     * and the arrow lands on the dependent. The other kinds are drawn as stored.
     */
    fun drawnEndpoints(edge: IntentEdgeRecord): Pair<String, String> = when (edge.edgeKind) {
        IntentEdgeKind.DEPENDS_ON -> edge.toNodeId to edge.fromNodeId
        else -> edge.fromNodeId to edge.toNodeId
    }
}

object CanvasFocus {
    /** The selected node if there is one; otherwise the first goal that is not DONE. */
    fun initialFocus(
        nodes: List<IntentNodeRecord>,
        statuses: Map<String, IntentNodeStatusView>,
        selectedNodeId: String?,
    ): String? {
        if (selectedNodeId != null && nodes.any { it.id == selectedNodeId }) return selectedNodeId
        return nodes.firstOrNull {
            it.type == IntentNodeType.GOAL && statuses[it.id]?.derivedStatus != DerivedStatus.DONE
        }?.id
    }

    /** Ancestors of [nodeId], root first, not including the node itself. Cycle-safe. */
    fun breadcrumb(nodeId: String?, nodes: List<IntentNodeRecord>): List<IntentNodeRecord> {
        if (nodeId == null) return emptyList()
        val byId = nodes.associateBy { it.id }
        val chain = ArrayList<IntentNodeRecord>()
        val seen = hashSetOf(nodeId)
        var parent = byId[nodeId]?.parentId
        while (parent != null && seen.add(parent)) {
            val node = byId[parent] ?: break
            chain.add(0, node)
            parent = node.parentId
        }
        return chain
    }

    /** Compact open: the focus node centred in the view at [zoom]. Screen = (world + pan) * zoom. */
    fun centredViewport(
        worldX: Float,
        worldY: Float,
        viewWidth: Float,
        viewHeight: Float,
        zoom: Float = 0.8f,
    ): ViewportTransform = ViewportTransform(
        panX = viewWidth / 2f / zoom - worldX,
        panY = viewHeight / 2f / zoom - worldY,
        zoom = zoom,
    )
}

object CanvasHitTest {
    /** Nearest node whose centre is within [radiusPx] screen pixels of [point], or null. */
    fun nodeAt(
        point: Offset,
        viewport: ViewportTransform,
        positions: Map<String, Pair<Float, Float>>,
        radiusPx: Float = 28f,
    ): String? {
        var best: String? = null
        var bestDistance = radiusPx
        for ((id, pos) in positions) {
            val distance = (viewport.toScreenCoordinates(pos.first, pos.second) - point).getDistance()
            if (distance <= bestDistance) {
                best = id
                bestDistance = distance
            }
        }
        return best
    }
}
