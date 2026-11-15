package dev.aidos.ui.spatial.schematic

import dev.aidos.api.IntentCommands
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.state.MviPresenter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CircuitChipUiModel(
    val node: IntentNodeRecord,
    val status: DerivedStatus,
    val isEnergized: Boolean,
    val isBlocked: Boolean,
    val hasVerificationDiode: Boolean,
    val dependencyIds: List<String>,
)

data class BreadboardUiState(
    val chips: List<CircuitChipUiModel> = emptyList(),
    val selectedChipId: String? = null,
    val isLoading: Boolean = false,
)

sealed interface BreadboardIntent {
    data object Refresh : BreadboardIntent
    data class SelectChip(val nodeId: String?) : BreadboardIntent
}

/**
 * Presenter for Design 5: The Logic Circuit Breadboard (RFC-0111).
 * Maps Intent Graph nodes to IC chips and DEPENDS_ON edges to energized or cold traces.
 */
class BreadboardSchematicPresenter(
    private val projectId: String,
    private val intentCommands: IntentCommands,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviPresenter<BreadboardUiState, BreadboardIntent> {

    private val _state = MutableStateFlow(BreadboardUiState(isLoading = true))
    override val state: StateFlow<BreadboardUiState> = _state.asStateFlow()

    init {
        loadCircuit()
    }

    override fun send(intent: BreadboardIntent) {
        when (intent) {
            is BreadboardIntent.Refresh -> loadCircuit()
            is BreadboardIntent.SelectChip -> _state.update { it.copy(selectedChipId = intent.nodeId) }
        }
    }

    fun loadCircuit() {
        scope.launch(ioDispatcher) {
            _state.update { it.copy(isLoading = true) }
            try {
                val nodes = intentCommands.listNodes(projectId)
                val edges = intentCommands.listEdges(projectId)
                val statusMap = nodes.associate { node ->
                    node.id to (intentCommands.getNodeStatus(projectId, node.id)?.derivedStatus ?: DerivedStatus.NOT_STARTED)
                }
                val chips = buildCircuitChips(nodes, edges, statusMap)
                _state.update { it.copy(chips = chips, isLoading = false) }
            } catch (_: Throwable) {
                _state.update { it.copy(isLoading = false) }
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
                )
            }
        }
    }
}
