package dev.aidos.ui.spatial.nodes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Orbiting diamond node connected via ACCEPTS lines (RFC-0106).
 */
object SatelliteCriterionNode {
    fun drawDiamond(
        drawScope: DrawScope,
        center: Offset,
        size: Float,
        fillColor: Color,
        borderColor: Color,
    ) {
        val half = size / 2f
        val path = Path().apply {
            moveTo(center.x, center.y - half)
            lineTo(center.x + half, center.y)
            lineTo(center.x, center.y + half)
            lineTo(center.x - half, center.y)
            close()
        }

        drawScope.drawPath(path = path, color = fillColor)
        drawScope.drawPath(path = path, color = borderColor, style = Stroke(width = 1.5f))
    }
}
