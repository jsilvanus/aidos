package dev.aidos.ui.system

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

enum class NativeProcessHealth {
    HEALTHY,
    CRASHED_WARM_RESTARTING,
    RECOVERED,
}

/**
 * Non-blocking recovery banner alerting user when :native_inference warm-restarts (D49, RFC-0111).
 * Isolates native segfaults / LMK sweeps from the UI process.
 */
@Composable
fun NativeProcessRecoveryBar(
    health: NativeProcessHealth,
    modifier: Modifier = Modifier,
) {
    val isVisible = health != NativeProcessHealth.HEALTHY

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        val (dotColor, text) = when (health) {
            NativeProcessHealth.HEALTHY -> ColorTokens.StatusDone to "Engine Ready"
            NativeProcessHealth.CRASHED_WARM_RESTARTING -> ColorTokens.StatusReview to "Native Engine Restarting (LMK Recovered)..."
            NativeProcessHealth.RECOVERED -> ColorTokens.StatusDone to "✔ Engine Reconnected"
        }

        Box(
            modifier = Modifier
                .background(Color(0xF00F172A), shape = RoundedCornerShape(CornerTokens.pill))
                .border(1.dp, dotColor, shape = RoundedCornerShape(CornerTokens.pill))
                .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            ) {
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .background(dotColor, shape = CircleShape)
                )

                Text(
                    text = text,
                    style = AidosTypography.baseTypography.labelSmall,
                    color = Color.White,
                )
            }
        }
    }
}
