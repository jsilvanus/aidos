package dev.aidos.ui.pills

import androidx.compose.ui.graphics.Color
import dev.aidos.ui.theme.ColorTokens

/**
 * Roles and visual identities for the Multi-Agent Pill Overlay (RFC-0106, RFC-0111).
 */
enum class PillRole(
    val displayName: String,
    val color: Color,
    val iconEmoji: String,
) {
    ORCHESTRATOR_RED(
        displayName = "Voice Caret",
        color = ColorTokens.PillOrchestratorRed,
        iconEmoji = "🔴",
    ),
    WORKER_CODE_BLUE(
        displayName = "Code Worker",
        color = ColorTokens.PillWorkerCodeBlue,
        iconEmoji = "🔵",
    ),
    VERIFIER_GREEN(
        displayName = "Verification Agent",
        color = ColorTokens.PillVerifierGreen,
        iconEmoji = "🟢",
    ),
    RESEARCH_VIOLET(
        displayName = "Research Agent",
        color = ColorTokens.PillResearchViolet,
        iconEmoji = "🟣",
    );
}

data class AgentPillState(
    val id: String,
    val role: PillRole,
    val label: String,
    val activeTaskId: String? = null,
    val xNormalized: Float = 0.5f,
    val yNormalized: Float = 0.5f,
    val isRadiatingWave: Boolean = false,
    val ghostText: String = "",
)
