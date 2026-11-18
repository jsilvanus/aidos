package dev.aidos.ui.spatial.schematic

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.intent.DerivedStatus
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController

/**
 * The phone form of the Breadboard (RFC-0124 0124.5): one chip at a time, its input leads above,
 * the action in the middle and its output leads below. Arrows step between the ready chips.
 * Which chip is shown and what it can do live in [BreadboardSchematicPresenter].
 */
@Composable
fun BreadboardComponentStrip(
    chips: List<CircuitChipUiModel>,
    selectedChipId: String?,
    onStep: (Int) -> Unit,
    controller: WorkspaceController,
    modifier: Modifier = Modifier,
) {
    val chip = BreadboardSchematicPresenter.stripChip(chips, selectedChipId) ?: return
    val ready = BreadboardSchematicPresenter.readyChips(chips)
    val byId = chips.associateBy { it.node.id }
    val inputs = chip.dependencyIds.mapNotNull { byId[it] }
    val outputs = chips.filter { chip.node.id in it.dependencyIds }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFF064E3B), shape = RoundedCornerShape(CornerTokens.md))
            .padding(SpacingTokens.md),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TextButton(onClick = { onStep(-1) }, enabled = ready.size > 1) { Text("‹") }
            Text(
                text = if (ready.isEmpty()) "Nothing ready" else "${ready.size} ready",
                style = AidosTypography.baseTypography.labelSmall,
                color = Color(0xFFA7F3D0),
            )
            TextButton(onClick = { onStep(1) }, enabled = ready.size > 1) { Text("›") }
        }

        inputs.forEach { input ->
            Text(
                text = (if (input.status == DerivedStatus.DONE) "● " else "○ ") + input.node.title,
                style = AidosTypography.baseTypography.labelMedium,
                color = if (input.status == DerivedStatus.DONE) ColorTokens.StatusDone else ColorTokens.StatusStale,
            )
        }
        if (inputs.isEmpty()) {
            Text(text = "No inputs", style = AidosTypography.baseTypography.labelSmall, color = ColorTokens.StatusStale)
        }

        Text(
            text = chip.node.title,
            style = AidosTypography.baseTypography.titleMedium,
            color = Color.White,
        )
        Text(
            text = "${chip.status.name.lowercase().replace('_', ' ')} · ${chip.node.actorKind.name.lowercase()}",
            style = AidosTypography.baseTypography.labelSmall,
            color = if (chip.hasVerificationDiode) ColorTokens.StatusReview else Color(0xFFA7F3D0),
        )
        if (chip.hasVerificationDiode) {
            Text(
                text = "Diode gate: confirm the review to let current through",
                style = AidosTypography.baseTypography.labelSmall,
                color = ColorTokens.StatusReview,
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
            if (BreadboardSchematicPresenter.completeEnabled(chip.node, chip.status)) {
                Button(onClick = { controller.completeHumanNode(chip.node.id) }) { Text("Complete") }
            }
            if (BreadboardSchematicPresenter.startEnabled(chip.node, chip.status)) {
                Button(onClick = { controller.startNodeRun(chip.node.id) }) { Text("Start") }
            }
        }

        outputs.forEach { output ->
            Text(
                text = "→ " + output.node.title,
                style = AidosTypography.baseTypography.bodySmall,
                color = Color.White,
            )
        }
        if (outputs.isEmpty()) {
            Text(text = "No dependents", style = AidosTypography.baseTypography.labelSmall, color = ColorTokens.StatusStale)
        }
    }
}
