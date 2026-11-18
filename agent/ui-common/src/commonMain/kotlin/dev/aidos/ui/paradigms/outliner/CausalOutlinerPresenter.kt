package dev.aidos.ui.paradigms.outliner

import dev.aidos.api.UiManifestRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Asks the screen to scroll to [nodeId]. [serial] makes every request distinct, so repeats still scroll. */
data class JumpRequest(val nodeId: String, val serial: Long)

/**
 * The inline "add a child" field. It sits under [anchorId]; [parentId] and [type] say where the new node
 * goes. Tab moves [parentId] to the previous sibling (indent), as RFC-0111 Design 6 asks for desktop.
 */
data class ChildDraft(
    val anchorId: String,
    val parentId: String?,
    val type: IntentNodeType,
    val text: String = "",
)

data class CausalOutlinerUiState(
    val visibleRows: List<OutlinerNodeUiModel> = emptyList(),
    val expandedNodeIds: Set<String> = emptySet(),
    val focusedNodeId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
    val horizon: List<OutlinerNodeUiModel> = emptyList(),
    val ledger: List<OutlinerNodeUiModel> = emptyList(),
    val collapsedSections: Set<OutlinerSection> = emptySet(),
    val jumpRequest: JumpRequest? = null,
    val draft: ChildDraft? = null,
)

sealed interface CausalOutlinerIntent {
    data class ToggleExpand(val nodeId: String) : CausalOutlinerIntent
    data class SelectNode(val nodeId: String) : CausalOutlinerIntent
    data class JumpToPrerequisite(val prerequisiteId: String) : CausalOutlinerIntent
    data class ToggleSection(val section: OutlinerSection) : CausalOutlinerIntent
    data class StartChild(val parentId: String) : CausalOutlinerIntent
    data class DraftText(val text: String) : CausalOutlinerIntent
    data object IndentDraft : CausalOutlinerIntent
    data object CancelDraft : CausalOutlinerIntent
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
    private var allManifests: Map<String, List<UiManifestRecord>> = emptyMap()
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
                        visibleRows = buildFlattenedTree(allNodes, allStatuses, nextExpanded, allManifests)
                    )
                }
            }
            is CausalOutlinerIntent.SelectNode -> {
                _state.update { it.copy(focusedNodeId = intent.nodeId) }
            }
            is CausalOutlinerIntent.JumpToPrerequisite -> jumpTo(intent.prerequisiteId)
            is CausalOutlinerIntent.ToggleSection -> {
                _state.update {
                    val next = if (intent.section in it.collapsedSections) {
                        it.collapsedSections - intent.section
                    } else {
                        it.collapsedSections + intent.section
                    }
                    it.copy(collapsedSections = next)
                }
            }
            is CausalOutlinerIntent.StartChild -> {
                val parent = allNodes.firstOrNull { it.id == intent.parentId } ?: return
                _state.update {
                    it.copy(
                        draft = ChildDraft(
                            anchorId = parent.id,
                            parentId = parent.id,
                            type = childTypeFor(parent.type),
                        ),
                    )
                }
            }
            is CausalOutlinerIntent.DraftText -> {
                _state.update { s -> s.copy(draft = s.draft?.copy(text = intent.text)) }
            }
            is CausalOutlinerIntent.IndentDraft -> indentDraft()
            is CausalOutlinerIntent.CancelDraft -> _state.update { it.copy(draft = null) }
            is CausalOutlinerIntent.Refresh -> Unit
        }
    }

    /** Opens every ancestor of the target, shows the Intent Tree, and asks the screen to scroll there. */
    private fun jumpTo(nodeId: String) {
        val expanded = _state.value.expandedNodeIds + ancestorsOf(nodeId)
        _state.update {
            it.copy(
                expandedNodeIds = expanded,
                visibleRows = buildFlattenedTree(allNodes, allStatuses, expanded, allManifests),
                collapsedSections = it.collapsedSections - OutlinerSection.entries.toSet(),
                focusedNodeId = nodeId,
                jumpRequest = JumpRequest(nodeId, (it.jumpRequest?.serial ?: 0L) + 1L),
            )
        }
    }

    private fun indentDraft() {
        val current = _state.value
        val draft = current.draft ?: return
        val previousId = previousSiblingId(draft.anchorId, current.visibleRows) ?: return
        val previous = allNodes.firstOrNull { it.id == previousId } ?: return
        _state.update { it.copy(draft = draft.copy(parentId = previousId, type = childTypeFor(previous.type))) }
    }

    private fun ancestorsOf(nodeId: String): Set<String> {
        val byId = allNodes.associateBy { it.id }
        val found = HashSet<String>()
        var cursor = byId[nodeId]?.parentId
        while (cursor != null && found.add(cursor)) {
            cursor = byId[cursor]?.parentId
        }
        return found
    }

    private fun applySnapshot(snap: GraphSnapshot) {
        allNodes = snap.nodes
        allStatuses = snap.statuses
        allManifests = snap.manifests
        // A parent expands by default the first time it appears; one the person collapsed stays collapsed.
        val parents = snap.nodes.mapNotNull { it.parentId }.toSet()
        val newParents = parents - parentsSeen
        parentsSeen += parents
        val expanded = _state.value.expandedNodeIds + newParents
        val index = OutlinerIndex(snap.nodes, snap.statuses)
        _state.update {
            it.copy(
                visibleRows = buildFlattenedTree(snap.nodes, snap.statuses, expanded, snap.manifests),
                expandedNodeIds = expanded,
                horizon = actionHorizon(snap).map { node -> index.row(node) },
                ledger = verificationLedger(snap).map { node -> index.row(node) },
                isLoading = snap.loadedAt == 0L && snap.error == null,
                error = snap.error,
            )
        }
    }

    companion object {
        /**
         * Depth-first list of the Intent Tree. Acceptance criteria are not tree rows; each task carries
         * them in [OutlinerNodeUiModel.criteria] and the row draws them as a checklist.
         */
        fun buildFlattenedTree(
            nodes: List<IntentNodeRecord>,
            statuses: Map<String, IntentNodeStatusView>,
            expandedNodeIds: Set<String>,
            manifests: Map<String, List<UiManifestRecord>> = emptyMap(),
        ): List<OutlinerNodeUiModel> {
            val index = OutlinerIndex(nodes, statuses, manifests)
            val result = mutableListOf<OutlinerNodeUiModel>()

            fun walk(node: IntentNodeRecord, depth: Int) {
                val children = index.treeChildren(node.id)
                val isExpanded = expandedNodeIds.contains(node.id)
                result.add(
                    index.row(
                        node,
                        depth = depth,
                        isExpanded = isExpanded,
                        hasChildren = children.isNotEmpty(),
                    )
                )
                if (children.isNotEmpty() && isExpanded) {
                    children.forEach { child -> walk(child, depth + 1) }
                }
            }

            index.treeChildren(null).forEach { walk(it, 0) }
            return result
        }
    }
}
