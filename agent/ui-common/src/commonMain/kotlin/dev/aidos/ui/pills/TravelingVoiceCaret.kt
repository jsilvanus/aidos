package dev.aidos.ui.pills

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.ui.tcr.core.AudioWaveformAtom
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

/**
 * Traveling STT Caret Pill (Red Pill) (RFC-0106, RFC-0111).
 * Follows text caret position, provides Whisper waveform visualization, and streams ghost text.
 */
@Composable
fun TravelingVoiceCaret(
    isRecording: Boolean,
    ghostText: String,
    modifier: Modifier = Modifier,
    audioLevels: List<Float> = listOf(0.3f, 0.6f, 0.9f, 0.4f),
    onCommitText: ((String) -> Unit)? = null,
) {
    AnimatedVisibility(
        visible = isRecording || ghostText.isNotEmpty(),
        enter = fadeIn(),
        exit = fadeOut(),
        modifier = modifier,
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .background(
                    color = Color(0xF018181B),
                    shape = RoundedCornerShape(CornerTokens.pill),
                )
                .border(
                    width = 1.dp,
                    color = ColorTokens.PillOrchestratorRed,
                    shape = RoundedCornerShape(CornerTokens.pill),
                )
                .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs)
        ) {
            Text(
                text = "🔴 Dictator",
                style = AidosTypography.baseTypography.labelSmall,
                color = ColorTokens.PillOrchestratorRed,
            )

            if (isRecording) {
                Box(modifier = Modifier.padding(horizontal = SpacingTokens.sm)) {
                    AudioWaveformAtom(
                        amplitudes = audioLevels,
                        barColor = ColorTokens.PillOrchestratorRed,
                    )
                }
            }

            if (ghostText.isNotEmpty()) {
                Text(
                    text = " \"$ghostText\"",
                    style = AidosTypography.codeSnippet,
                    color = Color(0xFFFCA5A5),
                )
            }
        }
    }
}
