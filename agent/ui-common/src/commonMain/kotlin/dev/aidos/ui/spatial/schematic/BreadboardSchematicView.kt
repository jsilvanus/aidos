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
import dev.aidos.intent.IntentActorKind
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens

/**
 * Design 5: The Logic Circuit Breadboard (RFC-0111).
 * Renders IC chips, energized/cold conductive traces, and verification diode gates
 * driven by live Intent Graph state from [BreadboardSchematicPresenter].
 */
@Composable
fun BreadboardSchematicView(
    presenter: BreadboardSchematicPresenter,
    onChipClick: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF022C22))
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = "Logic Circuit Breadboard (PCB Schematic)",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color(0xFF6EE7B7),
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        if (state.chips.isEmpty() && !state.isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No IC chips on board — add Intent Graph nodes to energize traces.",
                    style = AidosTypography.baseTypography.bodyMedium,
                    color = Color(0xFFA7F3D0),
                )
            }
            return@Column
        }

        LazyColumn(
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
            contentPadding = PaddingValues(vertical = SpacingTokens.xs),
            modifier = Modifier.fillMaxSize(),
        ) {
            items(state.chips, key = { it.node.id }) { chip ->
                val traceColor = when {
                    chip.hasVerificationDiode -> ColorTokens.StatusReview
                    chip.isEnergized -> Color(0xFF10B981)
                    else -> Color(0xFF334155)
                }
                val componentLabel = when (chip.node.actorKind) {
                    IntentActorKind.HUMAN -> "Toggle Switch"
                    IntentActorKind.AGENT -> "Silicon IC"
                    IntentActorKind.HYBRID -> "Solenoid Relay"
                }

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(
                            if (state.selectedChipId == chip.node.id) Color(0xFF065F46) else Color(0xFF064E3B),
                            shape = RoundedCornerShape(CornerTokens.sm),
                        )
                        .border(1.5.dp, traceColor, shape = RoundedCornerShape(CornerTokens.sm))
                        .clickable {
                            presenter.send(BreadboardIntent.SelectChip(chip.node.id))
                            onChipClick(chip.node.id)
                        }
                        .padding(SpacingTokens.md),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                ) {
                    Canvas(modifier = Modifier.size(width = 32.dp, height = 24.dp)) {
                        drawLine(
                            color = traceColor,
                            start = Offset(0f, size.height / 2f),
                            end = Offset(size.width, size.height / 2f),
                            strokeWidth = if (chip.isEnergized) 4f else 2f,
                        )
                    }

                    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            text = chip.node.title,
                            style = AidosTypography.baseTypography.titleSmall,
                            color = Color.White,
                        )
                        Text(
                            text = "$componentLabel • ${chip.status.name} • ${chip.dependencyIds.size} input pins",
                            style = AidosTypography.baseTypography.labelSmall,
                            color = Color(0xFFA7F3D0),
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        if (chip.hasVerificationDiode) {
                            Text(
                                text = "DIODE GATE",
                                style = AidosTypography.baseTypography.labelSmall,
                                color = ColorTokens.StatusReview,
                            )
                        }
                        Box(
                            modifier = Modifier
                                .size(10.dp)
                                .background(traceColor, shape = CircleShape)
                        )
                    }
                }
            }
        }
    }
}
