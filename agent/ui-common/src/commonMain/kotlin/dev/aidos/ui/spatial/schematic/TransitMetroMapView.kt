package dev.aidos.ui.spatial.schematic

import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.components.QuickIntentCreatorBar
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForStatus

/**
 * Design 2: The Transit Metro Map (RFC-0111).
 * Renders color-coded metro lines, transfer hubs, and turnstile verification gates
 * driven by live Intent Graph state from [TransitMetroPresenter].
 */
@Composable
fun TransitMetroMapView(
    presenter: TransitMetroPresenter,
    onStationClick: (String) -> Unit = {},
    onCreateIntent: ((title: String, type: IntentNodeType, actorKind: IntentActorKind) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = "Transit Metro Map (Topology View)",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        if (onCreateIntent != null) {
            QuickIntentCreatorBar(
                onCreateIntent = onCreateIntent,
                modifier = Modifier.padding(bottom = SpacingTokens.sm),
            )
        }

        if (state.lines.isEmpty() && !state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No metro lines yet — add goals or tasks to plot the network.",
                    style = AidosTypography.baseTypography.bodyMedium,
                    color = ColorTokens.StatusStale,
                )
            }
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
            contentPadding = PaddingValues(vertical = SpacingTokens.xs),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.lines, key = { it.lineId }) { line ->
                val lineColor = Color(line.colorHex)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B), shape = RoundedCornerShape(CornerTokens.md))
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

                    Box(modifier = Modifier.fillMaxWidth().height(24.dp), contentAlignment = Alignment.Center) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            drawLine(
                                color = lineColor,
                                start = Offset(12f, size.height / 2f),
                                end = Offset((size.width - 12f).coerceAtLeast(12f), size.height / 2f),
                                strokeWidth = 6f,
                            )
                        }
                    }

                    LazyRow(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                        items(line.stations, key = { it.node.id }) { station ->
                            val isSelected = state.selectedStationId == station.node.id
                            val signalColor = colorForStatus(station.status)
                            Column(
                                modifier = Modifier
                                    .background(
                                        if (isSelected) Color(0xFF334155) else Color(0xFF0F172A),
                                        shape = RoundedCornerShape(CornerTokens.sm),
                                    )
                                    .border(
                                        width = if (station.isTransferHub) 2.dp else 1.dp,
                                        color = if (station.hasTurnstileGate) ColorTokens.StatusReview else signalColor,
                                        shape = RoundedCornerShape(CornerTokens.sm),
                                    )
                                    .clickable {
                                        presenter.send(TransitMetroIntent.SelectStation(station.node.id))
                                        onStationClick(station.node.id)
                                    }
                                    .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                                verticalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                ) {
                                    Box(modifier = Modifier.size(8.dp).background(signalColor, CircleShape))
                                    Text(
                                        text = station.node.title,
                                        style = AidosTypography.baseTypography.labelMedium,
                                        color = Color.White,
                                    )
                                }
                                val badge = when {
                                    station.hasTurnstileGate -> "Turnstile Gate"
                                    station.isTransferHub -> "Transfer Hub"
                                    station.status == DerivedStatus.DONE -> "Cleared"
                                    else -> station.status.name
                                }
                                Text(
                                    text = badge,
                                    style = AidosTypography.baseTypography.labelSmall,
                                    color = signalColor,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
