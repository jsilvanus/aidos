package dev.aidos.ui.spatial.nodes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/** RFC-0111 Design 7: a CONSTRAINT is drawn as a shield (UI-35). */
object ConstraintShieldNode {
    fun drawShield(
        drawScope: DrawScope,
        center: Offset,
        size: Float,
        fillColor: Color,
        borderColor: Color,
    ) {
        val half = size / 2f
        val path = Path().apply {
            moveTo(center.x - half, center.y - half * 0.8f)
            lineTo(center.x, center.y - half)
            lineTo(center.x + half, center.y - half * 0.8f)
            lineTo(center.x + half, center.y + half * 0.1f)
            lineTo(center.x, center.y + half)
            lineTo(center.x - half, center.y + half * 0.1f)
            close()
        }
        drawScope.drawPath(path = path, color = fillColor)
        drawScope.drawPath(path = path, color = borderColor, style = Stroke(width = 2f))
    }
}
