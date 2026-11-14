package dev.aidos.ui.paradigms.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
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
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.tcr.code.StepCounterAtom
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForActor
import dev.aidos.ui.theme.colorForStatus

@Composable
fun TaskCartridgeView(
    node: IntentNodeRecord,
    status: DerivedStatus,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val statusColor = colorForStatus(status)
    val actorColor = colorForActor(node.actorKind)

    Box(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E293B), shape = RoundedCornerShape(CornerTokens.sm))
            .border(width = 1.dp, color = Color(0xFF334155), shape = RoundedCornerShape(CornerTokens.sm))
            .clickable(onClick = onClick)
            .padding(SpacingTokens.md)
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // LED Status indicator
                Row(
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        modifier = Modifier
                            .size(6.dp)
                            .background(statusColor, shape = CircleShape)
                    )
                    Text(
                        text = status.name,
                        style = AidosTypography.baseTypography.labelSmall,
                        color = statusColor,
                    )
                }

                // Actor badge
                Box(
                    modifier = Modifier
                        .background(actorColor.copy(alpha = 0.2f), shape = RoundedCornerShape(CornerTokens.xs))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = node.actorKind.name,
                        style = AidosTypography.baseTypography.labelSmall,
                        color = actorColor,
                    )
                }
            }

            Text(
                text = node.title,
                style = AidosTypography.baseTypography.titleSmall,
                color = Color.White,
            )

            if (node.estimatedSteps != null) {
                StepCounterAtom(currentStep = 1, totalSteps = node.estimatedSteps!!)
            }
        }
    }
}
