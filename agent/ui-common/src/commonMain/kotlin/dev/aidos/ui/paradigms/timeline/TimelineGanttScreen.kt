package dev.aidos.ui.paradigms.timeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForStatus

/**
 * Timeline / Gantt View Paradigm (RFC-0112 §2.1).
 * Renders a vertical chrono-list on HANDHELD_COMPACT and a horizontal Gantt plane
 * on HANDHELD_EXPANDED / DESKTOP_DOCKED.
 */
@Composable
fun TimelineGanttScreen(
    presenter: TimelineGanttPresenter,
    formFactor: FormFactor = FormFactor.DESKTOP_DOCKED,
    onNodeClick: (IntentNodeRecord) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()
    val isCompact = formFactor == FormFactor.HANDHELD_COMPACT

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = if (isCompact) "Timeline (Chrono-List)" else "Timeline / Gantt Plane",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        if (state.bars.isEmpty() && !state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No scheduled tasks in this project.",
                    style = AidosTypography.baseTypography.bodyMedium,
                    color = ColorTokens.StatusStale,
                )
            }
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            contentPadding = PaddingValues(vertical = SpacingTokens.xs),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.bars, key = { it.node.id }) { bar ->
                val statusColor = colorForStatus(bar.status)
                val isSelected = state.selectedNodeId == bar.node.id
                if (isCompact) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B), shape = RoundedCornerShape(CornerTokens.sm))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = statusColor,
                                shape = RoundedCornerShape(CornerTokens.sm),
                            )
                            .clickable {
                                presenter.send(TimelineGanttIntent.SelectNode(bar.node.id))
                                onNodeClick(bar.node)
                            }
                            .padding(SpacingTokens.md),
                    ) {
                        Text(
                            text = "Phase ${bar.startSlot + 1} • ${bar.node.title}",
                            style = AidosTypography.baseTypography.titleSmall,
                            color = Color.White,
                        )
                        Text(
                            text = "${bar.status.name} • ${bar.durationSlots} step(s) • ${bar.dependencyCount} prereq(s)",
                            style = AidosTypography.baseTypography.labelSmall,
                            color = statusColor,
                        )
                    }
                } else {
                    val total = state.totalSlots.toFloat().coerceAtLeast(1f)
                    val offsetWeight = (bar.startSlot / total).coerceIn(0f, 0.85f)
                    val barWeight = (bar.durationSlots / total).coerceIn(0.1f, 1f - offsetWeight)
                    val trailingWeight = (1f - offsetWeight - barWeight).coerceAtLeast(0f)

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF0F172A), shape = RoundedCornerShape(CornerTokens.sm))
                            .clickable {
                                presenter.send(TimelineGanttIntent.SelectNode(bar.node.id))
                                onNodeClick(bar.node)
                            }
                            .padding(SpacingTokens.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = bar.node.title,
                            style = AidosTypography.baseTypography.labelMedium,
                            color = Color.White,
                            modifier = Modifier.width(180.dp),
                        )
                        Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                            if (offsetWeight > 0f) Spacer(modifier = Modifier.weight(offsetWeight))
                            Box(
                                modifier = Modifier
                                    .weight(barWeight)
                                    .height(24.dp)
                                    .background(statusColor.copy(alpha = 0.35f), shape = RoundedCornerShape(CornerTokens.xs))
                                    .border(1.dp, statusColor, shape = RoundedCornerShape(CornerTokens.xs))
                                    .padding(horizontal = SpacingTokens.xs),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(
                                    text = "T+${bar.startSlot} (${bar.durationSlots}s)",
                                    style = AidosTypography.baseTypography.labelSmall,
                                    color = Color.White,
                                )
                            }
                            if (trailingWeight > 0f) Spacer(modifier = Modifier.weight(trailingWeight))
                        }
                    }
                }
            }
        }
    }
}
