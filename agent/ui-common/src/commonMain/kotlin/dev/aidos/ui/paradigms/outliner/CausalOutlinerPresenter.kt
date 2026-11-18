package dev.aidos.ui.paradigms.outliner

import dev.aidos.intent.DerivedStatus
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

data class CausalOutlinerUiState(
    val visibleRows: List<OutlinerNodeUiModel> = emptyList(),
    val expandedNodeIds: Set<String> = emptySet(),
    val focusedNodeId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface CausalOutlinerIntent {
    data class ToggleExpand(val nodeId: String) : CausalOutlinerIntent
    data class SelectNode(val nodeId: String) : CausalOutlinerIntent
    data class JumpToPrerequisite(val prerequisiteId: String) : CausalOutlinerIntent
    data object Refresh : CausalOutlinerIntent
}

/**
 * RFC-0124 amendment (UI-20): reads the project's live [GraphSnapshot]; it never fetches the graph itself.
 * [CausalOutlinerIntent.Refresh] is a no-op because the store refreshes on its own.
 */
class CausalOutlinerPresenter(
    snapshot: StateFlow<GraphSnapshot>,
    scope: CoroutineScope,
) : MviPresenter<CausalOutlinerUiState, CausalOutlinerIntent> {

    private val _state = MutableStateFlow(CausalOutlinerUiState(isLoading = true))
    override val state: StateFlow<CausalOutlinerUiState> = _state.asStateFlow()

    private var allNodes: List<IntentNodeRecord> = emptyList()
    private var allStatuses: Map<String, IntentNodeStatusView> = emptyMap()
    private val parentsSeen = HashSet<String>()

    init {
        scope.launch {
            snapshot.collect { applySnapshot(it) }
        }
    }

    override fun send(intent: CausalOutlinerIntent) {
        when (intent) {
            is CausalOutlinerIntent.ToggleExpand -> {
                val currentExpanded = _state.value.expandedNodeIds
                val nextExpanded = if (currentExpanded.contains(intent.nodeId)) {
                    currentExpanded - intent.nodeId
                } else {
                    currentExpanded + intent.nodeId
                }
                _state.update {
                    it.copy(
                        expandedNodeIds = nextExpanded,
                        visibleRows = buildFlattenedTree(allNodes, allStatuses, nextExpanded)
                    )
                }
            }
            is CausalOutlinerIntent.SelectNode -> {
                _state.update { it.copy(focusedNodeId = intent.nodeId) }
            }
            is CausalOutlinerIntent.JumpToPrerequisite -> {
                _state.update { it.copy(focusedNodeId = intent.prerequisiteId) }
            }
            is CausalOutlinerIntent.Refresh -> Unit
        }
    }

    private fun applySnapshot(snap: GraphSnapshot) {
        allNodes = snap.nodes
        allStatuses = snap.statuses
        // A parent expands by default the first time it appears; one the person collapsed stays collapsed.
        val parents = snap.nodes.mapNotNull { it.parentId }.toSet()
        val newParents = parents - parentsSeen
        parentsSeen += parents
        _state.update {
            val expanded = it.expandedNodeIds + newParents
            it.copy(
                visibleRows = buildFlattenedTree(snap.nodes, snap.statuses, expanded),
                expandedNodeIds = expanded,
                isLoading = snap.loadedAt == 0L && snap.error == null,
                error = snap.error,
            )
        }
    }

    companion object {
        fun buildFlattenedTree(
            nodes: List<IntentNodeRecord>,
            statuses: Map<String, IntentNodeStatusView>,
            expandedNodeIds: Set<String>,
        ): List<OutlinerNodeUiModel> {
            val childrenByParent = nodes.groupBy { it.parentId }
            val rootNodes = childrenByParent[null] ?: emptyList()
            val result = mutableListOf<OutlinerNodeUiModel>()
            val nodeTitleMap = nodes.associate { it.id to it.title }

            fun walk(node: IntentNodeRecord, depth: Int) {
                val children = childrenByParent[node.id] ?: emptyList()
                val hasChildren = children.isNotEmpty()
                val isExpanded = expandedNodeIds.contains(node.id)
                val statusView = statuses[node.id]
                val derivedStatus = statusView?.derivedStatus ?: DerivedStatus.NOT_STARTED

                val unmet = statusView?.unmetDependencyIds?.map { id ->
                    id to (nodeTitleMap[id] ?: "Task $id")
                } ?: emptyList()

                result.add(
                    OutlinerNodeUiModel(
                        node = node,
                        derivedStatus = derivedStatus,
                        depth = depth,
                        isExpanded = isExpanded,
                        hasChildren = hasChildren,
                        unmetPrerequisites = unmet,
                    )
                )

                if (hasChildren && isExpanded) {
                    children.forEach { child ->
                        walk(child, depth + 1)
                    }
                }
            }

            rootNodes.forEach { walk(it, 0) }
            return result
        }
    }
}
