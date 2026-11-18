package dev.aidos.ui.spatial.schematic

import androidx.compose.ui.geometry.Offset
import dev.aidos.intent.DerivedStatus

/** A trace from a prerequisite's output pin to a dependent's input pin, in dp from the board origin. */
data class BreadboardWire(
    val sourceId: String,
    val targetId: String,
    val from: Offset,
    val to: Offset,
    val energized: Boolean,
)

/**
 * Board geometry, in dp, as pure functions so the layout can be tested without Compose.
 * Each column is one dependency depth; chips in a column stack in the order the snapshot lists them.
 */
object BreadboardLayout {
    const val CHIP_W = 176f
    const val CHIP_H = 104f
    const val COLUMN_GAP = 72f
    const val ROW_GAP = 24f
    const val PAD = 16f

    fun slots(chips: List<CircuitChipUiModel>): Map<String, Offset> {
        val rowInColumn = HashMap<Int, Int>()
        val out = LinkedHashMap<String, Offset>()
        for (chip in chips) {
            val row = rowInColumn[chip.column] ?: 0
            rowInColumn[chip.column] = row + 1
            out[chip.node.id] = Offset(
                x = PAD + chip.column * (CHIP_W + COLUMN_GAP),
                y = PAD + row * (CHIP_H + ROW_GAP),
            )
        }
        return out
    }

    /** The full size of the board, so the scroll area can hold every chip. */
    fun extent(slots: Map<String, Offset>): Offset {
        val right = slots.values.maxOfOrNull { it.x } ?: 0f
        val bottom = slots.values.maxOfOrNull { it.y } ?: 0f
        return Offset(right + CHIP_W + PAD, bottom + CHIP_H + PAD)
    }

    /** Input pins sit on the left edge, spread evenly over the chip height. */
    fun inputPinY(index: Int, count: Int): Float = CHIP_H * (index + 1) / (count + 1)

    fun outputPinY(): Float = CHIP_H / 2f

    /** One wire per DEPENDS_ON prerequisite, energized only when that prerequisite is DONE. */
    fun wires(chips: List<CircuitChipUiModel>, slots: Map<String, Offset>): List<BreadboardWire> {
        val statusById: Map<String, DerivedStatus> = chips.associate { it.node.id to it.status }
        val out = mutableListOf<BreadboardWire>()
        for (chip in chips) {
            val target = slots[chip.node.id] ?: continue
            val count = chip.dependencyIds.size
            chip.dependencyIds.forEachIndexed { index, sourceId ->
                val source = slots[sourceId] ?: return@forEachIndexed
                out += BreadboardWire(
                    sourceId = sourceId,
                    targetId = chip.node.id,
                    from = Offset(source.x + CHIP_W, source.y + outputPinY()),
                    to = Offset(target.x, target.y + inputPinY(index, count)),
                    energized = statusById[sourceId] == DerivedStatus.DONE,
                )
            }
        }
        return out
    }
}
