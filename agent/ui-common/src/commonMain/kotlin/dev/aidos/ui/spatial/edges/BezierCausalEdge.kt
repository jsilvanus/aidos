package dev.aidos.ui.spatial.edges

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * Smooth curved spline representing causal dependencies (RFC-0106).
 * UI-35: optional dashed stroke (CONSTRAINS) and an arrowhead at the end (DEPENDS_ON).
 */
object BezierCausalEdge {
    private const val ARROW_LENGTH = 10f
    private const val ARROW_HALF_ANGLE = 0.45

    fun drawBezierEdge(
        drawScope: DrawScope,
        start: Offset,
        end: Offset,
        color: Color,
        strokeWidth: Float = 2f,
        dashed: Boolean = false,
        arrowAtEnd: Boolean = false,
    ) {
        val dx = (end.x - start.x) / 2f
        val control1 = Offset(start.x + dx, start.y)
        val control2 = Offset(end.x - dx, end.y)

        val path = Path().apply {
            moveTo(start.x, start.y)
            cubicTo(control1.x, control1.y, control2.x, control2.y, end.x, end.y)
        }

        val effect: PathEffect? = if (dashed) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null
        drawScope.drawPath(
            path = path,
            color = color,
            style = Stroke(width = strokeWidth, pathEffect = effect),
        )

        if (arrowAtEnd) {
            // The arrow points along the curve's final tangent (from the second control point to the end).
            val angle = atan2(end.y - control2.y, end.x - control2.x).toDouble()
            val left = Offset(
                end.x - ARROW_LENGTH * cos(angle - ARROW_HALF_ANGLE).toFloat(),
                end.y - ARROW_LENGTH * sin(angle - ARROW_HALF_ANGLE).toFloat(),
            )
            val right = Offset(
                end.x - ARROW_LENGTH * cos(angle + ARROW_HALF_ANGLE).toFloat(),
                end.y - ARROW_LENGTH * sin(angle + ARROW_HALF_ANGLE).toFloat(),
            )
            val head = Path().apply {
                moveTo(end.x, end.y)
                lineTo(left.x, left.y)
                lineTo(right.x, right.y)
                close()
            }
            drawScope.drawPath(path = head, color = color)
        }
    }
}
