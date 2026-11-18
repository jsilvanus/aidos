package dev.aidos.ui.paradigms.pipeline

import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
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
    val error: String? = null,
)

sealed interface RelayPipelineIntent {
    data object Refresh : RelayPipelineIntent
}

/** RFC-0124 amendment (UI-20): stages are derived from the live [GraphSnapshot]. */
class RelayPipelinePresenter(
    snapshot: StateFlow<GraphSnapshot>,
    scope: CoroutineScope,
) : MviPresenter<RelayPipelineUiState, RelayPipelineIntent> {

    private val _state = MutableStateFlow(RelayPipelineUiState(isLoading = true))
    override val state: StateFlow<RelayPipelineUiState> = _state.asStateFlow()

    init {
        scope.launch {
            snapshot.collect { snap ->
                _state.update {
                    it.copy(
                        stages = buildStages(snap.nodes, snap.statuses),
                        isLoading = snap.loadedAt == 0L && snap.error == null,
                        error = snap.error,
                    )
                }
            }
        }
    }

    override fun send(intent: RelayPipelineIntent) {
        // Refresh is a no-op: the ProjectGraphStore refreshes itself.
    }

    companion object {
        fun buildStages(
            nodes: List<IntentNodeRecord>,
            statusMap: Map<String, IntentNodeStatusView>,
        ): List<PipelineStageUiModel> {
            // Partition nodes into stages (e.g. by priority or sequential chunks)
            return nodes.chunked(4).mapIndexed { index, chunk ->
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
        }
    }
}
