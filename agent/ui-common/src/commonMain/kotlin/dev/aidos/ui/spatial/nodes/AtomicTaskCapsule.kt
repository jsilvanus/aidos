package dev.aidos.ui.spatial.nodes

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke

object AtomicTaskCapsule {
    fun drawCapsule(
        drawScope: DrawScope,
        center: Offset,
        width: Float,
        height: Float,
        fillColor: Color,
        borderColor: Color,
    ) {
        val topLeft = Offset(center.x - width / 2f, center.y - height / 2f)
        val size = Size(width, height)
        val corner = CornerRadius(height / 2f, height / 2f)

        drawScope.drawRoundRect(
            color = fillColor,
            topLeft = topLeft,
            size = size,
            cornerRadius = corner,
        )

        drawScope.drawRoundRect(
            color = borderColor,
            topLeft = topLeft,
            size = size,
            cornerRadius = corner,
            style = Stroke(width = 1.5f),
        )
    }
}
