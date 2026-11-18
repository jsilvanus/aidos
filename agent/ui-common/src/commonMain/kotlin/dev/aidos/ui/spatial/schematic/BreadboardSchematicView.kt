package dev.aidos.ui.spatial.schematic

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController

private val EnergizedColor = Color(0xFF10B981)
private val ColdColor = Color(0xFF334155)

/**
 * Design 5: The Logic Circuit Breadboard (RFC-0111), on live data (RFC-0124 UI-41).
 * Columns are dependency depth, left to right. Energized traces (source DONE) run bright and
 * animated; cold traces are dim. On a phone the component strip sits above the board (0124.5).
 * Tapping a chip body opens the Node Inspector; tapping its symbol completes or starts it.
 */
@Composable
fun BreadboardSchematicView(
    presenter: BreadboardSchematicPresenter,
    onChipClick: (String) -> Unit = {},
    controller: WorkspaceController,
    formFactor: FormFactor,
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

        if (formFactor == FormFactor.HANDHELD_COMPACT) {
            BreadboardComponentStrip(
                chips = state.chips,
                selectedChipId = state.selectedChipId,
                onStep = { presenter.send(BreadboardIntent.StepReady(it)) },
                controller = controller,
                modifier = Modifier.fillMaxWidth().padding(bottom = SpacingTokens.md),
            )
        }

        val slots = remember(state.chips) { BreadboardLayout.slots(state.chips) }
        val wires = remember(state.chips) { BreadboardLayout.wires(state.chips, slots) }
        val extent = remember(slots) { BreadboardLayout.extent(slots) }
        val phase = rememberWirePhase()
        val chipsById = remember(state.chips) { state.chips.associateBy { it.node.id } }

        Box(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Box(modifier = Modifier.horizontalScroll(rememberScrollState())) {
                Box(modifier = Modifier.size(extent.x.dp, extent.y.dp)) {
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawWires(wires, phase.value)
                    }
                    state.chips.forEach { chip ->
                        val slot = slots[chip.node.id] ?: return@forEach
                        val litInputs = chip.dependencyIds.map { chipsById[it]?.status == DerivedStatus.DONE }
                        BreadboardChip(
                            chip = chip,
                            slot = slot,
                            litInputs = litInputs,
                            selected = state.selectedChipId == chip.node.id,
                            onBodyClick = {
                                presenter.send(BreadboardIntent.SelectChip(chip.node.id))
                                controller.openInspector(chip.node.id)
                                onChipClick(chip.node.id)
                            },
                            onAction = {
                                when (BreadboardSchematicPresenter.chipAction(chip.node, chip.status)) {
                                    ChipAction.COMPLETE -> controller.completeHumanNode(chip.node.id)
                                    ChipAction.START -> controller.startNodeRun(chip.node.id)
                                    ChipAction.NONE -> Unit
                                }
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadboardChip(
    chip: CircuitChipUiModel,
    slot: Offset,
    litInputs: List<Boolean>,
    selected: Boolean,
    onBodyClick: () -> Unit,
    onAction: () -> Unit,
) {
    val accent = when {
        chip.hasVerificationDiode -> ColorTokens.StatusReview
        chip.status == DerivedStatus.DONE -> EnergizedColor
        else -> ColdColor
    }
    val action = BreadboardSchematicPresenter.chipAction(chip.node, chip.status)
    val componentLabel = when (chip.node.actorKind) {
        IntentActorKind.HUMAN -> "Toggle switch"
        IntentActorKind.AGENT -> "Silicon IC"
        IntentActorKind.HYBRID -> "Relay"
    }
    val inputCount = chip.dependencyIds.size
    val pinLit = { lit: Boolean -> if (lit) EnergizedColor else ColdColor }
    val shape = RoundedCornerShape(CornerTokens.sm)

    Box(
        modifier = Modifier
            .offset(x = slot.x.dp, y = slot.y.dp)
            .size(BreadboardLayout.CHIP_W.dp, BreadboardLayout.CHIP_H.dp)
            .background(if (selected) Color(0xFF065F46) else Color(0xFF064E3B), shape = shape)
            .border(if (selected) 2.dp else 1.5.dp, accent, shape = shape)
            .clickable(onClick = onBodyClick)
            .padding(SpacingTokens.sm),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = chip.node.title,
                style = AidosTypography.baseTypography.titleSmall,
                color = Color.White,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "$componentLabel · ${chip.status.name.lowercase().replace('_', ' ')}",
                style = AidosTypography.baseTypography.labelSmall,
                color = Color(0xFFA7F3D0),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                BreadboardActorGlyph(
                    actor = chip.node.actorKind,
                    dispatchPolicy = chip.node.dispatchPolicy,
                    isOn = chip.status == DerivedStatus.DONE,
                    enabled = action != ChipAction.NONE,
                    onClick = onAction,
                )
                if (chip.hasVerificationDiode) {
                    BreadboardDiodeGate(color = ColorTokens.StatusReview)
                    Text(text = "DIODE GATE", style = AidosTypography.baseTypography.labelSmall, color = ColorTokens.StatusReview)
                }
            }
        }

        // Input pins on the left, one per DEPENDS_ON prerequisite.
        litInputs.forEachIndexed { index, lit ->
            val y = BreadboardLayout.inputPinY(index, inputCount) - 5f
            Box(
                modifier = Modifier
                    .offset(x = (-5f).dp, y = y.dp)
                    .size(10.dp)
                    .background(pinLit(lit), shape = CircleShape),
            )
        }
        // Output pin on the right.
        Box(
            modifier = Modifier
                .offset(x = (BreadboardLayout.CHIP_W - 5f).dp, y = (BreadboardLayout.outputPinY() - 5f).dp)
                .size(10.dp)
                .background(pinLit(chip.status == DerivedStatus.DONE), shape = CircleShape),
        )
        // Top pins for CONSTRAINS, bottom probes for ACCEPTS.
        repeat(chip.constraintCount) { k ->
            Box(
                modifier = Modifier
                    .offset(x = (BreadboardLayout.CHIP_W * (k + 1) / (chip.constraintCount + 1) - 3f).dp, y = (-3f).dp)
                    .size(6.dp)
                    .background(ColorTokens.StatusStale, shape = CircleShape),
            )
        }
        repeat(chip.acceptsCount) { k ->
            Box(
                modifier = Modifier
                    .offset(x = (BreadboardLayout.CHIP_W * (k + 1) / (chip.acceptsCount + 1) - 3f).dp, y = (BreadboardLayout.CHIP_H - 3f).dp)
                    .size(6.dp)
                    .background(ColorTokens.StatusStale, shape = CircleShape),
            )
        }
    }
}

/** One cycle of dash offset per 900 ms, so energized traces appear to carry current. */
@Composable
private fun rememberWirePhase(): State<Float> {
    val transition = rememberInfiniteTransition(label = "breadboard-wires")
    return transition.animateFloat(
        initialValue = 0f,
        targetValue = 24f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 900, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "breadboard-phase",
    )
}

private fun DrawScope.drawWires(wires: List<BreadboardWire>, phase: Float) {
    val current = PathEffect.dashPathEffect(floatArrayOf(12f, 12f), phase)
    for (wire in wires) {
        val from = Offset(wire.from.x.dp.toPx(), wire.from.y.dp.toPx())
        val to = Offset(wire.to.x.dp.toPx(), wire.to.y.dp.toPx())
        val bend = (to.x - from.x) / 2f
        val path = Path().apply {
            moveTo(from.x, from.y)
            cubicTo(from.x + bend, from.y, to.x - bend, to.y, to.x, to.y)
        }
        if (wire.energized) {
            drawPath(path, color = EnergizedColor, style = Stroke(width = 3.dp.toPx(), pathEffect = current))
        } else {
            drawPath(path, color = ColdColor, style = Stroke(width = 1.5.dp.toPx()))
        }
    }
}
