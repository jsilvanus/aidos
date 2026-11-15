package dev.aidos.ui.spatial.edges

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

/**
 * RFC-0106 §2: causal edges must meet a node at its boundary, not run through its body.
 *
 * Each endpoint that lies inside an obstacle (the node's bounding box) is moved along the edge to
 * the point where the segment leaves that box, so the stroke starts at the edge of the source node
 * and ends at the edge of the target node. Endpoints outside every obstacle are untouched, and a
 * segment that never leaves the box (both ends inside it) is left alone rather than collapsed.
 */
object EdgeCollisionRouter {
    fun routeAroundObstacles(
        start: Offset,
        end: Offset,
        obstacles: List<Rect>,
    ): Pair<Offset, Offset> {
        val clippedStart = obstacles.firstOrNull { it.contains(start) }
            ?.let { exitPoint(it, start, end) } ?: start
        val clippedEnd = obstacles.firstOrNull { it.contains(end) }
            ?.let { exitPoint(it, end, start) } ?: end
        return clippedStart to clippedEnd
    }

    /** First point where the ray from [inside] toward [toward] leaves [box]; null if [toward] is still inside. */
    internal fun exitPoint(box: Rect, inside: Offset, toward: Offset): Offset? {
        val dx = toward.x - inside.x
        val dy = toward.y - inside.y
        if (dx == 0f && dy == 0f) return null
        val tx = when {
            dx > 0f -> (box.right - inside.x) / dx
            dx < 0f -> (box.left - inside.x) / dx
            else -> Float.POSITIVE_INFINITY
        }
        val ty = when {
            dy > 0f -> (box.bottom - inside.y) / dy
            dy < 0f -> (box.top - inside.y) / dy
            else -> Float.POSITIVE_INFINITY
        }
        val t = minOf(tx, ty)
        if (t >= 1f) return null
        return Offset(inside.x + dx * t, inside.y + dy * t)
    }
}
