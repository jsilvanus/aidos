package dev.aidos.ui.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.ui.pills.TravelingVoiceCaret
import dev.aidos.ui.tcr.core.ActionButtonAtom
import dev.aidos.ui.tcr.core.ActionButtonKind
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

/**
 * Android System Assistant Floating Voice Sheet (RFC-0111).
 * Invoked system-wide via power button or corner swipe. Proposes atomic tasks to personal Life project.
 */
@Composable
fun AssistantSheetOverlay(
    isOpen: Boolean,
    isListening: Boolean,
    streamedGhostText: String,
    onProposeTask: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = isOpen,
        enter = slideInVertically(initialOffsetY = { it }),
        exit = slideOutVertically(targetOffsetY = { it }),
        modifier = modifier.fillMaxSize(),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0x66000000))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0F172A), shape = RoundedCornerShape(topStart = CornerTokens.lg, topEnd = CornerTokens.lg))
                    .border(1.dp, ColorTokens.PillOrchestratorRed, shape = RoundedCornerShape(topStart = CornerTokens.lg, topEnd = CornerTokens.lg))
                    .clickable(enabled = false) {}
                    .padding(SpacingTokens.base)
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "🔴 Aidos System Assistant",
                            style = AidosTypography.baseTypography.titleMedium,
                            color = ColorTokens.PillOrchestratorRed,
                        )

                        ActionButtonAtom(
                            text = "Dismiss",
                            onClick = onDismiss,
                            kind = ActionButtonKind.TACTILE_MICRO,
                        )
                    }

                    TravelingVoiceCaret(
                        isRecording = isListening,
                        ghostText = streamedGhostText,
                    )

                    if (streamedGhostText.isNotBlank()) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            ActionButtonAtom(
                                text = "Propose Task to Life Graph",
                                onClick = {
                                    onProposeTask(streamedGhostText)
                                    onDismiss()
                                },
                                kind = ActionButtonKind.PRIMARY,
                            )
                        }
                    }
                }
            }
        }
    }
}
