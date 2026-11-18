package dev.aidos.ui.spatial.schematic

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MetroStationUiModel(
    val node: IntentNodeRecord,
    val status: DerivedStatus,
    val isTransferHub: Boolean,
    val hasTurnstileGate: Boolean,
)

data class MetroLineUiModel(
    val lineId: String,
    val title: String,
    val colorHex: Long,
    val stations: List<MetroStationUiModel>,
)

data class TransitMetroUiState(
    val lines: List<MetroLineUiModel> = emptyList(),
    val selectedStationId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface TransitMetroIntent {
    data object Refresh : TransitMetroIntent
    data class SelectStation(val nodeId: String?) : TransitMetroIntent
}

/**
 * Presenter for Design 2: The Transit Metro Map (RFC-0111).
 * Maps GOAL/GROUP nodes to color-coded metro lines, SUB_GOALs to transfer hubs,
 * and ATOMIC_TASKs to local stations with NEEDS_REVIEW turnstile gates.
 * Reads the live [GraphSnapshot] (RFC-0124 UI-20).
 */
class TransitMetroPresenter(
    snapshot: StateFlow<GraphSnapshot>,
    scope: CoroutineScope,
) : MviPresenter<TransitMetroUiState, TransitMetroIntent> {

    private val _state = MutableStateFlow(TransitMetroUiState(isLoading = true))
    override val state: StateFlow<TransitMetroUiState> = _state.asStateFlow()

    init {
        scope.launch {
            snapshot.collect { snap ->
                val statusMap = snap.nodes.associate { node ->
                    node.id to (snap.statuses[node.id]?.derivedStatus ?: DerivedStatus.NOT_STARTED)
                }
                _state.update {
                    it.copy(
                        lines = buildMetroLines(snap.nodes, statusMap),
                        isLoading = snap.loadedAt == 0L && snap.error == null,
                        error = snap.error,
                    )
                }
            }
        }
    }

    override fun send(intent: TransitMetroIntent) {
        when (intent) {
            is TransitMetroIntent.Refresh -> Unit // the ProjectGraphStore refreshes itself
            is TransitMetroIntent.SelectStation -> _state.update { it.copy(selectedStationId = intent.nodeId) }
        }
    }

    companion object {
        private val LINE_PALETTE = listOf(0xFFEF4444L, 0xFF3B82F6L, 0xFF10B981L, 0xFFF59E0BL, 0xFFA855F7L)

        fun buildMetroLines(
            nodes: List<IntentNodeRecord>,
            statusMap: Map<String, DerivedStatus>,
        ): List<MetroLineUiModel> {
            if (nodes.isEmpty()) return emptyList()
            val roots = nodes.filter { it.type == IntentNodeType.GROUP || it.type == IntentNodeType.GOAL || it.parentId == null }
            val byParent = nodes.groupBy { it.parentId }

            if (roots.isEmpty()) {
                return listOf(
                    MetroLineUiModel(
                        lineId = "main-line",
                        title = "Main Line",
                        colorHex = LINE_PALETTE.first(),
                        stations = nodes.map { toStation(it, statusMap[it.id] ?: DerivedStatus.NOT_STARTED) },
                    )
                )
            }

            return roots.mapIndexed { index, root ->
                val descendants = collectDescendants(root.id, byParent)
                val stations = (listOf(root) + descendants).map { node ->
                    toStation(node, statusMap[node.id] ?: DerivedStatus.NOT_STARTED)
                }
                MetroLineUiModel(
                    lineId = root.id,
                    title = root.title,
                    colorHex = LINE_PALETTE[index % LINE_PALETTE.size],
                    stations = stations,
                )
            }
        }

        private fun collectDescendants(
            parentId: String,
            byParent: Map<String?, List<IntentNodeRecord>>,
        ): List<IntentNodeRecord> {
            val result = mutableListOf<IntentNodeRecord>()
            val queue = ArrayDeque(byParent[parentId].orEmpty())
            val visited = mutableSetOf(parentId)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (visited.add(current.id)) {
                    result.add(current)
                    byParent[current.id].orEmpty().forEach { queue.addLast(it) }
                }
            }
            return result
        }

        private fun toStation(node: IntentNodeRecord, status: DerivedStatus): MetroStationUiModel {
            return MetroStationUiModel(
                node = node,
                status = status,
                isTransferHub = node.type == IntentNodeType.SUB_GOAL || node.type == IntentNodeType.GOAL,
                hasTurnstileGate = status == DerivedStatus.NEEDS_REVIEW || node.type == IntentNodeType.ACCEPTANCE_CRITERION,
            )
        }
    }
}
