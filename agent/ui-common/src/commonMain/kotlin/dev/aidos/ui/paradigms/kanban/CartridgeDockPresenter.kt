package dev.aidos.ui.paradigms.kanban

import dev.aidos.api.IntentCommands
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.ui.state.MviPresenter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
)

sealed interface CartridgeDockIntent {
    data object Refresh : CartridgeDockIntent
    data class MoveTask(val nodeId: String, val targetStatus: DerivedStatus) : CartridgeDockIntent
}

class CartridgeDockPresenter(
    private val projectId: String,
    private val intentCommands: IntentCommands,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviPresenter<CartridgeDockUiState, CartridgeDockIntent> {

    private val _state = MutableStateFlow(CartridgeDockUiState(isLoading = true))
    override val state: StateFlow<CartridgeDockUiState> = _state.asStateFlow()

    init {
        loadDock()
    }

    override fun send(intent: CartridgeDockIntent) {
        when (intent) {
            is CartridgeDockIntent.Refresh -> loadDock()
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

    fun loadDock() {
        scope.launch(ioDispatcher) {
            _state.update { it.copy(isLoading = true) }
            try {
                val nodes = intentCommands.listNodes(projectId)
                val statusMap = mutableMapOf<String, IntentNodeStatusView>()
                for (node in nodes) {
                    val status = intentCommands.getNodeStatus(projectId, node.id)
                    if (status != null) {
                        statusMap[node.id] = status
                    }
                }

                val primaryStatuses = listOf(
                    DerivedStatus.NOT_STARTED to "Backlog",
                    DerivedStatus.IN_PROGRESS to "Active Work",
                    DerivedStatus.NEEDS_REVIEW to "Review Gates",
                    DerivedStatus.BLOCKED to "Blocked",
                    DerivedStatus.DONE to "Completed",
                )

                val columns = primaryStatuses.map { (status, label) ->
                    val tasks = nodes.mapNotNull { node ->
                        val nodeStatus = statusMap[node.id]?.derivedStatus ?: DerivedStatus.NOT_STARTED
                        if (nodeStatus == status) node to nodeStatus else null
                    }
                    KanbanColumn(
                        status = status,
                        title = label,
                        tasks = tasks,
                    )
                }

                _state.update {
                    it.copy(columns = columns, isLoading = false)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }
}
