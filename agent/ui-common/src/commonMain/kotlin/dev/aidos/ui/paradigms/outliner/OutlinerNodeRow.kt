package dev.aidos.ui.paradigms.outliner

import androidx.compose.foundation.background
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
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.tcr.code.StepCounterAtom
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForActor
import dev.aidos.ui.theme.colorForStatus

data class OutlinerNodeUiModel(
    val node: IntentNodeRecord,
    val derivedStatus: DerivedStatus,
    val depth: Int = 0,
    val isExpanded: Boolean = true,
    val hasChildren: Boolean = false,
    val unmetPrerequisites: List<Pair<String, String>> = emptyList(),
)

@Composable
fun OutlinerNodeRow(
    item: OutlinerNodeUiModel,
    onToggleExpand: (String) -> Unit,
    onNodeClick: (String) -> Unit,
    onCausalJump: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val actorColor = colorForActor(item.node.actorKind)
    val statusColor = colorForStatus(item.derivedStatus)
    val indentPadding = (item.depth * 20).dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onNodeClick(item.node.id) }
            .padding(start = indentPadding, end = SpacingTokens.base, top = 4.dp, bottom = 4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            modifier = Modifier.fillMaxWidth()
        ) {
            // Expand / collapse arrow if children exist
            if (item.hasChildren) {
                Box(
                    modifier = Modifier
                        .clickable { onToggleExpand(item.node.id) }
                        .padding(SpacingTokens.xxs)
                ) {
                    Text(
                        text = if (item.isExpanded) "▼" else "▶",
                        style = AidosTypography.baseTypography.labelSmall,
                        color = Color(0xFF94A3B8),
                    )
                }
            } else {
                Box(modifier = Modifier.size(16.dp))
            }

            // Status indicator dot
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .background(color = statusColor, shape = CircleShape)
            )

            // Actor pill indicator
            Box(
                modifier = Modifier
                    .background(actorColor.copy(alpha = 0.2f), shape = RoundedCornerShape(CornerTokens.xs))
                    .padding(horizontal = 4.dp, vertical = 1.dp)
            ) {
                Text(
                    text = item.node.actorKind.name.take(1),
                    style = AidosTypography.baseTypography.labelSmall,
                    color = actorColor,
                )
            }

            // Title
            Text(
                text = item.node.title,
                style = AidosTypography.baseTypography.bodyMedium,
                color = if (item.derivedStatus == DerivedStatus.DONE) Color(0xFF64748B) else Color.White,
                modifier = Modifier.weight(1f),
            )

            // Step fuel meter
            if (item.node.estimatedSteps != null) {
                StepCounterAtom(currentStep = 1, totalSteps = item.node.estimatedSteps!!)
            }
        }

        // Causal Jump Pills if blocked
        if (item.unmetPrerequisites.isNotEmpty()) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
                modifier = Modifier.padding(start = 32.dp, top = 2.dp)
            ) {
                item.unmetPrerequisites.forEach { (prereqId, prereqTitle) ->
                    CausalJumpPill(
                        prerequisiteId = prereqId,
                        prerequisiteTitle = prereqTitle,
                        onJump = onCausalJump,
                    )
                }
            }
        }
    }
}
