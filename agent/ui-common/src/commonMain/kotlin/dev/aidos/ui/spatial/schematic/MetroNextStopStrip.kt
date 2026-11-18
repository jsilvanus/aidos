package dev.aidos.ui.spatial.schematic

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.intent.IntentActorKind
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController

/**
 * The phone form of the Metro Map (RFC-0124 0124.5): a line picker, the station the train is at,
 * the next three stations, and a transfer warning when the next station waits on another line.
 * Complete (HUMAN) and Start (AGENT) act on the current station; HYBRID gets both.
 * All decisions about what is current or next live in [TransitMetroPresenter].
 */
@Composable
fun MetroNextStopStrip(
    lines: List<MetroLineUiModel>,
    controller: WorkspaceController,
    modifier: Modifier = Modifier,
) {
    var pickedLineId by remember { mutableStateOf<String?>(null) }
    val line = lines.firstOrNull { it.lineId == pickedLineId } ?: lines.firstOrNull() ?: return

    val current = TransitMetroPresenter.currentStation(line)
    val upcoming = TransitMetroPresenter.upcomingStations(line, current, count = 3)
    val waitingOn = upcoming.firstOrNull()
        ?.let { TransitMetroPresenter.stationTransfers(it, line.lineId, lines).firstOrNull() }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF1E293B), shape = RoundedCornerShape(CornerTokens.md))
            .padding(SpacingTokens.md),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        Text(
            text = "Next Stop",
            style = AidosTypography.baseTypography.labelSmall,
            color = ColorTokens.StatusStale,
        )

        Row(
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            lines.forEach { option ->
                val selected = option.lineId == line.lineId
                Text(
                    text = option.title,
                    style = AidosTypography.baseTypography.labelMedium,
                    color = if (selected) Color(0xFF0F172A) else Color.White,
                    modifier = Modifier
                        .background(
                            if (selected) Color(option.colorHex) else Color(0xFF0F172A),
                            shape = RoundedCornerShape(CornerTokens.sm),
                        )
                        .clickable { pickedLineId = option.lineId }
                        .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                )
            }
        }

        if (current == null) {
            Text(
                text = "Every stop on ${line.title} is done.",
                style = AidosTypography.baseTypography.bodyMedium,
                color = Color.White,
            )
            return@Column
        }

        Text(
            text = current.node.title,
            style = AidosTypography.baseTypography.titleMedium,
            color = Color.White,
        )
        Text(
            text = "${current.status.name.lowercase().replace('_', ' ')} · ${current.node.actorKind.name.lowercase()}",
            style = AidosTypography.baseTypography.labelSmall,
            color = TransitMetroPresenter.signalHex(current.status)?.let { Color(it) } ?: ColorTokens.StatusStale,
        )

        val actor = current.node.actorKind
        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
            if (actor == IntentActorKind.HUMAN || actor == IntentActorKind.HYBRID) {
                Button(onClick = { controller.completeHumanNode(current.node.id) }) { Text("Complete") }
            }
            if (actor == IntentActorKind.AGENT || actor == IntentActorKind.HYBRID) {
                Button(onClick = { controller.startNodeRun(current.node.id) }) { Text("Start") }
            }
        }

        upcoming.forEach { station ->
            Text(
                text = "→ ${station.node.title}",
                style = AidosTypography.baseTypography.bodySmall,
                color = Color.White,
            )
        }

        waitingOn?.let {
            Text(
                text = "Transfer needed: ${it.title}",
                style = AidosTypography.baseTypography.labelMedium,
                color = ColorTokens.StatusReview,
            )
        }
    }
}
