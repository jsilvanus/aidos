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
    val status: DerivedStatus,
    val title: String,
    val tasks: List<Pair<IntentNodeRecord, DerivedStatus>>,
)

data class CartridgeDockUiState(
    val columns: List<KanbanColumn> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface CartridgeDockIntent {
    data object Refresh : CartridgeDockIntent
    data class MoveTask(val nodeId: String, val targetStatus: DerivedStatus) : CartridgeDockIntent
}

/**
 * RFC-0124 amendment (UI-20): columns are derived from the live [GraphSnapshot].
 * [CartridgeDockIntent.MoveTask] is only an in-memory preview; the next snapshot replaces it.
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
                _state.update {
                    it.copy(
                        columns = buildColumns(snap.nodes, snap.statuses.mapValues { e -> e.value.derivedStatus }),
                        isLoading = snap.loadedAt == 0L && snap.error == null,
                        error = snap.error,
                    )
                }
            }
        }
    }

    override fun send(intent: CartridgeDockIntent) {
        when (intent) {
            is CartridgeDockIntent.Refresh -> Unit // the ProjectGraphStore refreshes itself
            is CartridgeDockIntent.MoveTask -> {
                // In memory optimistic update
                _state.update { current ->
                    val updated = current.columns.map { col ->
                        if (col.status == intent.targetStatus) {
                            val moved = current.columns.flatMap { it.tasks }.firstOrNull { it.first.id == intent.nodeId }
                            if (moved != null) col.copy(tasks = col.tasks + (moved.first to intent.targetStatus)) else col
                        } else {
                            col.copy(tasks = col.tasks.filterNot { it.first.id == intent.nodeId })
                        }
                    }
                    current.copy(columns = updated)
                }
            }
        }
    }

    companion object {
        fun buildColumns(
            nodes: List<IntentNodeRecord>,
            statuses: Map<String, DerivedStatus>,
        ): List<KanbanColumn> {
            val primaryStatuses = listOf(
                DerivedStatus.NOT_STARTED to "Backlog",
                DerivedStatus.IN_PROGRESS to "Active Work",
                DerivedStatus.NEEDS_REVIEW to "Review Gates",
                DerivedStatus.BLOCKED to "Blocked",
                DerivedStatus.DONE to "Completed",
            )
            return primaryStatuses.map { (status, label) ->
                val tasks = nodes.mapNotNull { node ->
                    val nodeStatus = statuses[node.id] ?: DerivedStatus.NOT_STARTED
                    if (nodeStatus == status) node to nodeStatus else null
                }
                KanbanColumn(status = status, title = label, tasks = tasks)
            }
        }
    }
}
