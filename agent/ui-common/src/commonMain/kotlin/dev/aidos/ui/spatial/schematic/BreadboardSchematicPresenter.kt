package dev.aidos.ui.spatial.schematic

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What a chip's action glyph does when tapped (RFC-0111 Design 5, RFC-0124 U10). */
enum class ChipAction { COMPLETE, START, NONE }

/**
 * One IC chip on the board. [column] is its dependency depth (RFC-0111 Design 5: power flows left
 * to right). [constraintCount] and [acceptsCount] are the top pins and bottom probes.
 */
data class CircuitChipUiModel(
    val node: IntentNodeRecord,
    val status: DerivedStatus,
    val isEnergized: Boolean,
    val isBlocked: Boolean,
    val hasVerificationDiode: Boolean,
    val dependencyIds: List<String>,
    val column: Int = 0,
    val constraintCount: Int = 0,
    val acceptsCount: Int = 0,
)

data class BreadboardUiState(
    val chips: List<CircuitChipUiModel> = emptyList(),
    val selectedChipId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface BreadboardIntent {
    data object Refresh : BreadboardIntent
    data class SelectChip(val nodeId: String?) : BreadboardIntent
    /** Moves the component strip to the next (+1) or previous (-1) ready chip. */
    data class StepReady(val delta: Int) : BreadboardIntent
}

/**
 * Presenter for Design 5: The Logic Circuit Breadboard (RFC-0111).
 * Maps Intent Graph nodes to IC chips and DEPENDS_ON edges to energized or cold traces.
 * Reads the live [GraphSnapshot] (RFC-0124 UI-20).
 */
class BreadboardSchematicPresenter(
    snapshot: StateFlow<GraphSnapshot>,
    scope: CoroutineScope,
) : MviPresenter<BreadboardUiState, BreadboardIntent> {

    private val _state = MutableStateFlow(BreadboardUiState(isLoading = true))
    override val state: StateFlow<BreadboardUiState> = _state.asStateFlow()

    init {
        scope.launch {
            snapshot.collect { snap ->
                val statusMap = snap.nodes.associate { node ->
                    node.id to (snap.statuses[node.id]?.derivedStatus ?: DerivedStatus.NOT_STARTED)
                }
                _state.update {
                    it.copy(
                        chips = buildCircuitChips(snap.nodes, snap.edges, statusMap),
                        isLoading = snap.loadedAt == 0L && snap.error == null,
                        error = snap.error,
                    )
                }
            }
        }
    }

    override fun send(intent: BreadboardIntent) {
        when (intent) {
            is BreadboardIntent.Refresh -> Unit // the ProjectGraphStore refreshes itself
            is BreadboardIntent.SelectChip -> _state.update { it.copy(selectedChipId = intent.nodeId) }
            is BreadboardIntent.StepReady -> _state.update {
                it.copy(selectedChipId = stepReady(it.chips, it.selectedChipId, intent.delta))
            }
        }
    }

    companion object {
        fun buildCircuitChips(
            nodes: List<IntentNodeRecord>,
            edges: List<IntentEdgeRecord>,
            statusMap: Map<String, DerivedStatus>,
        ): List<CircuitChipUiModel> {
            val depsByTarget = edges
                .filter { it.edgeKind == IntentEdgeKind.DEPENDS_ON }
                .groupBy({ it.toNodeId }, { it.fromNodeId })
            val depths = depthsOf(nodes, edges)

            return nodes.map { node ->
                val status = statusMap[node.id] ?: DerivedStatus.NOT_STARTED
                val deps = depsByTarget[node.id].orEmpty()
                val allUpstreamDone = deps.all { (statusMap[it] ?: DerivedStatus.NOT_STARTED) == DerivedStatus.DONE }
                CircuitChipUiModel(
                    node = node,
                    status = status,
                    isEnergized = status == DerivedStatus.DONE || status == DerivedStatus.IN_PROGRESS || (deps.isNotEmpty() && allUpstreamDone),
                    isBlocked = status == DerivedStatus.BLOCKED || !allUpstreamDone,
                    hasVerificationDiode = status == DerivedStatus.NEEDS_REVIEW,
                    dependencyIds = deps,
                    column = depths[node.id] ?: 0,
                    constraintCount = edges.count { it.fromNodeId == node.id && it.edgeKind == IntentEdgeKind.CONSTRAINS },
                    acceptsCount = edges.count { it.fromNodeId == node.id && it.edgeKind == IntentEdgeKind.ACCEPTS },
                )
            }
        }

        /**
         * Dependency depth of every node: 0 for a node with no DEPENDS_ON prerequisite, otherwise one
         * more than its deepest prerequisite. A cycle in the graph is cut rather than followed, so a
         * bad edge cannot hang the view.
         */
        fun depthsOf(nodes: List<IntentNodeRecord>, edges: List<IntentEdgeRecord>): Map<String, Int> {
            val prerequisites = edges
                .filter { it.edgeKind == IntentEdgeKind.DEPENDS_ON }
                .groupBy({ it.toNodeId }, { it.fromNodeId })
            val memo = HashMap<String, Int>()

            fun depth(id: String, visiting: MutableSet<String>): Int {
                memo[id]?.let { return it }
                if (!visiting.add(id)) return 0
                val d = prerequisites[id].orEmpty()
                    .filter { it != id }
                    .maxOfOrNull { depth(it, visiting) + 1 } ?: 0
                visiting.remove(id)
                memo[id] = d
                return d
            }
            return nodes.associate { it.id to depth(it.id, mutableSetOf()) }
        }

        /** Column of [node] on the board, from the same snapshot the view draws. */
        fun columnOf(node: IntentNodeRecord, snapshot: GraphSnapshot): Int =
            depthsOf(snapshot.nodes, snapshot.edges)[node.id] ?: 0

        /** A wire carries current only when its source is DONE; anything else is a cold trace. */
        fun isEnergized(edge: IntentEdgeRecord, statuses: Map<String, DerivedStatus>): Boolean =
            statuses[edge.fromNodeId] == DerivedStatus.DONE

        /**
         * Complete is offered to a human, and to a hybrid, while the node is ready to be worked on.
         * An agent node is never completed by hand; it is started (see [startEnabled]).
         */
        fun completeEnabled(node: IntentNodeRecord, status: DerivedStatus): Boolean =
            node.actorKind != IntentActorKind.AGENT &&
                (status == DerivedStatus.NOT_STARTED || status == DerivedStatus.IN_PROGRESS)

        /**
         * Start is offered to an agent or hybrid node that waits for a manual start. A node with an
         * AUTO_WHEN_READY policy is started by the runtime, not by a tap (RFC-0124 U10). An
         * INHERIT policy counts as manual, the same default the intent graph uses.
         */
        fun startEnabled(node: IntentNodeRecord, status: DerivedStatus): Boolean =
            node.actorKind != IntentActorKind.HUMAN &&
                node.dispatchPolicy != NodeDispatchPolicy.AUTO_WHEN_READY &&
                status == DerivedStatus.NOT_STARTED

        fun chipAction(node: IntentNodeRecord, status: DerivedStatus): ChipAction = when {
            completeEnabled(node, status) -> ChipAction.COMPLETE
            startEnabled(node, status) -> ChipAction.START
            else -> ChipAction.NONE
        }

        fun readyChips(chips: List<CircuitChipUiModel>): List<CircuitChipUiModel> =
            chips.filter { chipAction(it.node, it.status) != ChipAction.NONE }

        /** The chip the component strip shows: the selected one, else the first ready one. */
        fun stripChip(chips: List<CircuitChipUiModel>, selectedChipId: String?): CircuitChipUiModel? =
            chips.firstOrNull { it.node.id == selectedChipId }
                ?: readyChips(chips).firstOrNull()
                ?: chips.firstOrNull()

        /** Moves the selection [delta] steps through the ready chips, wrapping at both ends. */
        fun stepReady(chips: List<CircuitChipUiModel>, selectedChipId: String?, delta: Int): String? {
            val ready = readyChips(chips)
            if (ready.isEmpty()) return selectedChipId
            val index = ready.indexOfFirst { it.node.id == selectedChipId }
            val base = if (index < 0) (if (delta >= 0) -1 else 0) else index
            val size = ready.size
            return ready[((base + delta) % size + size) % size].node.id
        }
    }
}
