package dev.aidos.ui.pills

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aidos.tcr.pill.TravelingVoiceCaret as TcrTravelingVoiceCaret

/**
 * Delegates to [dev.aidos.tcr.pill.TravelingVoiceCaret].
 */
@Composable
fun TravelingVoiceCaret(
    isRecording: Boolean,
    ghostText: String,
    modifier: Modifier = Modifier,
    audioLevels: List<Float> = listOf(0.3f, 0.6f, 0.9f, 0.4f),
    alwaysVisible: Boolean = false,
    isActive: Boolean = false,
    idleLabel: String = "🔴 Orchestrator",
    onClick: (() -> Unit)? = null,
    onCommitText: ((String) -> Unit)? = null,
) {
    TcrTravelingVoiceCaret(
        isRecording = isRecording,
        ghostText = ghostText,
        modifier = modifier,
        audioLevels = audioLevels,
        onCommitText = onCommitText,
        alwaysVisible = alwaysVisible,
        isActive = isActive,
        idleLabel = idleLabel,
        onClick = onClick,
    )
}
