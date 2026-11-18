package dev.aidos.ui.paradigms.timeline

import dev.aidos.intent.DerivedStatus
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn

/** RFC-0112 Timeline modes: by due dates, or by dependency depth (the original Gantt). */
enum class TimelineMode { DATES, SEQUENCE }

data class TimelineBarUiModel(
    val node: IntentNodeRecord,
    val status: DerivedStatus,
    val startSlot: Int,
    val durationSlots: Int,
    val dependencyCount: Int,
)

/**
 * One bar in Dates mode. Days are epoch days. The bar covers `[startDay, dueDay + 1)`, so a task due
 * today still has a visible width. [overdue] is `dueAt` before today and not DONE.
 */
data class DateBarUiModel(
    val node: IntentNodeRecord,
    val status: DerivedStatus,
    val startDay: Int,
    val dueDay: Int,
    val goalId: String?,
    val goalTitle: String?,
    val overdue: Boolean,
    val recurring: Boolean,
)

data class DateTimelineModel(
    val today: LocalDate,
    /** Sorted by goal (nodes without a goal last), then start day. */
    val bars: List<DateBarUiModel>,
    /** Dependency edges (from, to) where both nodes have a bar. */
    val dependencies: List<Pair<String, String>>,
    /** Nodes with no `dueAt`, listed under the chart. */
    val unscheduled: List<IntentNodeRecord>,
    val rangeStartDay: Int,
    val rangeEndDay: Int,
)

data class TimelineGanttUiState(
    val mode: TimelineMode = TimelineMode.SEQUENCE,
    val hasDueDates: Boolean = false,
    val bars: List<TimelineBarUiModel> = emptyList(),
    val totalSlots: Int = 1,
    val dates: DateTimelineModel? = null,
    val selectedNodeId: String? = null,
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface TimelineGanttIntent {
    data object Refresh : TimelineGanttIntent
    data class SelectNode(val nodeId: String?) : TimelineGanttIntent
    data class SetMode(val mode: TimelineMode) : TimelineGanttIntent
}

/**
 * Presenter for the Timeline view (RFC-0112 §2.1, RFC-0124 UI-37). Two modes: Dates (bars placed by
 * `createdAt` and `dueAt`) and Sequence (dependency depth). Reads the live [GraphSnapshot].
 */
class TimelineGanttPresenter(
    snapshot: StateFlow<GraphSnapshot>,
    scope: CoroutineScope,
    private val today: () -> LocalDate = { Clock.System.todayIn(TimeZone.currentSystemDefault()) },
) : MviPresenter<TimelineGanttUiState, TimelineGanttIntent> {

    private val _state = MutableStateFlow(TimelineGanttUiState(isLoading = true))
    override val state: StateFlow<TimelineGanttUiState> = _state.asStateFlow()

    /** The user's toggle; null means "pick Dates when any node has a due date, else Sequence". */
    private val modeOverride = MutableStateFlow<TimelineMode?>(null)

    init {
        scope.launch {
            combine(snapshot, modeOverride) { snap, override -> snap to override }.collect { (snap, override) ->
                val statusMap = snap.nodes.associate { node ->
                    node.id to (snap.statuses[node.id]?.derivedStatus ?: DerivedStatus.NOT_STARTED)
                }
                val bars = buildTimelineBars(snap.nodes, snap.edges, statusMap)
                val maxEnd = bars.maxOfOrNull { it.startSlot + it.durationSlots } ?: 1
                val hasDue = snap.nodes.any { parseDay(it.dueAt) != null }
                val mode = override ?: if (hasDue) TimelineMode.DATES else TimelineMode.SEQUENCE
                val dates = buildDateTimeline(snap.nodes, snap.edges, statusMap, today())
                _state.update {
                    it.copy(
                        mode = mode,
                        hasDueDates = hasDue,
                        bars = bars,
                        totalSlots = maxEnd.coerceAtLeast(1),
                        dates = dates,
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
            is TimelineGanttIntent.SetMode -> modeOverride.value = intent.mode
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

        /**
         * Dates mode model. Only nodes with a parseable `dueAt` get a bar. A bar starts at the later of
         * the node's `createdAt` and the latest `dueAt` among its dependencies (clamped so it never
         * starts after its own due day) and ends at its own `dueAt`. The range runs from the earliest
         * start to the latest due day plus three days.
         */
        fun buildDateTimeline(
            nodes: List<IntentNodeRecord>,
            edges: List<IntentEdgeRecord>,
            statusMap: Map<String, DerivedStatus>,
            today: LocalDate,
        ): DateTimelineModel {
            val todayDay = today.toEpochDays()
            val dueDays = nodes.mapNotNull { node -> parseDay(node.dueAt)?.let { node.id to it } }.toMap()
            val byId = nodes.associateBy { it.id }
            val nodeIds = byId.keys
            val depsByTarget = edges
                .filter { it.edgeKind == IntentEdgeKind.DEPENDS_ON && it.fromNodeId in nodeIds && it.toNodeId in nodeIds }
                .groupBy({ it.toNodeId }, { it.fromNodeId })

            val scheduled = nodes.filter { it.id in dueDays }
            val bars = scheduled.map { node ->
                val dueDay = dueDays.getValue(node.id)
                val createdDay = parseDay(node.createdAt) ?: dueDay
                val latestDependencyDue = depsByTarget[node.id].orEmpty().mapNotNull { dueDays[it] }.maxOrNull()
                val startDay = maxOf(createdDay, latestDependencyDue ?: createdDay).coerceAtMost(dueDay)
                val goal = goalOf(node, byId)
                val status = statusMap[node.id] ?: DerivedStatus.NOT_STARTED
                DateBarUiModel(
                    node = node,
                    status = status,
                    startDay = startDay,
                    dueDay = dueDay,
                    goalId = goal?.id,
                    goalTitle = goal?.title,
                    overdue = dueDay < todayDay && status != DerivedStatus.DONE,
                    recurring = node.recurrenceJson != null,
                )
            }.sortedWith(
                compareBy<DateBarUiModel>({ it.goalId == null }, { it.goalTitle.orEmpty() }, { it.startDay }, { it.node.title }),
            )

            val shown = bars.map { it.node.id }.toSet()
            val dependencies = edges
                .filter { it.edgeKind == IntentEdgeKind.DEPENDS_ON && it.fromNodeId in shown && it.toNodeId in shown }
                .map { it.fromNodeId to it.toNodeId }

            val unscheduled = nodes.filter { it.id !in dueDays }.sortedBy { it.title }
            val rangeStart = bars.minOfOrNull { it.startDay } ?: todayDay
            val rangeEnd = bars.maxOfOrNull { it.dueDay + 3 }?.coerceAtLeast(rangeStart + 1) ?: (rangeStart + 1)

            return DateTimelineModel(
                today = today,
                bars = bars,
                dependencies = dependencies,
                unscheduled = unscheduled,
                rangeStartDay = rangeStart,
                rangeEndDay = rangeEnd,
            )
        }

        /** Agenda for compact screens: bars grouped by due day, earliest first. */
        fun buildAgenda(bars: List<DateBarUiModel>): List<AgendaDay> =
            bars.groupBy { it.dueDay }
                .toSortedMap()
                .map { (day, items) ->
                    AgendaDay(LocalDate.fromEpochDays(day), items.sortedBy { it.node.title })
                }

        /** The date part of an ISO string (`2026-11-20` or `2026-11-20T…`), as an epoch day. */
        internal fun parseDay(iso: String?): Int? =
            iso?.take(10)?.let { text -> runCatching { LocalDate.parse(text).toEpochDays() }.getOrNull() }

        /** Nearest GOAL on the node's parent chain, the node itself included. */
        private fun goalOf(node: IntentNodeRecord, byId: Map<String, IntentNodeRecord>): IntentNodeRecord? {
            var current: IntentNodeRecord? = node
            val seen = mutableSetOf<String>()
            while (current != null && seen.add(current.id)) {
                if (current.type == IntentNodeType.GOAL) return current
                current = current.parentId?.let { byId[it] }
            }
            return null
        }
    }
}

data class AgendaDay(val day: LocalDate, val items: List<DateBarUiModel>)
