package dev.aidos.ui.inspector

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.flow.Flow

/** A node reference shown in the inspector: enough to read it and to jump to or remove it. */
data class InspectorRef(val id: String, val title: String)

/**
 * Everything the Node Inspector shows for one node (RFC-0124, UI-21). Derived purely from a
 * [GraphSnapshot], so every view that opens the inspector shows the same thing.
 */
data class NodeInspectorState(
    val node: IntentNodeRecord,
    val status: DerivedStatus,
    val effectivePolicy: EffectiveDispatchPolicy?,
    val unmetDependencies: List<InspectorRef>,
    val dependents: List<InspectorRef>,
    val criteria: List<InspectorRef>,
    val constraints: List<InspectorRef>,
    val activeRunIds: List<String>,
    val canStart: Boolean,
    val startBlockedReason: String?,
    val canComplete: Boolean,
    val completeBlockedReason: String?,
    val canConfirm: Boolean,
    val confirmBlockedReason: String?,
)

/**
 * Reads one node out of the shared graph store. Views call `controller.openInspector(nodeId)`;
 * the inspector composable gets its state from here. Pending proposals are not listed: a proposal
 * stores its operations as opaque strings, so it cannot be tied to a node without parsing them.
 */
class NodeInspectorPresenter(
    /** The current project's graph; the inspector reads the node and the other nodes from it. */
    val snapshot: Flow<GraphSnapshot>,
    val nodeId: String,
)

/** Pure: the inspector state for [nodeId], or `null` when the snapshot has no such node. */
fun buildInspectorState(snapshot: GraphSnapshot, nodeId: String): NodeInspectorState? {
    val node = snapshot.nodes.firstOrNull { it.id == nodeId } ?: return null
    val byId = snapshot.nodes.associateBy { it.id }
    val view = snapshot.statuses[nodeId]
    val status = view?.derivedStatus ?: DerivedStatus.NOT_STARTED

    fun ref(id: String): InspectorRef? = byId[id]?.let { InspectorRef(it.id, it.title) }

    val dependsOn = snapshot.edges.filter { it.fromNodeId == nodeId && it.edgeKind == IntentEdgeKind.DEPENDS_ON }
    // Same rule as the runtime's unmetDependencies: a target that is missing or not DONE is unmet.
    val unmet = dependsOn.mapNotNull { edge -> ref(edge.toNodeId) }
        .filter { snapshot.statuses[it.id]?.derivedStatus != DerivedStatus.DONE }

    val dependents = snapshot.edges
        .filter { it.toNodeId == nodeId && it.edgeKind == IntentEdgeKind.DEPENDS_ON }
        .mapNotNull { ref(it.fromNodeId) }

    // Criteria come from both places a criterion can live: as a child node, or linked by ACCEPTS.
    val criteriaIds = LinkedHashSet<String>()
    snapshot.nodes.filter { it.parentId == nodeId && it.type == IntentNodeType.ACCEPTANCE_CRITERION }
        .forEach { criteriaIds += it.id }
    snapshot.edges.filter { it.fromNodeId == nodeId && it.edgeKind == IntentEdgeKind.ACCEPTS }
        .forEach { criteriaIds += it.toNodeId }
    val criteria = criteriaIds.mapNotNull { ref(it) }

    val constraintIds = LinkedHashSet<String>()
    snapshot.nodes.filter { it.parentId == nodeId && it.type == IntentNodeType.CONSTRAINT }
        .forEach { constraintIds += it.id }
    snapshot.edges.filter { it.fromNodeId == nodeId && it.edgeKind == IntentEdgeKind.CONSTRAINS }
        .forEach { constraintIds += it.toNodeId }
    val constraints = constraintIds.mapNotNull { ref(it) }

    val agentCapable = node.actorKind == IntentActorKind.AGENT || node.actorKind == IntentActorKind.HYBRID
    val startReason = when {
        !agentCapable -> "A person does this task, so no agent run can start on it."
        status == DerivedStatus.DONE -> "This task is already done."
        status == DerivedStatus.BLOCKED -> "Waiting on ${unmet.size.let { if (it == 1) "one dependency" else "$it dependencies" }}."
        status == DerivedStatus.CONFLICTED -> "Resolve the conflict before starting a run."
        else -> null
    }
    val completeReason = when {
        node.actorKind != IntentActorKind.HUMAN -> "Only tasks a person does can be marked done here."
        status == DerivedStatus.DONE -> "This task is already done."
        else -> null
    }
    val confirmReason = if (status != DerivedStatus.NEEDS_REVIEW) "Nothing to confirm: the task is not waiting for review." else null

    return NodeInspectorState(
        node = node,
        status = status,
        effectivePolicy = view?.effectiveDispatchPolicy,
        unmetDependencies = unmet,
        dependents = dependents,
        criteria = criteria,
        constraints = constraints,
        activeRunIds = view?.activeRunIds ?: emptyList(),
        canStart = startReason == null,
        startBlockedReason = startReason,
        canComplete = completeReason == null,
        completeBlockedReason = completeReason,
        canConfirm = confirmReason == null,
        confirmBlockedReason = confirmReason,
    )
}
