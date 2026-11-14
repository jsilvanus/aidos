package dev.aidos.ui.frontdoors.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.ui.tcr.code.ScopePathChipAtom
import dev.aidos.ui.tcr.code.StepCounterAtom
import dev.aidos.ui.tcr.core.ActionButtonAtom
import dev.aidos.ui.tcr.core.ActionButtonKind
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForStatus

data class ActionItemUiModel(
    val node: IntentNodeRecord,
    val statusView: IntentNodeStatusView,
    val projectName: String,
    val isLifeProject: Boolean = false,
    val batonDirection: BatonTransferDirection = BatonTransferDirection.AGENT_TO_HUMAN,
)

@Composable
fun ActionItemCard(
    item: ActionItemUiModel,
    onComplete: (String) -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = colorForStatus(item.statusView.derivedStatus)
    val accentColor = if (item.isLifeProject) ColorTokens.ActorHuman else ColorTokens.ActorAgent

    Box(
        modifier = modifier
            .fillMaxWidth()
            .defaultMinSize(minHeight = 72.dp)
            .background(Color(0xFF1E293B), shape = RoundedCornerShape(CornerTokens.md))
            .border(width = 1.dp, color = accentColor.copy(alpha = 0.4f), shape = RoundedCornerShape(CornerTokens.md))
            .clickable(onClick = onClick)
            .padding(SpacingTokens.base)
            .semantics {
                contentDescription = "Action item: ${item.node.title}, Status: ${item.statusView.derivedStatus.name}"
            }
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    BatonHandoffBadge(direction = item.batonDirection)

                    Text(
                        text = if (item.isLifeProject) "🛡 [${item.projectName}]" else "💻 [${item.projectName}]",
                        style = AidosTypography.baseTypography.labelSmall,
                        color = Color(0xFF94A3B8),
                    )
                }

                ActionButtonAtom(
                    text = "Complete",
                    onClick = { onComplete(item.node.id) },
                    kind = ActionButtonKind.TACTILE_MICRO,
                )
            }

            Text(
                text = item.node.title,
                style = AidosTypography.baseTypography.titleMedium,
                color = Color.White,
            )

            if (!item.node.description.isNullOrBlank()) {
                Text(
                    text = item.node.description!!,
                    style = AidosTypography.baseTypography.bodySmall,
                    color = Color(0xFFCBD5E1),
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (item.node.estimatedSteps != null) {
                    StepCounterAtom(currentStep = 1, totalSteps = item.node.estimatedSteps!!)
                }

                item.node.scopePaths.take(2).forEach { path ->
                    ScopePathChipAtom(path = path)
                }
            }
        }
    }
}
