package dev.aidos.ui.paradigms.outliner

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.workspace.GraphSnapshot

/** RFC-0111 Design 6: the three sections of the Causal Outliner. */
enum class OutlinerSection(val title: String, val keyPrefix: String) {
    ACTION_HORIZON("Action Horizon", "h"),
    INTENT_TREE("Intent Tree", "t"),
    VERIFICATION_LEDGER("Verification Ledger", "l"),
}

/**
 * Node-level lookups shared by the sections. Built once per snapshot so that a
 * tree of many nodes is not scanned again for every row.
 */
internal class OutlinerIndex(
    nodes: List<IntentNodeRecord>,
    private val statuses: Map<String, IntentNodeStatusView>,
) {
    private val titles: Map<String, String> = nodes.associate { it.id to it.title }
    private val byParent: Map<String?, List<IntentNodeRecord>> = nodes.groupBy { it.parentId }

    /** Children that appear as tree rows; acceptance criteria are drawn under their task instead. */
    fun treeChildren(parentId: String?): List<IntentNodeRecord> =
        byParent[parentId].orEmpty().filter { it.type != IntentNodeType.ACCEPTANCE_CRITERION }

    fun criteriaOf(nodeId: String): List<IntentNodeRecord> =
        byParent[nodeId].orEmpty().filter { it.type == IntentNodeType.ACCEPTANCE_CRITERION }

    fun row(
        node: IntentNodeRecord,
        depth: Int = 0,
        isExpanded: Boolean = false,
        hasChildren: Boolean = false,
    ): OutlinerNodeUiModel {
        val view = statuses[node.id]
        return OutlinerNodeUiModel(
            node = node,
            derivedStatus = view?.derivedStatus ?: DerivedStatus.NOT_STARTED,
            depth = depth,
            isExpanded = isExpanded,
            hasChildren = hasChildren,
            unmetPrerequisites = view?.unmetDependencyIds?.map { id -> id to (titles[id] ?: "Task $id") }.orEmpty(),
            activeRuns = view?.activeRunIds?.size ?: 0,
            criteria = criteriaOf(node.id),
        )
    }
}

private fun statusOf(snapshot: GraphSnapshot, id: String): DerivedStatus? = snapshot.statuses[id]?.derivedStatus

private val priorityOrder = compareBy<IntentNodeRecord>(
    { it.priority },
    { it.dueAt == null },
    { it.dueAt.orEmpty() },
)

/**
 * Action Horizon: active ATOMIC_TASK nodes that are not DONE, not BLOCKED and have no unmet
 * dependency, sorted by priority (lower first), then due date (earlier first, undated last).
 */
fun actionHorizon(snapshot: GraphSnapshot): List<IntentNodeRecord> =
    snapshot.nodes
        .filter { it.lifecycle == IntentLifecycle.ACTIVE && it.type == IntentNodeType.ATOMIC_TASK }
        .filter { node ->
            val view = snapshot.statuses[node.id]
            val status = view?.derivedStatus
            status != DerivedStatus.DONE &&
                status != DerivedStatus.BLOCKED &&
                view?.unmetDependencyIds.isNullOrEmpty()
        }
        .sortedWith(priorityOrder)

/** Verification Ledger: active nodes parked in NEEDS_REVIEW, in the same order as the horizon. */
fun verificationLedger(snapshot: GraphSnapshot): List<IntentNodeRecord> =
    snapshot.nodes
        .filter { it.lifecycle == IntentLifecycle.ACTIVE && it.type != IntentNodeType.ACCEPTANCE_CRITERION }
        .filter { statusOf(snapshot, it.id) == DerivedStatus.NEEDS_REVIEW }
        .sortedWith(priorityOrder)

/** The acceptance criteria that sit under [nodeId], in the order the graph lists them. */
fun criteriaFor(nodeId: String, snapshot: GraphSnapshot): List<IntentNodeRecord> =
    OutlinerIndex(snapshot.nodes, snapshot.statuses).criteriaOf(nodeId)

/**
 * The type a new child of [parentType] gets (RFC-0111 Design 6): one level further down the outline.
 * Atomic tasks take atomic-task children; criteria and constraints take tasks.
 */
fun childTypeFor(parentType: IntentNodeType): IntentNodeType = when (parentType) {
    IntentNodeType.GROUP -> IntentNodeType.GOAL
    IntentNodeType.GOAL -> IntentNodeType.SUB_GOAL
    IntentNodeType.SUB_GOAL -> IntentNodeType.ATOMIC_TASK
    IntentNodeType.ATOMIC_TASK -> IntentNodeType.ATOMIC_TASK
    IntentNodeType.CONSTRAINT, IntentNodeType.ACCEPTANCE_CRITERION -> IntentNodeType.ATOMIC_TASK
}

/**
 * The row directly above [anchorId] that shares its parent, or null when there is none.
 * The scan stops at the first shallower row, because that row belongs to an outer level.
 */
fun previousSiblingId(anchorId: String, rows: List<OutlinerNodeUiModel>): String? {
    val index = rows.indexOfFirst { it.node.id == anchorId }
    if (index < 0) return null
    val anchor = rows[index]
    for (i in index - 1 downTo 0) {
        val row = rows[i]
        if (row.depth < anchor.depth) return null
        if (row.depth == anchor.depth && row.node.parentId == anchor.node.parentId) return row.node.id
    }
    return null
}

/** One line of the outliner list. Keys are unique across sections so that jumps can find a row. */
sealed interface OutlinerEntry {
    val key: String

    data class Header(val section: OutlinerSection, val count: Int, val collapsed: Boolean) : OutlinerEntry {
        override val key: String get() = "section:${section.name}"
    }

    data class Row(val section: OutlinerSection, val item: OutlinerNodeUiModel) : OutlinerEntry {
        override val key: String get() = "${section.keyPrefix}:${item.node.id}"
    }

    data class Draft(val draft: ChildDraft, val depth: Int, val parentTitle: String?) : OutlinerEntry {
        override val key: String get() = "draft"
    }

    data class Empty(val section: OutlinerSection) : OutlinerEntry {
        override val key: String get() = "empty:${section.name}"
    }
}

/** Flattens the presenter state into the list the screen shows. Pure, so tests can check the order. */
fun buildEntries(state: CausalOutlinerUiState): List<OutlinerEntry> {
    val entries = mutableListOf<OutlinerEntry>()
    val rowsBySection = listOf(
        OutlinerSection.ACTION_HORIZON to state.horizon,
        OutlinerSection.INTENT_TREE to state.visibleRows,
        OutlinerSection.VERIFICATION_LEDGER to state.ledger,
    )
    val draft = state.draft
    var draftPlaced = false
    for ((section, rows) in rowsBySection) {
        val collapsed = section in state.collapsedSections
        entries += OutlinerEntry.Header(section, rows.size, collapsed)
        if (collapsed) continue
        if (rows.isEmpty()) {
            entries += OutlinerEntry.Empty(section)
            continue
        }
        for (row in rows) {
            entries += OutlinerEntry.Row(section, row)
            if (!draftPlaced && draft != null && draft.anchorId == row.node.id) {
                draftPlaced = true
                val parent = state.visibleRows.firstOrNull { it.node.id == draft.parentId }
                val depth = if (parent != null) parent.depth + 1 else row.depth
                entries += OutlinerEntry.Draft(draft, depth, parent?.node?.title)
            }
        }
    }
    return entries
}

/** Index of the entry a jump to [nodeId] should scroll to, preferring the tree row. -1 when none is shown. */
fun jumpIndex(entries: List<OutlinerEntry>, nodeId: String): Int {
    // The tree row wins: it is where the prerequisite lives in the outline.
    for (prefix in listOf(OutlinerSection.INTENT_TREE, OutlinerSection.ACTION_HORIZON, OutlinerSection.VERIFICATION_LEDGER).map { it.keyPrefix }) {
        val index = entries.indexOfFirst { it.key == "$prefix:$nodeId" }
        if (index >= 0) return index
    }
    return -1
}
