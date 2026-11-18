package dev.aidos.ui.spatial.radar

import dev.aidos.ui.layout.FormFactor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForStatus
import kotlin.math.cos
import kotlin.math.sin
import dev.aidos.ui.workspace.WorkspaceController

/**
 * Desktop 360-degree radial radar HUD (Design 4, RFC-0111).
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun RadialRadarScreen(
    presenter: FocusRadarPresenter,
    controller: WorkspaceController,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = "Radial Focus Radar (360° Desktop HUD)",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        Box(modifier = Modifier.fillMaxSize()) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val center = Offset(size.width / 2f, size.height / 2f)
                val maxRadius = size.minDimension / 2.2f

                val strikeRadius = maxRadius * 0.35f
                val intermediateRadius = maxRadius * 0.70f
                val perimeterRadius = maxRadius * 1.0f

                // Draw orbital rings
                drawCircle(color = Color(0x3338BDF8), radius = strikeRadius, center = center, style = Stroke(1.5f))
                drawCircle(color = Color(0x2294A3B8), radius = intermediateRadius, center = center, style = Stroke(1f))
                drawCircle(color = Color(0x1194A3B8), radius = perimeterRadius, center = center, style = Stroke(1f))

                // Plot nodes
                state.radarNodes.forEach { item ->
                    val r = when (item.zone) {
                        RadarZone.STRIKE_ZONE -> strikeRadius * 0.8f
                        RadarZone.INTERMEDIATE_ORBIT -> (strikeRadius + intermediateRadius) / 2f
                        RadarZone.OUTER_PERIMETER -> (intermediateRadius + perimeterRadius) / 2f
                    }

                    val x = center.x + r * cos(item.angleRad)
                    val y = center.y + r * sin(item.angleRad)

                    val color = colorForStatus(item.status)
                    drawCircle(color = color, radius = 6f, center = Offset(x, y))
                }
            }
        }
    }
}
