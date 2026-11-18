package dev.aidos.ui.paradigms.kanban

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class KanbanColumn(
    val lane: DockLane,
    val status: DerivedStatus,
    val title: String,
    val tasks: List<Pair<IntentNodeRecord, DerivedStatus>>,
)

data class CartridgeDockUiState(
    val columns: List<KanbanColumn> = emptyList(),
    /** One row per nearest goal, for the desktop and expanded board. */
    val swimlanes: List<DockSwimlane> = emptyList(),
    /** STALE and CONFLICTED tasks, shown in a strip above the board. */
    val attention: List<Pair<IntentNodeRecord, DerivedStatus>> = emptyList(),
    /** Unmet dependency count per node id; a node with a count above zero cannot move forward. */
    val unmet: Map<String, Int> = emptyMap(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface CartridgeDockIntent {
    data object Refresh : CartridgeDockIntent

    /**
     * Legacy in-memory preview, kept so existing callers keep working. The board does not use it:
     * drops go through [CartridgeDockPresenter.drop], which only moves what the runtime allows.
     */
    data class MoveTask(val nodeId: String, val targetStatus: DerivedStatus) : CartridgeDockIntent
}

/**
 * RFC-0111 Design 3 / RFC-0124 UI-36: columns and swimlanes derive from the live [GraphSnapshot].
 * A drop is decided by [dropAction]; the screen performs the resulting action through the
 * workspace controller and the store refreshes the board afterwards.
 */
class CartridgeDockPresenter(
    snapshot: StateFlow<GraphSnapshot>,
    scope: CoroutineScope,
) : MviPresenter<CartridgeDockUiState, CartridgeDockIntent> {

    private val _state = MutableStateFlow(CartridgeDockUiState(isLoading = true))
    override val state: StateFlow<CartridgeDockUiState> = _state.asStateFlow()

    init {
        scope.launch {
            snapshot.collect { snap ->
                _state.value = build(snap).copy(isLoading = snap.loadedAt == 0L && snap.error == null, error = snap.error)
            }
        }
    }

    /** The action for dropping [nodeId] on column [to], decided from the current board. */
    fun drop(nodeId: String, to: DockLane): DockAction {
        val current = _state.value
        val from = current.columns.firstOrNull { col -> col.tasks.any { it.first.id == nodeId } }?.lane
            ?: return DockAction.Refuse("This task needs attention before it can move")
        val node = current.columns.first { it.lane == from }.tasks.first { it.first.id == nodeId }.first
        return dropAction(node, from, to, current.unmet[nodeId] ?: 0)
    }

    override fun send(intent: CartridgeDockIntent) {
        when (intent) {
            is CartridgeDockIntent.Refresh -> Unit // the ProjectGraphStore refreshes itself
            is CartridgeDockIntent.MoveTask -> _state.update { current ->
                val moved = current.columns.flatMap { it.tasks }.firstOrNull { it.first.id == intent.nodeId }
                val updated = current.columns.map { col ->
                    val without = col.tasks.filterNot { it.first.id == intent.nodeId }
                    if (col.status == intent.targetStatus && moved != null) {
                        col.copy(tasks = without + (moved.first to intent.targetStatus))
                    } else {
                        col.copy(tasks = without)
                    }
                }
                current.copy(columns = updated)
            }
        }
    }

    companion object {
        /** Pure: the whole board for one snapshot. */
        fun build(snap: GraphSnapshot): CartridgeDockUiState {
            val byId = snap.nodes.associateBy { it.id }
            val statuses = snap.statuses.mapValues { it.value.derivedStatus }
            val unmet = snap.statuses.mapValues { it.value.unmetDependencyIds.size }
            val cartridges = snap.nodes.filter(::isCartridge).sortedWith(compareBy({ it.priority }, { it.title }))

            val placed = LinkedHashMap<String, Pair<IntentNodeRecord, DockLane>>()
            val attention = mutableListOf<Pair<IntentNodeRecord, DerivedStatus>>()
            for (node in cartridges) {
                val status = statuses[node.id] ?: DerivedStatus.NOT_STARTED
                val lane = laneFor(status, unmet[node.id] ?: 0)
                if (lane == null) attention += node to status else placed[node.id] = node to lane
            }

            val columns = DockLane.entries.map { lane ->
                KanbanColumn(
                    lane = lane,
                    status = lane.status,
                    title = lane.title,
                    tasks = placed.values.filter { it.second == lane }.map { (node, _) ->
                        node to (statuses[node.id] ?: DerivedStatus.NOT_STARTED)
                    },
                )
            }
            return CartridgeDockUiState(
                columns = columns,
                swimlanes = buildSwimlanes(placed, byId),
                attention = attention,
                unmet = unmet,
            )
        }
    }
}
