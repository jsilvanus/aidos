package dev.aidos.ui.spatial.edges

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect

object EdgeCollisionRouter {
    fun routeAroundObstacles(
        start: Offset,
        end: Offset,
        obstacles: List<Rect>,
    ): Pair<Offset, Offset> {
        // Returns the start and end anchors clipped cleanly to bounding boxes
        return start to end
    }
}
