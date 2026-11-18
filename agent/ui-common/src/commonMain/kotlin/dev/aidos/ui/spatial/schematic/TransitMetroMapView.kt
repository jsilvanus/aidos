package dev.aidos.ui.spatial.schematic

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aidos.intent.DerivedStatus
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController

private val MetroBackground = Color(0xFF0F172A)
private val MetroCard = Color(0xFF1E293B)
private val MetroCellWidth = 104.dp

/**
 * Design 2: The Transit Metro Map (RFC-0111, RFC-0124 0124.5).
 * Each top-level goal or group is a horizontal rail, stacked vertically and scrollable sideways.
 * Stations show who does the work (solid, ring, half-filled) and a signal dot for their status.
 * Tapping a station opens the shared Node Inspector (RFC-0124 UI-21). On a compact handheld a
 * "Next Stop" strip sits above the map.
 */
@Composable
fun TransitMetroMapView(
    presenter: TransitMetroPresenter,
    onStationClick: (String) -> Unit = {},
    controller: WorkspaceController,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(MetroBackground)
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = "Transit Metro Map",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        state.error?.let { message ->
            Text(
                text = message,
                style = AidosTypography.baseTypography.bodySmall,
                color = ColorTokens.StatusBlocked,
                modifier = Modifier.padding(bottom = SpacingTokens.sm),
            )
        }

        if (state.lines.isEmpty() && !state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No metro lines yet. Add goals or tasks to plot the network.",
                    style = AidosTypography.baseTypography.bodyMedium,
                    color = ColorTokens.StatusStale,
                )
            }
            return@Column
        }

        if (formFactor == FormFactor.HANDHELD_COMPACT) {
            MetroNextStopStrip(
                lines = state.lines,
                controller = controller,
                modifier = Modifier.fillMaxWidth().padding(bottom = SpacingTokens.md),
            )
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
            contentPadding = PaddingValues(vertical = SpacingTokens.xs),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.lines, key = { it.lineId }) { line ->
                MetroLineRail(
                    line = line,
                    lines = state.lines,
                    selectedStationId = state.selectedStationId,
                    onStationSelected = { nodeId ->
                        presenter.send(TransitMetroIntent.SelectStation(nodeId))
                        controller.openInspector(nodeId)
                        onStationClick(nodeId)
                    },
                )
            }
        }
    }
}

@Composable
private fun MetroLineRail(
    line: MetroLineUiModel,
    lines: List<MetroLineUiModel>,
    selectedStationId: String?,
    onStationSelected: (String) -> Unit,
) {
    val lineColor = Color(line.colorHex)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MetroCard, shape = RoundedCornerShape(CornerTokens.md))
            .border(1.dp, lineColor.copy(alpha = 0.5f), shape = RoundedCornerShape(CornerTokens.md))
            .padding(SpacingTokens.md),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            Box(modifier = Modifier.size(12.dp).background(lineColor, CircleShape))
            Text(
                text = line.title,
                style = AidosTypography.baseTypography.titleMedium,
                color = Color.White,
            )
            Text(
                text = "(${line.stations.size} stations)",
                style = AidosTypography.baseTypography.labelSmall,
                color = ColorTokens.StatusStale,
            )
        }

        Row(modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState())) {
            line.stations.forEachIndexed { index, station ->
                val transferTo = TransitMetroPresenter.stationTransfers(station, line.lineId, lines).firstOrNull()
                MetroStationCell(
                    station = station,
                    lineColor = lineColor,
                    isFirst = index == 0,
                    isLast = index == line.stations.lastIndex,
                    isSelected = selectedStationId == station.node.id,
                    transferLabel = transferTo?.let { "Transfer: ${it.title}" },
                    onClick = { onStationSelected(station.node.id) },
                )
            }
        }
    }
}

@Composable
private fun MetroStationCell(
    station: MetroStationUiModel,
    lineColor: Color,
    isFirst: Boolean,
    isLast: Boolean,
    isSelected: Boolean,
    transferLabel: String?,
    onClick: () -> Unit,
) {
    val signal = TransitMetroPresenter.signalHex(station.status)?.let { Color(it) }
    val style = TransitMetroPresenter.stationStyle(station.node)
    Column(
        modifier = Modifier
            .width(MetroCellWidth)
            .background(
                if (isSelected) Color(0xFF334155) else Color.Transparent,
                shape = RoundedCornerShape(CornerTokens.sm),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = SpacingTokens.xs),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(modifier = Modifier.fillMaxWidth().height(32.dp)) {
            val cy = size.height / 2f
            val cx = size.width / 2f
            val radius = if (station.isTransferHub) 12f else 8f
            val center = Offset(cx, cy)

            drawLine(
                color = lineColor,
                start = Offset(if (isFirst) cx else 0f, cy),
                end = Offset(if (isLast) cx else size.width, cy),
                strokeWidth = 6f,
            )
            when (style) {
                MetroActorStyle.SOLID -> drawCircle(color = lineColor, radius = radius, center = center)
                MetroActorStyle.RING -> {
                    drawCircle(color = MetroBackground, radius = radius, center = center)
                    drawCircle(color = lineColor, radius = radius, center = center, style = Stroke(width = 4f))
                }
                MetroActorStyle.HALF_FILLED -> {
                    drawCircle(color = MetroBackground, radius = radius, center = center)
                    drawArc(
                        color = lineColor,
                        startAngle = 90f,
                        sweepAngle = 180f,
                        useCenter = true,
                        topLeft = Offset(cx - radius, cy - radius),
                        size = Size(radius * 2f, radius * 2f),
                    )
                    drawCircle(color = lineColor, radius = radius, center = center, style = Stroke(width = 3f))
                }
            }
            if (station.hasTurnstileGate) {
                val gateX = cx - radius - 6f
                drawLine(
                    color = ColorTokens.StatusReview,
                    start = Offset(gateX, cy - 8f),
                    end = Offset(gateX, cy + 8f),
                    strokeWidth = 3f,
                )
            }
            signal?.let { drawCircle(color = it, radius = 4f, center = Offset(cx + radius + 6f, cy - radius)) }
        }

        Text(
            text = station.node.title,
            style = AidosTypography.baseTypography.labelMedium,
            color = Color.White,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = stationBadge(station),
            style = AidosTypography.baseTypography.labelSmall,
            color = signal ?: ColorTokens.StatusStale,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        transferLabel?.let {
            Text(
                text = it,
                style = AidosTypography.baseTypography.labelSmall,
                color = lineColor,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

private fun stationBadge(station: MetroStationUiModel): String = when {
    station.hasTurnstileGate -> "Turnstile"
    station.isTransferHub -> "Transfer hub"
    station.status == DerivedStatus.DONE -> "Done"
    else -> station.status.name.lowercase().replace('_', ' ')
}
