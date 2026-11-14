package dev.aidos.ui.tcr.core

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens

/**
 * Dynamic 4-bar whisper visualizer ( ▂▃▅ ) for Dictator STT streaming (RFC-0106).
 */
@Composable
fun AudioWaveformAtom(
    amplitudes: List<Float> = listOf(0.2f, 0.4f, 0.7f, 0.9f),
    barColor: Color = ColorTokens.ActorHuman,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        val bars = if (amplitudes.size >= 4) amplitudes.take(4) else (amplitudes + listOf(0.2f, 0.4f, 0.6f, 0.8f)).take(4)
        bars.forEach { amp ->
            val clamped = amp.coerceIn(0.1f, 1.0f)
            val barHeight = (clamped * 18).dp
            Box(
                modifier = Modifier
                    .width(3.dp)
                    .height(barHeight)
                    .background(barColor, shape = RoundedCornerShape(CornerTokens.xs))
            )
        }
    }
}
