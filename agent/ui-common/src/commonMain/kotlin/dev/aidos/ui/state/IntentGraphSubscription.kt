package dev.aidos.ui.state

import dev.aidos.api.IntentCommands
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class IntentGraphUiState(
    val projectId: String = "",
    val nodes: List<IntentNodeRecord> = emptyList(),
    val statuses: Map<String, IntentNodeStatusView> = emptyMap(),
    val actionableHumanNodes: List<IntentNodeStatusView> = emptyList(),
    val isSyncing: Boolean = false,
    val lastError: String? = null,
)

/**
 * Shared reactive state bridge listening to [IntentCommands] (RFC-0110, RFC-0111).
 * Safe for high-frequency concurrent background dispatch.
 */
class IntentGraphSubscription(
    private val intentCommands: IntentCommands,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _state = MutableStateFlow(IntentGraphUiState())
    val state: StateFlow<IntentGraphUiState> = _state.asStateFlow()

    fun bindProject(projectId: String) {
        _state.update { it.copy(projectId = projectId, isSyncing = true, lastError = null) }
        refresh(projectId)
    }

    fun refresh(projectId: String = _state.value.projectId) {
        if (projectId.isBlank()) return
        scope.launch(ioDispatcher) {
            try {
                _state.update { it.copy(isSyncing = true) }
                val nodes = intentCommands.listNodes(projectId = projectId)
                val actionable = intentCommands.listActionableForHuman(projectId = projectId)
                
                val statusMap = mutableMapOf<String, IntentNodeStatusView>()
                for (node in nodes) {
                    val statusView = intentCommands.getNodeStatus(projectId, node.id)
                    if (statusView != null) {
                        statusMap[node.id] = statusView
                    }
                }

                _state.update {
                    it.copy(
                        projectId = projectId,
                        nodes = nodes,
                        statuses = statusMap,
                        actionableHumanNodes = actionable,
                        isSyncing = false,
                        lastError = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isSyncing = false, lastError = t.message ?: "Sync error") }
            }
        }
    }

    /**
     * Direct high-throughput batch update processor for stress testing and local event streaming.
     */
    suspend fun applyBatchUpdate(
        nodes: List<IntentNodeRecord>,
        statuses: Map<String, IntentNodeStatusView>,
    ) = withContext(ioDispatcher) {
        val actionable = statuses.values.filter { view ->
            view.node.actorKind == IntentActorKind.HUMAN
        }
        _state.update { current ->
            current.copy(
                nodes = nodes,
                statuses = statuses,
                actionableHumanNodes = actionable,
                isSyncing = false,
            )
        }
    }
}
