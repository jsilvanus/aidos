package dev.aidos.ui.spatial.nodes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Translucent bounding hull grouping child nodes (RFC-0106).
 */
object GroupNebulaRenderer {
    fun drawNebula(
        drawScope: DrawScope,
        topLeft: Offset,
        size: Size,
        accentColor: Color,
        label: String,
    ) {
        // Translucent background hull
        drawScope.drawRect(
            color = accentColor.copy(alpha = 0.08f),
            topLeft = topLeft,
            size = size,
        )

        // Bounding border
        drawScope.drawRect(
            color = accentColor.copy(alpha = 0.3f),
            topLeft = topLeft,
            size = size,
            style = Stroke(width = 1.5f),
        )
    }
}
