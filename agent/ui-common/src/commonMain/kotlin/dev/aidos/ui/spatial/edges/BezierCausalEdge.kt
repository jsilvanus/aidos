package dev.aidos.ui.spatial.edges

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

/**
 * Smooth curved spline representing causal dependencies (RFC-0106).
 */
object BezierCausalEdge {
    fun drawBezierEdge(
        drawScope: DrawScope,
        start: Offset,
        end: Offset,
        color: Color,
        strokeWidth: Float = 2f,
    ) {
        val dx = (end.x - start.x) / 2f
        val control1 = Offset(start.x + dx, start.y)
        val control2 = Offset(end.x - dx, end.y)

        val path = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
        }

        drawScope.drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth),
        )
    }
}
