package dev.aidos.ui.spatial.schematic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens

/**
 * Design 2: The Transit Metro Map (RFC-0111).
 * Renders subway lines, interchange hubs, and ticket turnstiles.
 */
@Composable
fun TransitMetroMapView(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = "Transit Metro Map (Topology View)",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val lineRed = Color(0xFFEF4444)
                val lineBlue = Color(0xFF3B82F6)
                val lineGreen = Color(0xFF10B981)

                val y1 = size.height * 0.35f
                val y2 = size.height * 0.65f

                // Draw Subway Lines
                drawLine(color = lineRed, start = Offset(50f, y1), end = Offset(size.width - 50f, y1), strokeWidth = 8f)
                drawLine(color = lineBlue, start = Offset(size.width * 0.3f, 50f), end = Offset(size.width * 0.7f, size.height - 50f), strokeWidth = 8f)
                drawLine(color = lineGreen, start = Offset(50f, y2), end = Offset(size.width - 50f, y2), strokeWidth = 8f)

                // Interchange Hub
                val interchangeCenter = Offset(size.width * 0.44f, y1)
                drawCircle(color = Color.White, radius = 14f, center = interchangeCenter)
                drawCircle(color = Color.Black, radius = 8f, center = interchangeCenter)
            }
        }
    }
}
