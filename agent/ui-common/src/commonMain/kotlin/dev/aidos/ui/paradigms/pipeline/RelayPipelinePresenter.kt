package dev.aidos.ui.paradigms.pipeline

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentLifecycle
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

data class PipelineStageUiModel(
    val stageIndex: Int,
    val humanNodes: List<IntentNodeRecord>,
    val agentNodes: List<IntentNodeRecord>,
    val statuses: Map<String, IntentNodeStatusView>,
    val hasBatonCross: Boolean = false,
)

/** Which swim lane a node is drawn in (RFC-0111 Design 1). */
enum class RiverLane { HUMAN, AGENT, CHANNEL }

/** One node placed on the river: its lane, its dependency depth and the markers it carries. */
data class RiverItem(
    val node: IntentNodeRecord,
    val lane: RiverLane,
    val depth: Int,
    val status: DerivedStatus?,
    val constrained: Boolean, // has an outgoing CONSTRAINS edge: thin rail marker
    val accepted: Boolean,    // has an outgoing ACCEPTS edge: lock icon
)

/**
 * A DEPENDS_ON edge whose ends sit in different lanes. [upstreamId] is the prerequisite (the
 * `toNodeId` of the edge); [inTheAir] is true when the upstream is DONE and the downstream is not.
 */
data class BatonEdge(
    val upstreamId: String,
    val downstreamId: String,
    val upstreamLane: RiverLane,
    val downstreamLane: RiverLane,
    val inTheAir: Boolean,
)

enum class BatonKind { HANDED_OVER, AGENT_IN_FLIGHT }

/** What the pinned card in compact layout shows: a human task ready to take over, or agent work underway. */
data class CurrentBaton(val node: IntentNodeRecord, val kind: BatonKind)

data class RelayPipelineUiState(
    val stages: List<PipelineStageUiModel> = emptyList(),
    val items: List<RiverItem> = emptyList(),
    val batons: List<BatonEdge> = emptyList(),
    val currentBaton: CurrentBaton? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface RelayPipelineIntent {
    data object Refresh : RelayPipelineIntent
}

/** RFC-0124 amendment (UI-20): stages are derived from the live [GraphSnapshot]. UI-38 adds the river. */
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
                        items = riverItems(snap),
                        batons = batonEdges(snap),
                        currentBaton = currentBaton(snap),
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
        /** Stage grouping used before UI-38; kept because existing presenter tests read it. */
        fun buildStages(
            nodes: List<IntentNodeRecord>,
            statusMap: Map<String, IntentNodeStatusView>,
        ): List<PipelineStageUiModel> {
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

        /**
         * RFC-0111 Design 1: HYBRID nodes, acceptance criteria and NEEDS_REVIEW nodes sit in the
         * central Handoff channel; otherwise a HUMAN node is in the Human lane and everything else
         * in the Agent lane.
         */
        fun laneOf(node: IntentNodeRecord, status: DerivedStatus? = null): RiverLane = when {
            node.actorKind == IntentActorKind.HYBRID -> RiverLane.CHANNEL
            node.type == IntentNodeType.ACCEPTANCE_CRITERION -> RiverLane.CHANNEL
            status == DerivedStatus.NEEDS_REVIEW -> RiverLane.CHANNEL
            node.actorKind == IntentActorKind.HUMAN -> RiverLane.HUMAN
            else -> RiverLane.AGENT
        }

        /**
         * Dependency depth: 0 for a node with no prerequisites, otherwise one more than the deepest
         * prerequisite. A DEPENDS_ON edge `from -> to` means `from` needs `to`, so in a chain where B
         * needs A and C needs B, the depths are A=0, B=1, C=2 and work flows downstream.
         */
        fun depthLevels(snapshot: GraphSnapshot): Map<String, Int> {
            val ids = snapshot.nodes.map { it.id }.toSet()
            val prerequisites = HashMap<String, MutableList<String>>()
            snapshot.edges
                .filter { it.edgeKind == IntentEdgeKind.DEPENDS_ON && it.fromNodeId in ids && it.toNodeId in ids }
                .forEach { prerequisites.getOrPut(it.fromNodeId) { mutableListOf() }.add(it.toNodeId) }

            val memo = HashMap<String, Int>()
            fun depth(id: String, visiting: MutableSet<String>): Int {
                memo[id]?.let { return it }
                // Cycles are refused by the intent graph; the guard only stops a corrupt store from recursing forever.
                if (!visiting.add(id)) return 0
                val d = prerequisites[id]?.maxOfOrNull { depth(it, visiting) + 1 } ?: 0
                visiting.remove(id)
                memo[id] = d
                return d
            }
            return snapshot.nodes.associate { it.id to depth(it.id, mutableSetOf()) }
        }

        /** Every DEPENDS_ON edge whose two ends are in different lanes. */
        fun batonEdges(snapshot: GraphSnapshot): List<BatonEdge> {
            val byId = snapshot.nodes.associateBy { it.id }
            return snapshot.edges
                .filter { it.edgeKind == IntentEdgeKind.DEPENDS_ON }
                .mapNotNull { edge ->
                    val upstream = byId[edge.toNodeId] ?: return@mapNotNull null
                    val downstream = byId[edge.fromNodeId] ?: return@mapNotNull null
                    val upStatus = snapshot.statuses[upstream.id]?.derivedStatus
                    val downStatus = snapshot.statuses[downstream.id]?.derivedStatus
                    val upLane = laneOf(upstream, upStatus)
                    val downLane = laneOf(downstream, downStatus)
                    if (upLane == downLane) {
                        null
                    } else {
                        BatonEdge(
                            upstreamId = upstream.id,
                            downstreamId = downstream.id,
                            upstreamLane = upLane,
                            downstreamLane = downLane,
                            inTheAir = upStatus == DerivedStatus.DONE && downStatus != DerivedStatus.DONE,
                        )
                    }
                }
        }

        /**
         * The compact view's pinned card. Prefers the highest-priority HUMAN node that is ready (not
         * DONE, all prerequisites DONE, at least one prerequisite) and whose most recently changed
         * prerequisite is an AGENT node: the agent handed it over. Otherwise shows the highest-priority
         * agent task in flight. Lower `priority` values come first, as in the Action Inbox.
         */
        fun currentBaton(snapshot: GraphSnapshot): CurrentBaton? {
            val byId = snapshot.nodes.associateBy { it.id }
            fun statusOf(id: String) = snapshot.statuses[id]?.derivedStatus
            val prerequisitesOf = HashMap<String, List<IntentNodeRecord>>()
            snapshot.edges
                .filter { it.edgeKind == IntentEdgeKind.DEPENDS_ON }
                .forEach { edge ->
                    val prerequisite = byId[edge.toNodeId] ?: return@forEach
                    prerequisitesOf[edge.fromNodeId] = (prerequisitesOf[edge.fromNodeId] ?: emptyList()) + prerequisite
                }
            val order = compareBy<IntentNodeRecord>({ it.priority }, { it.id })

            val handedOver = snapshot.nodes
                .filter { it.lifecycle == IntentLifecycle.ACTIVE && it.actorKind == IntentActorKind.HUMAN }
                .filter { statusOf(it.id) != DerivedStatus.DONE }
                .filter { node ->
                    val prerequisites = prerequisitesOf[node.id].orEmpty()
                    prerequisites.isNotEmpty() && prerequisites.all { statusOf(it.id) == DerivedStatus.DONE }
                }
                .filter { node ->
                    val latest = prerequisitesOf[node.id].orEmpty().maxWithOrNull(compareBy<IntentNodeRecord> { it.modifiedAt })
                    latest?.actorKind == IntentActorKind.AGENT
                }
                .minWithOrNull(order)
            if (handedOver != null) return CurrentBaton(handedOver, BatonKind.HANDED_OVER)

            val inFlight = snapshot.nodes
                .filter { it.lifecycle == IntentLifecycle.ACTIVE && it.actorKind == IntentActorKind.AGENT }
                .filter { statusOf(it.id) == DerivedStatus.IN_PROGRESS }
                .minWithOrNull(order)
            return inFlight?.let { CurrentBaton(it, BatonKind.AGENT_IN_FLIGHT) }
        }

        /** Every node placed in a lane and a depth, ordered by depth, then priority, then id. */
        fun riverItems(snapshot: GraphSnapshot): List<RiverItem> {
            val depths = depthLevels(snapshot)
            val constrained = snapshot.edges.filter { it.edgeKind == IntentEdgeKind.CONSTRAINS }.map { it.fromNodeId }.toSet()
            val accepted = snapshot.edges.filter { it.edgeKind == IntentEdgeKind.ACCEPTS }.map { it.fromNodeId }.toSet()
            return snapshot.nodes
                .map { node ->
                    val status = snapshot.statuses[node.id]?.derivedStatus
                    RiverItem(
                        node = node,
                        lane = laneOf(node, status),
                        depth = depths[node.id] ?: 0,
                        status = status,
                        constrained = node.id in constrained,
                        accepted = node.id in accepted,
                    )
                }
                .sortedWith(compareBy({ it.depth }, { it.node.priority }, { it.node.id }))
        }
    }
}
