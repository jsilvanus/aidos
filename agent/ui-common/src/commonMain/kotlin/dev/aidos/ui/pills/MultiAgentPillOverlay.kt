package dev.aidos.ui.pills

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Absolute coordinate overlay canvas hosting all floating agent pills (RFC-0106, RFC-0111).
 * Positioned in an independent Z-index layer via normalized coordinates (u, v) in [0, 1] x [0, 1].
 */
@Composable
fun MultiAgentPillOverlay(
    pills: List<AgentPillState>,
    modifier: Modifier = Modifier,
    onPillClick: ((AgentPillState) -> Unit)? = null,
) {
    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val totalWidth = maxWidth
        val totalHeight = maxHeight

        pills.forEach { pill ->
            val offsetX = totalWidth * pill.xNormalized.coerceIn(0f, 1f)
            val offsetY = totalHeight * pill.yNormalized.coerceIn(0f, 1f)

            Box(
                modifier = Modifier.offset(x = offsetX, y = offsetY)
            ) {
                if (pill.isRadiatingWave) {
                    WaveSignalingCanvas(
                        waveColor = pill.role.color,
                        isActive = true,
                    )
                }

                when (pill.role) {
                    PillRole.ORCHESTRATOR_RED -> {
                        TravelingVoiceCaret(
                            isRecording = pill.isRadiatingWave,
                            ghostText = pill.ghostText,
                        )
                    }
                    PillRole.WORKER_CODE_BLUE,
                    PillRole.VERIFIER_GREEN,
                    PillRole.RESEARCH_VIOLET -> {
                        WorkerPillNode(
                            role = pill.role,
                            label = pill.label,
                            activeTaskTitle = pill.activeTaskId,
                            onClick = { onPillClick?.invoke(pill) },
                        )
                    }
                }
            }
        }
    }
}
