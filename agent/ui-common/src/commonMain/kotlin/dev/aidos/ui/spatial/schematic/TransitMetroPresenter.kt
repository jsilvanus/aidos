package dev.aidos.ui.spatial.schematic

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** How a station is drawn for who does the work (RFC-0111 Design 2, decision U11). */
enum class MetroActorStyle { SOLID, RING, HALF_FILLED }

data class MetroStationUiModel(
    val node: IntentNodeRecord,
    val status: DerivedStatus,
    val isTransferHub: Boolean,
    val hasTurnstileGate: Boolean,
    /** Ids of the nodes this station DEPENDS_ON; a dependency on another line is a transfer. */
    val dependsOnNodeIds: List<String> = emptyList(),
)

data class MetroLineUiModel(
    val lineId: String,
    val title: String,
    val colorHex: Long,
    val stations: List<MetroStationUiModel>,
)

data class TransitMetroUiState(
    val lines: List<MetroLineUiModel> = emptyList(),
    val selectedStationId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface TransitMetroIntent {
    data object Refresh : TransitMetroIntent
    data class SelectStation(val nodeId: String?) : TransitMetroIntent
}

/**
 * Presenter for Design 2: The Transit Metro Map (RFC-0111).
 * Maps top-level GOAL/GROUP nodes to colour-coded metro lines, SUB_GOALs to transfer hubs,
 * ATOMIC_TASKs to stations, and ACCEPTANCE_CRITERIA to turnstile ticks on their task.
 * Reads the live [GraphSnapshot] (RFC-0124 UI-20).
 */
class TransitMetroPresenter(
    snapshot: StateFlow<GraphSnapshot>,
    scope: CoroutineScope,
) : MviPresenter<TransitMetroUiState, TransitMetroIntent> {

    private val _state = MutableStateFlow(TransitMetroUiState(isLoading = true))
    override val state: StateFlow<TransitMetroUiState> = _state.asStateFlow()

    init {
        scope.launch {
            snapshot.collect { snap ->
                val statusMap = snap.nodes.associate { node ->
                    node.id to (snap.statuses[node.id]?.derivedStatus ?: DerivedStatus.NOT_STARTED)
                }
                _state.update {
                    it.copy(
                        lines = buildMetroLines(snap.nodes, statusMap, snap.edges),
                        isLoading = snap.loadedAt == 0L && snap.error == null,
                        error = snap.error,
                    )
                }
            }
        }
    }

    override fun send(intent: TransitMetroIntent) {
        when (intent) {
            is TransitMetroIntent.Refresh -> Unit // the ProjectGraphStore refreshes itself
            is TransitMetroIntent.SelectStation -> _state.update { it.copy(selectedStationId = intent.nodeId) }
        }
    }

    companion object {
        /** Eight distinct line colours; a line takes the colour at its index modulo eight (RFC-0111 Design 2). */
        val LINE_PALETTE: List<Long> = listOf(
            0xFFEF4444L, // red
            0xFF3B82F6L, // blue
            0xFF10B981L, // green
            0xFFF59E0BL, // amber
            0xFFA855F7L, // violet
            0xFF06B6D4L, // cyan
            0xFFEC4899L, // pink
            0xFF84CC16L, // lime
        )

        private val STATION_TYPES = setOf(
            IntentNodeType.GOAL,
            IntentNodeType.SUB_GOAL,
            IntentNodeType.ATOMIC_TASK,
        )

        /**
         * One line per top-level GOAL or GROUP. A GOAL or GROUP is top-level when it has no parent in
         * [nodes]. Stations are the line's root plus its GOAL, SUB_GOAL and ATOMIC_TASK descendants,
         * ordered so a dependency comes before the node that depends on it. Nodes not under any
         * top-level GOAL or GROUP go on an "Unfiled" line rather than disappearing.
         */
        fun buildMetroLines(
            nodes: List<IntentNodeRecord>,
            statusMap: Map<String, DerivedStatus>,
            edges: List<IntentEdgeRecord> = emptyList(),
        ): List<MetroLineUiModel> {
            if (nodes.isEmpty()) return emptyList()
            val ids = nodes.map { it.id }.toSet()
            val byParent = nodes.groupBy { it.parentId }
            val dependencies = edges
                .filter { it.edgeKind == IntentEdgeKind.DEPENDS_ON }
                .groupBy({ it.fromNodeId }, { it.toNodeId })
            val parentsWithCriteria = nodes
                .filter { it.type == IntentNodeType.ACCEPTANCE_CRITERION }
                .mapNotNull { it.parentId }
                .toSet()
            val roots = nodes.filter { node ->
                // Cross-module property: no smart cast, so read the parent id into a local first.
                val parent = node.parentId
                (node.type == IntentNodeType.GROUP || node.type == IntentNodeType.GOAL) &&
                    (parent == null || parent !in ids)
            }

            val lines = mutableListOf<MetroLineUiModel>()
            val placed = mutableSetOf<String>()
            roots.forEach { root ->
                val members = listOf(root) + collectDescendants(root.id, byParent)
                placed += members.map { it.id }
                lines += MetroLineUiModel(
                    lineId = root.id,
                    title = root.title,
                    colorHex = paletteAt(lines.size),
                    stations = toStations(
                        members = members,
                        keep = { it.id == root.id || it.type in STATION_TYPES },
                        statusMap = statusMap,
                        dependencies = dependencies,
                        parentsWithCriteria = parentsWithCriteria,
                    ),
                )
            }

            val unfiled = nodes.filter { it.id !in placed && it.type in STATION_TYPES }
            if (unfiled.isNotEmpty()) {
                lines += MetroLineUiModel(
                    lineId = "unfiled",
                    title = "Unfiled",
                    colorHex = paletteAt(lines.size),
                    stations = toStations(unfiled, { true }, statusMap, dependencies, parentsWithCriteria),
                )
            }
            return lines
        }

        /** The station a train is waiting at: the first station on [line] that is not DONE. */
        fun currentStation(
            line: MetroLineUiModel,
            statuses: Map<String, DerivedStatus> = emptyMap(),
        ): MetroStationUiModel? = line.stations.firstOrNull {
            (statuses[it.node.id] ?: it.status) != DerivedStatus.DONE
        }

        /** The [count] stations that follow [current] along [line]. */
        fun upcomingStations(
            line: MetroLineUiModel,
            current: MetroStationUiModel?,
            count: Int = 3,
        ): List<MetroStationUiModel> {
            val start = if (current == null) {
                line.stations.size
            } else {
                line.stations.indexOfFirst { it.node.id == current.node.id } + 1
            }
            return line.stations.drop(start).take(count)
        }

        /** The other lines that [line] needs a transfer to, because one of its stations depends on them. */
        fun transfers(line: MetroLineUiModel, lines: List<MetroLineUiModel>): List<MetroLineUiModel> =
            transfersFrom(line.stations, line.lineId, lines)

        /** The other lines that [station] (on line [ownLineId]) needs a transfer to. */
        fun stationTransfers(
            station: MetroStationUiModel,
            ownLineId: String,
            lines: List<MetroLineUiModel>,
        ): List<MetroLineUiModel> = transfersFrom(listOf(station), ownLineId, lines)

        /** Drawing style for a station, from who does the work: HUMAN solid, AGENT ring, HYBRID half-filled. */
        fun stationStyle(node: IntentNodeRecord): MetroActorStyle = when (node.actorKind) {
            IntentActorKind.HUMAN -> MetroActorStyle.SOLID
            IntentActorKind.AGENT -> MetroActorStyle.RING
            IntentActorKind.HYBRID -> MetroActorStyle.HALF_FILLED
        }

        /** The signal dot after a station: green DONE, red BLOCKED, amber NEEDS_REVIEW, otherwise none. */
        fun signalHex(status: DerivedStatus): Long? = when (status) {
            DerivedStatus.DONE -> 0xFF22C55EL
            DerivedStatus.BLOCKED -> 0xFFEF4444L
            DerivedStatus.NEEDS_REVIEW -> 0xFFF59E0BL
            else -> null
        }

        private fun paletteAt(index: Int): Long = LINE_PALETTE[index % LINE_PALETTE.size]

        private fun transfersFrom(
            stations: List<MetroStationUiModel>,
            ownLineId: String,
            lines: List<MetroLineUiModel>,
        ): List<MetroLineUiModel> {
            val owner = lines.flatMap { line -> line.stations.map { it.node.id to line.lineId } }.toMap()
            return stations
                .flatMap { it.dependsOnNodeIds }
                .mapNotNull { owner[it] }
                .filter { it != ownLineId }
                .distinct()
                .mapNotNull { lineId -> lines.firstOrNull { it.lineId == lineId } }
        }

        private fun toStations(
            members: List<IntentNodeRecord>,
            keep: (IntentNodeRecord) -> Boolean,
            statusMap: Map<String, DerivedStatus>,
            dependencies: Map<String, List<String>>,
            parentsWithCriteria: Set<String>,
        ): List<MetroStationUiModel> = orderAlongDependencies(members, dependencies)
            .filter(keep)
            .map { node ->
                val status = statusMap[node.id] ?: DerivedStatus.NOT_STARTED
                MetroStationUiModel(
                    node = node,
                    status = status,
                    isTransferHub = node.type == IntentNodeType.SUB_GOAL || node.type == IntentNodeType.GOAL,
                    hasTurnstileGate = status == DerivedStatus.NEEDS_REVIEW || node.id in parentsWithCriteria,
                    dependsOnNodeIds = dependencies[node.id].orEmpty(),
                )
            }

        /**
         * Stable topological order: repeatedly take the earliest member whose in-group dependencies are
         * all placed. If dependencies form a cycle, the earliest unplaced member is taken so the line
         * still renders.
         */
        private fun orderAlongDependencies(
            members: List<IntentNodeRecord>,
            dependencies: Map<String, List<String>>,
        ): List<IntentNodeRecord> {
            val memberIds = members.map { it.id }.toSet()
            val waitingOn = members.associate { node ->
                node.id to dependencies[node.id].orEmpty().filter { it in memberIds && it != node.id }.toSet()
            }
            val placed = mutableSetOf<String>()
            val ordered = mutableListOf<IntentNodeRecord>()
            while (ordered.size < members.size) {
                val next = members.firstOrNull { it.id !in placed && waitingOn.getValue(it.id).all { dep -> dep in placed } }
                    ?: members.first { it.id !in placed }
                placed += next.id
                ordered += next
            }
            return ordered
        }

        private fun collectDescendants(
            parentId: String,
            byParent: Map<String?, List<IntentNodeRecord>>,
        ): List<IntentNodeRecord> {
            val result = mutableListOf<IntentNodeRecord>()
            val queue = ArrayDeque(byParent[parentId].orEmpty())
            val visited = mutableSetOf(parentId)
            while (queue.isNotEmpty()) {
                val current = queue.removeFirst()
                if (visited.add(current.id)) {
                    result.add(current)
                    byParent[current.id].orEmpty().forEach { queue.addLast(it) }
                }
            }
            return result
        }
    }
}
