package dev.aidos.ui.pills

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aidos.tcr.pill.AgentPillState as TcrAgentPillState
import dev.aidos.tcr.pill.MultiAgentPillOverlay as TcrMultiAgentPillOverlay
import dev.aidos.tcr.pill.PillRole as TcrPillRole

/**
 * Delegates to [dev.aidos.tcr.pill.MultiAgentPillOverlay].
 */
@Composable
fun MultiAgentPillOverlay(
    pills: List<AgentPillState>,
    modifier: Modifier = Modifier,
    onPillClick: ((AgentPillState) -> Unit)? = null,
) {
    val byId = pills.associateBy { it.id }
    val tcrPills = pills.map { pill ->
        TcrAgentPillState(
            id = pill.id,
            role = when (pill.role) {
                PillRole.ORCHESTRATOR_RED -> TcrPillRole.ORCHESTRATOR_RED
                PillRole.WORKER_CODE_BLUE -> TcrPillRole.WORKER_CODE_BLUE
                PillRole.VERIFIER_GREEN -> TcrPillRole.VERIFIER_GREEN
                PillRole.RESEARCH_VIOLET -> TcrPillRole.RESEARCH_VIOLET
            },
            label = pill.label,
            activeTaskId = pill.activeTaskId,
            xNormalized = pill.xNormalized,
            yNormalized = pill.yNormalized,
            isRadiatingWave = pill.isRadiatingWave,
            ghostText = pill.ghostText,
        )
    }

    TcrMultiAgentPillOverlay(
        pills = tcrPills,
        modifier = modifier,
        onPillClick = if (onPillClick != null) {
            { tcrPill -> byId[tcrPill.id]?.let(onPillClick) }
        } else {
            null
        },
    )
}
