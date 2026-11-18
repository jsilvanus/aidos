package dev.aidos.ui.paradigms.timeline

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TimelineBarUiModel(
    val node: IntentNodeRecord,
    val status: DerivedStatus,
    val startSlot: Int,
    val durationSlots: Int,
    val dependencyCount: Int,
)

data class TimelineGanttUiState(
    val bars: List<TimelineBarUiModel> = emptyList(),
    val totalSlots: Int = 1,
    val selectedNodeId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface TimelineGanttIntent {
    data object Refresh : TimelineGanttIntent
    data class SelectNode(val nodeId: String?) : TimelineGanttIntent
}

/**
 * Presenter for the Timeline / Gantt view paradigm (RFC-0112 §2.1).
 * Topologically orders Intent Graph nodes along dependency slots. Reads the live [GraphSnapshot] (RFC-0124 UI-20).
 */
class TimelineGanttPresenter(
    snapshot: StateFlow<GraphSnapshot>,
    scope: CoroutineScope,
) : MviPresenter<TimelineGanttUiState, TimelineGanttIntent> {

    private val _state = MutableStateFlow(TimelineGanttUiState(isLoading = true))
    override val state: StateFlow<TimelineGanttUiState> = _state.asStateFlow()

    init {
        scope.launch {
            snapshot.collect { snap ->
                val statusMap = snap.nodes.associate { node ->
                    node.id to (snap.statuses[node.id]?.derivedStatus ?: DerivedStatus.NOT_STARTED)
                }
                val bars = buildTimelineBars(snap.nodes, snap.edges, statusMap)
                val maxEnd = bars.maxOfOrNull { it.startSlot + it.durationSlots } ?: 1
                _state.update {
                    it.copy(
                        bars = bars,
                        totalSlots = maxEnd.coerceAtLeast(1),
                        isLoading = snap.loadedAt == 0L && snap.error == null,
                        error = snap.error,
                    )
                }
            }
        }
    }

    override fun send(intent: TimelineGanttIntent) {
        when (intent) {
            is TimelineGanttIntent.Refresh -> Unit // the ProjectGraphStore refreshes itself
            is TimelineGanttIntent.SelectNode -> _state.update { it.copy(selectedNodeId = intent.nodeId) }
        }
    }

    companion object {
        fun buildTimelineBars(
            nodes: List<IntentNodeRecord>,
            edges: List<IntentEdgeRecord>,
            statusMap: Map<String, DerivedStatus>,
        ): List<TimelineBarUiModel> {
            if (nodes.isEmpty()) return emptyList()
            val nodeIds = nodes.map { it.id }.toSet()
            val depsByTarget = edges
                .filter { it.edgeKind == IntentEdgeKind.DEPENDS_ON && it.fromNodeId in nodeIds && it.toNodeId in nodeIds }
                .groupBy({ it.toNodeId }, { it.fromNodeId })

            val startSlots = mutableMapOf<String, Int>()
            val visiting = mutableSetOf<String>()

            fun computeStart(nodeId: String): Int {
                startSlots[nodeId]?.let { return it }
                if (!visiting.add(nodeId)) return 0
                val deps = depsByTarget[nodeId].orEmpty()
                val earliest = deps.maxOfOrNull { depId -> computeStart(depId) + 1 } ?: 0
                visiting.remove(nodeId)
                startSlots[nodeId] = earliest
                return earliest
            }

            return nodes.map { node ->
                val start = computeStart(node.id)
                val duration = (node.estimatedSteps ?: 1).coerceIn(1, 8)
                TimelineBarUiModel(
                    node = node,
                    status = statusMap[node.id] ?: DerivedStatus.NOT_STARTED,
                    startSlot = start,
                    durationSlots = duration,
                    dependencyCount = depsByTarget[node.id]?.size ?: 0,
                )
            }.sortedWith(compareBy<TimelineBarUiModel> { it.startSlot }.thenBy { it.node.title })
        }
    }
}
