package dev.aidos.ui.paradigms.pipeline

import dev.aidos.api.IntentCommands
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
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

data class PipelineStageUiModel(
    val stageIndex: Int,
    val humanNodes: List<IntentNodeRecord>,
    val agentNodes: List<IntentNodeRecord>,
    val statuses: Map<String, IntentNodeStatusView>,
    val hasBatonCross: Boolean = false,
)

data class RelayPipelineUiState(
    val stages: List<PipelineStageUiModel> = emptyList(),
    val isLoading: Boolean = false,
)

sealed interface RelayPipelineIntent {
    data object Refresh : RelayPipelineIntent
}

class RelayPipelinePresenter(
    private val projectId: String,
    private val intentCommands: IntentCommands,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviPresenter<RelayPipelineUiState, RelayPipelineIntent> {

    private val _state = MutableStateFlow(RelayPipelineUiState(isLoading = true))
    override val state: StateFlow<RelayPipelineUiState> = _state.asStateFlow()

    init {
        loadPipeline()
    }

    override fun send(intent: RelayPipelineIntent) {
        when (intent) {
            is RelayPipelineIntent.Refresh -> loadPipeline()
        }
    }

    fun loadPipeline() {
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

                // Partition nodes into stages (e.g. by priority or sequential chunks)
                val stages = nodes.chunked(4).mapIndexed { index, chunk ->
                    val humans = chunk.filter { it.actorKind == IntentActorKind.HUMAN }
                    val agents = chunk.filter { it.actorKind == IntentActorKind.AGENT || it.actorKind == IntentActorKind.HYBRID }
                    PipelineStageUiModel(
                        stageIndex = index + 1,
                        humanNodes = humans,
                        agentNodes = agents,
                        statuses = statusMap,
                        hasBatonCross = humans.isNotEmpty() && agents.isNotEmpty(),
                    )
                }

                _state.update {
                    it.copy(stages = stages, isLoading = false)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }
}
