package dev.aidos.ui.paradigms.pipeline

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentGranularity
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController

/**
 * Relay River (RFC-0111 Design 1, UI-38). Wide layouts show Human and Agent lanes with the Handoff
 * channel between them, one row per dependency depth. Compact layouts show one stream ordered by
 * depth with a pinned Current baton card in the thumb zone.
 */
@Composable
fun RelayPipelineScreen(
    presenter: RelayPipelinePresenter,
    controller: WorkspaceController,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = "Relay River",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.sm),
        )
        state.error?.let {
            Text(text = it, style = AidosTypography.baseTypography.bodySmall, color = ColorTokens.StatusBlocked)
        }

        if (state.items.isEmpty()) {
            Text(
                text = if (state.isLoading) "Loading the river…" else "No work on the river yet.",
                style = AidosTypography.baseTypography.bodyMedium,
                color = Color(0xFFCBD5E1),
            )
        } else if (formFactor == FormFactor.HANDHELD_COMPACT) {
            CompactRiver(state, controller)
        } else {
            WideRiver(state, controller)
        }
    }
}

@Composable
private fun WideRiver(state: RelayPipelineUiState, controller: WorkspaceController) {
    val rows = state.items.groupBy { it.depth }.toSortedMap()
    val batonDownstream = state.batons.associateBy { it.downstreamId }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
            LaneTitle("👤 Human", ColorTokens.ActorHuman, Modifier.weight(1f))
            LaneTitle("🤝 Handoff channel", ColorTokens.ActorHybrid, Modifier.weight(1f))
            LaneTitle("🤖 Agent", ColorTokens.ActorAgent, Modifier.weight(1f))
        }
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            contentPadding = PaddingValues(vertical = SpacingTokens.sm),
            modifier = Modifier.fillMaxSize(),
        ) {
            rows.forEach { (depth, items) ->
                item(key = "depth-$depth") {
                    Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
                        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                            listOf(RiverLane.HUMAN, RiverLane.CHANNEL, RiverLane.AGENT).forEach { lane ->
                                Column(
                                    modifier = Modifier.weight(1f),
                                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
                                ) {
                                    items.filter { it.lane == lane }.forEach { Stone(it, controller) }
                                }
                            }
                        }
                        // A baton crosses into this row when one of its downstream nodes sits here.
                        val crossing = items.mapNotNull { batonDownstream[it.node.id] }
                        if (crossing.isNotEmpty()) {
                            val highlighted = crossing.any { it.inTheAir }
                            BatonVectorLine(
                                isHumanToAgent = crossing.first().upstreamLane == RiverLane.AGENT,
                                highlighted = highlighted,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactRiver(state: RelayPipelineUiState, controller: WorkspaceController) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
            modifier = Modifier.weight(1f),
        ) {
            items(state.items, key = { it.node.id }) { item ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                    Text(
                        text = laneTag(item.lane),
                        style = AidosTypography.baseTypography.labelSmall,
                        color = laneColor(item.lane),
                        modifier = Modifier.width(72.dp),
                    )
                    Stone(item, controller, Modifier.weight(1f))
                }
            }
        }
        CurrentBatonCard(state.currentBaton, controller)
    }
}

@Composable
private fun CurrentBatonCard(baton: CurrentBaton?, controller: WorkspaceController) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(ColorTokens.SurfaceCardDark, shape = RoundedCornerShape(CornerTokens.md))
            .border(1.dp, ColorTokens.StatusInProgress, shape = RoundedCornerShape(CornerTokens.md))
            .padding(SpacingTokens.base),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
    ) {
        Text(text = "Current baton", style = AidosTypography.baseTypography.labelMedium, color = ColorTokens.ActorHuman)
        if (baton == null) {
            Text(
                text = "Nothing is waiting on you and no agent task is in flight.",
                style = AidosTypography.baseTypography.bodySmall,
                color = Color(0xFFCBD5E1),
            )
            return@Column
        }
        Text(
            text = baton.node.title,
            style = AidosTypography.baseTypography.titleSmall,
            color = Color.White,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = when (baton.kind) {
                BatonKind.HANDED_OVER -> "An agent handed this to you."
                BatonKind.AGENT_IN_FLIGHT -> "Agent task in flight."
            },
            style = AidosTypography.baseTypography.bodySmall,
            color = Color(0xFFCBD5E1),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
            if (baton.kind == BatonKind.HANDED_OVER) {
                Button(onClick = { controller.completeHumanNode(baton.node.id) }) { Text("Complete") }
            }
            OutlinedButton(onClick = { controller.openInspector(baton.node.id) }) { Text("Open") }
        }
    }
}

@Composable
private fun LaneTitle(text: String, color: Color, modifier: Modifier) {
    Text(text = text, style = AidosTypography.baseTypography.labelMedium, color = color, modifier = modifier)
}

/** A node "stone": compact for ATOMIC tasks, taller for GROUP and GOAL. */
@Composable
private fun Stone(item: RiverItem, controller: WorkspaceController, modifier: Modifier = Modifier) {
    val wide = item.node.granularity == IntentGranularity.GROUP || item.node.granularity == IntentGranularity.GOAL
    val stoneHeight = if (wide) 56.dp else 36.dp
    Row(modifier = modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        if (item.constrained) {
            // CONSTRAINS: a thin rail beside the constrained node (RFC-0111).
            Box(modifier = Modifier.width(3.dp).height(stoneHeight).background(Color(0xFF94A3B8)))
            Box(modifier = Modifier.width(4.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .heightIn(min = stoneHeight)
                .background(ColorTokens.SurfaceCardDark, shape = RoundedCornerShape(CornerTokens.md))
                .border(2.dp, statusColor(item.status), shape = RoundedCornerShape(CornerTokens.md))
                .clickable { controller.openInspector(item.node.id) }
                .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
            verticalArrangement = Arrangement.Center,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = item.node.title,
                    style = AidosTypography.baseTypography.bodySmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (item.accepted) {
                    // ACCEPTS: the task is locked to its acceptance criteria.
                    Text(text = "🔒", style = AidosTypography.baseTypography.labelSmall)
                }
            }
            if (wide) {
                Text(
                    text = item.status?.name?.replace('_', ' ') ?: "NOT STARTED",
                    style = AidosTypography.baseTypography.labelSmall,
                    color = statusColor(item.status),
                )
            }
        }
    }
}

private fun laneTag(lane: RiverLane): String = when (lane) {
    RiverLane.HUMAN -> "👤 Human"
    RiverLane.CHANNEL -> "🤝 Handoff"
    RiverLane.AGENT -> "🤖 Agent"
}

private fun laneColor(lane: RiverLane): Color = when (lane) {
    RiverLane.HUMAN -> ColorTokens.ActorHuman
    RiverLane.CHANNEL -> ColorTokens.ActorHybrid
    RiverLane.AGENT -> ColorTokens.ActorAgent
}

private fun statusColor(status: DerivedStatus?): Color = when (status) {
    DerivedStatus.DONE -> ColorTokens.StatusDone
    DerivedStatus.IN_PROGRESS -> ColorTokens.StatusInProgress
    DerivedStatus.BLOCKED -> ColorTokens.StatusBlocked
    DerivedStatus.NEEDS_REVIEW -> ColorTokens.StatusReview
    DerivedStatus.STALE -> ColorTokens.StatusStale
    DerivedStatus.CONFLICTED -> ColorTokens.StatusConflicted
    DerivedStatus.NOT_STARTED, null -> ColorTokens.StatusNotStarted
}
