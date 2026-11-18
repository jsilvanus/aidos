package dev.aidos.ui.paradigms.pipeline

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.ColorTokens

/**
 * Animated directional line when dependency crosses tracks between Human and Agent (RFC-0111).
 */
@Composable
fun BatonVectorLine(
    isHumanToAgent: Boolean,
    modifier: Modifier = Modifier,
    highlighted: Boolean = false, // RFC-0111: the baton is "in the air" (upstream DONE, downstream not)
) {
    val infiniteTransition = rememberInfiniteTransition(label = "BatonFlow")
    val phase by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 20f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "BatonLinePhase"
    )

    val lineColor = when {
        highlighted -> Color(0xFFFBBF24)
        isHumanToAgent -> ColorTokens.ActorAgent
        else -> ColorTokens.ActorHuman
    }
    val strokeWidth = if (highlighted) 4.dp else 2.dp

    Canvas(modifier = modifier.fillMaxWidth().height(24.dp)) {
        val y = size.height / 2f
        val startX = 0f
        val endX = size.width

        drawLine(
            color = lineColor,
            start = Offset(startX, y),
            end = Offset(endX, y),
            strokeWidth = strokeWidth.toPx(),
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 10f), phase),
        )
    }
}
