package dev.aidos.ui.spatial.nodes

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

object GoalHexagonNode {
    fun drawHexagon(
        drawScope: DrawScope,
        center: Offset,
        radius: Float,
        fillColor: Color,
        borderColor: Color,
    ) {
        val path = Path()
        for (i in 0..5) {
            val angle = (Math.PI / 3.0 * i).toFloat()
            val x = center.x + radius * cos(angle)
            val y = center.y + radius * sin(angle)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()

        drawScope.drawPath(path = path, color = fillColor)
        drawScope.drawPath(path = path, color = borderColor, style = Stroke(width = 2f))
    }
}
