package dev.aidos.ui.paradigms.outliner

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

data class CausalOutlinerUiState(
    val visibleRows: List<OutlinerNodeUiModel> = emptyList(),
    val expandedNodeIds: Set<String> = emptySet(),
    val focusedNodeId: String? = null,
    val isLoading: Boolean = false,
)

sealed interface CausalOutlinerIntent {
    data class ToggleExpand(val nodeId: String) : CausalOutlinerIntent
    data class SelectNode(val nodeId: String) : CausalOutlinerIntent
    data class JumpToPrerequisite(val prerequisiteId: String) : CausalOutlinerIntent
    data object Refresh : CausalOutlinerIntent
}

class CausalOutlinerPresenter(
    private val projectId: String,
    private val intentCommands: IntentCommands,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviPresenter<CausalOutlinerUiState, CausalOutlinerIntent> {

    private val _state = MutableStateFlow(CausalOutlinerUiState(isLoading = true))
    override val state: StateFlow<CausalOutlinerUiState> = _state.asStateFlow()

    private var allNodes: List<IntentNodeRecord> = emptyList()
    private var allStatuses: Map<String, IntentNodeStatusView> = emptyMap()

    init {
        loadGraph()
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
            is CausalOutlinerIntent.Refresh -> loadGraph()
        }
    }

    fun loadGraph() {
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
                allNodes = nodes
                allStatuses = statusMap

                // Default expand all parent nodes
                val parentsWithChildren = nodes.mapNotNull { it.parentId }.toSet()
                val expanded = _state.value.expandedNodeIds + parentsWithChildren

                val rows = buildFlattenedTree(nodes, statusMap, expanded)
                _state.update {
                    it.copy(
                        visibleRows = rows,
                        expandedNodeIds = expanded,
                        isLoading = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isLoading = false) }
            }
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
