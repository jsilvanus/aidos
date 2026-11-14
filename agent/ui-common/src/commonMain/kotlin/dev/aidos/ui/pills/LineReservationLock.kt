package dev.aidos.ui.pills

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens

/**
 * Living document line reservation wash with animated gutter shimmer (RFC-0106).
 * Tints range with 0x1F3B82F6 and renders an animated 2dp pulsing left-gutter bar.
 */
@Composable
fun LineReservationLock(
    isLocked: Boolean,
    modifier: Modifier = Modifier,
    lockOwnerRole: PillRole = PillRole.WORKER_CODE_BLUE,
    content: @Composable () -> Unit,
) {
    if (!isLocked) {
        content()
        return
    }

    val infiniteTransition = rememberInfiniteTransition(label = "GutterPulse")
    val alphaPulse by infiniteTransition.animateFloat(
        initialValue = 0.4f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 800),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "GutterAlpha"
    )

    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(ColorTokens.BlueWashReservation)
    ) {
        // 2dp pulsing left-gutter bar
        Box(
            modifier = Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(lockOwnerRole.color.copy(alpha = alphaPulse))
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xxs)
        ) {
            content()
        }
    }
}
