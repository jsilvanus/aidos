package dev.aidos.ui.pills

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.ColorTokens

/**
 * Concentric radiating ripple emitter ("Agent working here") (RFC-0106).
 * Emits at 1.5Hz frequency (approx 667ms per cycle) with decaying opacity (0.4 -> 0.0).
 */
@Composable
fun WaveSignalingCanvas(
    modifier: Modifier = Modifier,
    waveColor: Color = ColorTokens.WaveRippleEmission,
    size: Dp = 120.dp,
    isActive: Boolean = true,
) {
    if (!isActive) return

    val infiniteTransition = rememberInfiniteTransition(label = "WaveRippleTransition")
    val progress by infiniteTransition.animateFloat(
        initialValue = 0.0f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 667, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "RippleProgress"
    )

    Canvas(modifier = modifier.size(size)) {
        val center = Offset(this.size.width / 2f, this.size.height / 2f)
        val maxRadius = this.size.minDimension / 2f

        // Two concentric staggered wavefronts
        val wave1Progress = progress
        val wave2Progress = (progress + 0.5f) % 1.0f

        val radius1 = maxRadius * wave1Progress
        val alpha1 = (0.4f * (1.0f - wave1Progress)).coerceIn(0.0f, 0.4f)

        val radius2 = maxRadius * wave2Progress
        val alpha2 = (0.4f * (1.0f - wave2Progress)).coerceIn(0.0f, 0.4f)

        drawCircle(
            color = waveColor.copy(alpha = alpha1),
            radius = radius1,
            center = center,
            style = Stroke(width = 2.dp.toPx()),
        )

        drawCircle(
            color = waveColor.copy(alpha = alpha2),
            radius = radius2,
            center = center,
            style = Stroke(width = 1.5.dp.toPx()),
        )
    }
}
