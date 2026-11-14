package dev.aidos.ui.frontdoors.inbox

import dev.aidos.api.IntentCommands
import dev.aidos.api.ProjectCommands
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.ui.state.MviPresenter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ActionInboxUiState(
    val items: List<ActionItemUiModel> = emptyList(),
    val pendingApprovalsCount: Int = 0,
    val isLoading: Boolean = false,
    val selectedFilterProjectId: String? = null,
    val errorMessage: String? = null,
)

sealed interface ActionInboxIntent {
    data object Refresh : ActionInboxIntent
    data class CompleteItem(val projectId: String, val nodeId: String) : ActionInboxIntent
    data class FilterByProject(val projectId: String?) : ActionInboxIntent
}

class ActionInboxPresenter(
    private val intentCommands: IntentCommands,
    private val projectCommands: ProjectCommands,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviPresenter<ActionInboxUiState, ActionInboxIntent> {

    private val _state = MutableStateFlow(ActionInboxUiState(isLoading = true))
    override val state: StateFlow<ActionInboxUiState> = _state.asStateFlow()

    init {
        loadData()
    }

    override fun send(intent: ActionInboxIntent) {
        when (intent) {
            is ActionInboxIntent.Refresh -> loadData()
            is ActionInboxIntent.FilterByProject -> {
                _state.update { it.copy(selectedFilterProjectId = intent.projectId) }
            }
            is ActionInboxIntent.CompleteItem -> {
                completeItem(intent.projectId, intent.nodeId)
            }
        }
    }

    fun loadData() {
        scope.launch(ioDispatcher) {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val projects = projectCommands.list()
                val aggregatedItems = mutableListOf<ActionItemUiModel>()

                for (project in projects) {
                    val isLife = project.name.contains("personal", ignoreCase = true) ||
                            project.name.contains("life", ignoreCase = true)
                    val actionableViews = intentCommands.listActionableForHuman(project.id)

                    for (statusView in actionableViews) {
                        aggregatedItems.add(
                            ActionItemUiModel(
                                node = statusView.node,
                                statusView = statusView,
                                projectName = project.name,
                                isLifeProject = isLife,
                                batonDirection = if (statusView.derivedStatus == DerivedStatus.NEEDS_REVIEW) {
                                    BatonTransferDirection.AGENT_TO_HUMAN
                                } else {
                                    BatonTransferDirection.PEER_COLLABORATION
                                },
                            )
                        )
                    }
                }

                _state.update {
                    it.copy(
                        items = aggregatedItems,
                        pendingApprovalsCount = aggregatedItems.count { item ->
                            item.statusView.derivedStatus == DerivedStatus.NEEDS_REVIEW
                        },
                        isLoading = false,
                        errorMessage = null,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isLoading = false, errorMessage = t.message ?: "Failed to load inbox") }
            }
        }
    }

    private fun completeItem(projectId: String, nodeId: String) {
        scope.launch(ioDispatcher) {
            try {
                intentCommands.completeHumanNode(projectId, nodeId)
                loadData()
            } catch (t: Throwable) {
                _state.update { it.copy(errorMessage = t.message ?: "Failed to complete item") }
            }
        }
    }
}
