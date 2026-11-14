package dev.aidos.ui.spatial.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.CornerTokens

@Composable
fun CanvasMinimapView(
    nodePositions: Map<String, Pair<Float, Float>>,
    viewport: ViewportTransform,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .size(160.dp, 120.dp)
            .background(Color(0xDD0F172A), shape = RoundedCornerShape(CornerTokens.sm))
            .border(1.dp, Color(0xFF334155), shape = RoundedCornerShape(CornerTokens.sm))
    ) {
        Canvas(modifier = Modifier.size(160.dp, 120.dp)) {
            val scaleX = size.width / 2000f
            val scaleY = size.height / 2000f

            // Draw miniature nodes
            nodePositions.values.forEach { (wx, wy) ->
                drawCircle(
                    color = Color(0xFF38BDF8),
                    radius = 2f,
                    center = Offset(wx * scaleX, wy * scaleY),
                )
            }

            // Draw viewport rect
            val vpX = (-viewport.panX) * scaleX
            val vpY = (-viewport.panY) * scaleY
            val vpW = (size.width / viewport.zoom) * scaleX
            val vpH = (size.height / viewport.zoom) * scaleY

            drawRect(
                color = Color(0xFFF59E0B),
                topLeft = Offset(vpX.coerceAtLeast(0f), vpY.coerceAtLeast(0f)),
                size = Size(vpW.coerceAtMost(size.width), vpH.coerceAtMost(size.height)),
                style = Stroke(width = 1.5f),
            )
        }
    }
}
