package dev.aidos.ui.paradigms.outliner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.tcr.code.ScopePathChipAtom
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
    /** Agent runs in progress on this node (IntentNodeStatusView.activeRunIds). */
    val activeRuns: Int = 0,
    /** ACCEPTANCE_CRITERION children, drawn as a checklist under the row. */
    val criteria: List<IntentNodeRecord> = emptyList(),
)

/** Whether the person can complete the node by hand (HUMAN, or HYBRID which is also human-driven). */
internal fun canCompleteByHand(actor: IntentActorKind): Boolean =
    actor == IntentActorKind.HUMAN || actor == IntentActorKind.HYBRID

/** Whether an agent can be started on the node (AGENT, or HYBRID). */
internal fun canStartByAgent(actor: IntentActorKind): Boolean =
    actor == IntentActorKind.AGENT || actor == IntentActorKind.HYBRID

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun OutlinerNodeRow(
    item: OutlinerNodeUiModel,
    isSelected: Boolean,
    onToggleExpand: (String) -> Unit,
    onNodeClick: (String) -> Unit,
    onCausalJump: (String) -> Unit,
    onAddChild: (String) -> Unit,
    onComplete: (String) -> Unit,
    onStart: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val node = item.node
    val actorColor = colorForActor(node.actorKind)
    val statusColor = colorForStatus(item.derivedStatus)
    val isDone = item.derivedStatus == DerivedStatus.DONE
    var menuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(if (isSelected) Color(0x2260A5FA) else Color.Transparent)
            .padding(start = (item.depth * 20).dp, end = SpacingTokens.base, top = 4.dp, bottom = 4.dp)
    ) {
        Box {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = { onNodeClick(node.id) },
                        onLongClick = { menuOpen = true },
                    ),
            ) {
                if (item.hasChildren) {
                    Box(modifier = Modifier.clickable(onClick = { onToggleExpand(node.id) }).padding(SpacingTokens.xxs)) {
                        Text(
                            text = if (item.isExpanded) "▼" else "▶",
                            style = AidosTypography.baseTypography.labelSmall,
                            color = Color(0xFF94A3B8),
                        )
                    }
                } else {
                    Box(modifier = Modifier.size(16.dp))
                }

                Box(modifier = Modifier.size(8.dp).background(color = statusColor, shape = CircleShape))

                Box(
                    modifier = Modifier
                        .background(actorColor.copy(alpha = 0.2f), shape = RoundedCornerShape(CornerTokens.xs))
                        .padding(horizontal = 4.dp, vertical = 1.dp)
                ) {
                    Text(
                        text = node.actorKind.name.take(1),
                        style = AidosTypography.baseTypography.labelSmall,
                        color = actorColor,
                    )
                }

                Text(
                    text = node.title,
                    style = AidosTypography.baseTypography.bodyMedium,
                    color = if (isDone) Color(0xFF64748B) else Color.White,
                    modifier = Modifier.weight(1f),
                )

                if (item.activeRuns > 0) {
                    CircularProgressIndicator(modifier = Modifier.size(12.dp), strokeWidth = 2.dp, color = ColorTokens.ActorAgent)
                    Text("Running", style = AidosTypography.baseTypography.labelSmall, color = ColorTokens.ActorAgent)
                }

                ScopeChips(node.scopePaths)

                if (!isDone && item.activeRuns == 0 && canCompleteByHand(node.actorKind)) {
                    ActionText("Complete") { onComplete(node.id) }
                }
                if (!isDone && item.activeRuns == 0 && canStartByAgent(node.actorKind)) {
                    ActionText("Start") { onStart(node.id) }
                }
                if (isSelected) {
                    ActionText("+") { onAddChild(node.id) }
                }
            }

            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(
                    text = { Text("Add child") },
                    onClick = {
                        menuOpen = false
                        onAddChild(node.id)
                    },
                )
            }
        }

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

        item.criteria.forEach { criterion ->
            OutlinerCriterionRow(criterion = criterion, modifier = Modifier.padding(start = 32.dp, top = 2.dp))
        }
    }
}

/** First two scope paths as chips, then "+N" for the rest (RFC-0111 Design 6). */
@Composable
private fun ScopeChips(paths: List<String>) {
    if (paths.isEmpty()) return
    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xxs)) {
        paths.take(2).forEach { ScopePathChipAtom(path = it) }
        if (paths.size > 2) {
            Text(
                text = "+${paths.size - 2}",
                style = AidosTypography.baseTypography.labelSmall,
                color = Color(0xFF94A3B8),
            )
        }
    }
}

@Composable
private fun ActionText(label: String, onClick: () -> Unit) {
    Box(modifier = Modifier.clickable(onClick = onClick).padding(horizontal = SpacingTokens.xs, vertical = SpacingTokens.xxs)) {
        Text(text = label, style = AidosTypography.baseTypography.labelSmall, color = Color(0xFF7DD3FC))
    }
}
