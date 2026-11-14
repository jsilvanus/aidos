package dev.aidos.ui.spatial.radar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import dev.aidos.ui.theme.colorForStatus
import kotlin.math.cos
import kotlin.math.sin

/**
 * Mobile 180-degree bottom-arc thumb-zone view (Design 4, RFC-0111).
 * Positioned in the bottom 40% of the screen for ergonomic single-hand reach.
 */
@Composable
fun MobileHorizonArcView(
    presenter: FocusRadarPresenter,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()

    Box(modifier = modifier.fillMaxSize()) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2f, size.height)
            val maxRadius = size.width / 2.1f

            val strikeRadius = maxRadius * 0.5f
            val orbitRadius = maxRadius * 0.9f

            // Draw 180-degree semi-circle arcs
            drawCircle(color = Color(0x3338BDF8), radius = strikeRadius, center = center, style = Stroke(1.5f))
            drawCircle(color = Color(0x2294A3B8), radius = orbitRadius, center = center, style = Stroke(1f))

            // Plot strike zone nodes within upper 180 degrees (-PI to 0)
            state.radarNodes.forEach { item ->
                // Map angle to upper semi-circle
                val semiAngle = -Math.PI.toFloat() + (item.angleRad % Math.PI.toFloat())
                val r = if (item.zone == RadarZone.STRIKE_ZONE) strikeRadius * 0.85f else orbitRadius * 0.95f

                val x = center.x + r * cos(semiAngle)
                val y = center.y + r * sin(semiAngle)

                drawCircle(
                    color = colorForStatus(item.status),
                    radius = 8f,
                    center = Offset(x, y),
                )
            }
        }
    }
}
