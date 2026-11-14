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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens

/**
 * Design 5: The Logic Circuit Breadboard (RFC-0111).
 * Renders IC chips, logic gates, and glowing conductive copper traces.
 */
@Composable
fun BreadboardSchematicView(
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF022C22)) // Dark PCB Green
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = "Logic Circuit Breadboard (PCB Schematic)",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color(0xFF6EE7B7),
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                // Draw copper grid traces
                val step = 40f
                for (x in 0..(size.width / step).toInt()) {
                    drawLine(
                        color = Color(0x3310B981),
                        start = Offset(x * step, 0f),
                        end = Offset(x * step, size.height),
                        strokeWidth = 1f,
                    )
                }

                // Draw central IC Chip
                val icWidth = 180f
                val icHeight = 100f
                val icCenter = Offset(size.width / 2f, size.height / 2f)
                val icTopLeft = Offset(icCenter.x - icWidth / 2f, icCenter.y - icHeight / 2f)

                drawRect(
                    color = Color(0xFF0F172A),
                    topLeft = icTopLeft,
                    size = Size(icWidth, icHeight),
                )
                drawRect(
                    color = Color(0xFFF59E0B), // Gold Pins
                    topLeft = icTopLeft,
                    size = Size(icWidth, icHeight),
                    style = Stroke(2f),
                )
            }
        }
    }
}
