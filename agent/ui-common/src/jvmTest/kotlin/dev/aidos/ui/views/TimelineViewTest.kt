package dev.aidos.ui.views

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.ui.paradigms.timeline.TimelineGanttIntent
import dev.aidos.ui.paradigms.timeline.TimelineGanttPresenter
import dev.aidos.ui.paradigms.timeline.TimelineMode
import dev.aidos.ui.testSnapshot
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TimelineViewTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    private val today = LocalDate(2026, 11, 18)
    private fun day(iso: String) = LocalDate.parse(iso).toEpochDays()

    @Test
    fun defaultModeIsDatesWhenAnyNodeHasADueDateAndSequenceOtherwise() {
        val withDue = MutableStateFlow(snapshot(listOf(node("a", dueAt = "2026-11-20"), node("b"))))
        val dated = TimelineGanttPresenter(withDue, scope) { today }
        assertEquals(TimelineMode.DATES, dated.state.value.mode)
        assertTrue(dated.state.value.hasDueDates)

        val undated = MutableStateFlow(snapshot(listOf(node("a"), node("b"))))
        val sequenced = TimelineGanttPresenter(undated, scope) { today }
        assertEquals(TimelineMode.SEQUENCE, sequenced.state.value.mode)
        assertFalse(sequenced.state.value.hasDueDates)
    }

    @Test
    fun userChosenModeWinsOverTheDefault() {
        val store = MutableStateFlow(snapshot(listOf(node("a", dueAt = "2026-11-20"))))
        val presenter = TimelineGanttPresenter(store, scope) { today }
        presenter.send(TimelineGanttIntent.SetMode(TimelineMode.SEQUENCE))
        assertEquals(TimelineMode.SEQUENCE, presenter.state.value.mode)
        presenter.send(TimelineGanttIntent.SetMode(TimelineMode.DATES))
        assertEquals(TimelineMode.DATES, presenter.state.value.mode)
    }

    @Test
    fun barStartsAtTheLatestDependencyDueDateAndEndsAtItsOwnDueDate() {
        val first = node("first", dueAt = "2026-11-20")
        val second = node("second", createdAt = "2026-11-10T00:00:00Z", dueAt = "2026-11-25")
        val model = buildDates(
            listOf(first, second),
            listOf(edge("e", from = "first", to = "second")),
        )
        val bar = model.bars.first { it.node.id == "second" }
        assertEquals(day("2026-11-20"), bar.startDay)
        assertEquals(day("2026-11-25"), bar.dueDay)
        assertEquals(listOf("first" to "second"), model.dependencies)
    }

    @Test
    fun barStartsAtCreationWhenItIsLaterThanTheDependencies() {
        val dependency = node("dep", dueAt = "2026-11-01")
        val task = node("task", createdAt = "2026-11-22T09:00:00Z", dueAt = "2026-11-25")
        val model = buildDates(listOf(dependency, task), listOf(edge("e", from = "dep", to = "task")))
        assertEquals(day("2026-11-22"), model.bars.first { it.node.id == "task" }.startDay)
    }

    @Test
    fun nodesWithoutDueDateAreListedAsUnscheduledAndHaveNoBar() {
        val dated = node("dated", dueAt = "2026-11-20")
        val loose = node("loose", title = "Loose idea")
        val model = buildDates(listOf(dated, loose), emptyList())
        assertEquals(listOf("dated"), model.bars.map { it.node.id })
        assertEquals(listOf("loose"), model.unscheduled.map { it.id })
    }

    @Test
    fun rangeRunsFromTheEarliestCreationToTheLatestDueDatePlusThreeDays() {
        val a = node("a", createdAt = "2026-11-05T00:00:00Z", dueAt = "2026-11-20")
        val b = node("b", createdAt = "2026-11-12T00:00:00Z", dueAt = "2026-11-30")
        val model = buildDates(listOf(a, b), emptyList())
        assertEquals(day("2026-11-05"), model.rangeStartDay)
        assertEquals(day("2026-11-30") + 3, model.rangeEndDay)
    }

    @Test
    fun overdueFlagIsSetOnlyForUnfinishedTasksDueBeforeToday() {
        val late = node("late", dueAt = "2026-11-17")
        val lateDone = node("lateDone", dueAt = "2026-11-17")
        val dueToday = node("today", dueAt = "2026-11-18")
        val future = node("future", dueAt = "2026-11-19")
        val statuses = mapOf(
            "late" to DerivedStatus.IN_PROGRESS,
            "lateDone" to DerivedStatus.DONE,
            "today" to DerivedStatus.NOT_STARTED,
            "future" to DerivedStatus.NOT_STARTED,
        )
        val model = TimelineGanttPresenter.buildDateTimeline(
            listOf(late, lateDone, dueToday, future), emptyList(), statuses, today,
        )
        val overdue = model.bars.associate { it.node.id to it.overdue }
        assertEquals(mapOf("late" to true, "lateDone" to false, "today" to false, "future" to false), overdue)
    }

    @Test
    fun agendaGroupsTasksByDueDayInOrder() {
        val model = buildDates(
            listOf(
                node("b", title = "Beta", dueAt = "2026-11-20"),
                node("a", title = "Alpha", dueAt = "2026-11-20"),
                node("c", title = "Gamma", dueAt = "2026-11-19"),
            ),
            emptyList(),
        )
        val agenda = TimelineGanttPresenter.buildAgenda(model.bars)
        assertEquals(listOf(LocalDate(2026, 11, 19), LocalDate(2026, 11, 20)), agenda.map { it.day })
        assertEquals(listOf("Gamma"), agenda[0].items.map { it.node.title })
        assertEquals(listOf("Alpha", "Beta"), agenda[1].items.map { it.node.title })
    }

    @Test
    fun barsCarryTheirNearestGoalAndRecurrenceMark() {
        val goal = node("goal", title = "Release 1", type = IntentNodeType.GOAL)
        val task = node("task", dueAt = "2026-11-20", parentId = "goal", recurrence = """{"every":"week"}""")
        val loose = node("loose", dueAt = "2026-11-21")
        val model = buildDates(listOf(goal, task, loose), emptyList())
        val taskBar = model.bars.first { it.node.id == "task" }
        assertEquals("Release 1", taskBar.goalTitle)
        assertTrue(taskBar.recurring)
        assertNull(model.bars.first { it.node.id == "loose" }.goalId)
        assertEquals("loose", model.bars.last().node.id, "tasks without a goal sort last")
    }

    private fun buildDates(nodes: List<IntentNodeRecord>, edges: List<IntentEdgeRecord>) =
        TimelineGanttPresenter.buildDateTimeline(nodes, edges, nodes.associate { it.id to DerivedStatus.NOT_STARTED }, today)

    private fun snapshot(nodes: List<IntentNodeRecord>): GraphSnapshot = testSnapshot(
        projectId = "p",
        nodes = nodes,
        statuses = nodes.associate { it.id to view(it) },
    )

    private fun view(node: IntentNodeRecord) = IntentNodeStatusView(
        node = node,
        derivedStatus = DerivedStatus.NOT_STARTED,
        effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
        unmetDependencyIds = emptyList(),
        activeRunIds = emptyList(),
        pendingPlatformChecks = false,
    )

    private fun node(
        id: String,
        title: String = id,
        type: IntentNodeType = IntentNodeType.ATOMIC_TASK,
        createdAt: String = "2026-11-10T00:00:00Z",
        dueAt: String? = null,
        parentId: String? = null,
        recurrence: String? = null,
    ) = IntentNodeRecord(
        id = id, projectId = "p", type = type, title = title,
        granularity = IntentGranularity.ATOMIC, lifecycle = IntentLifecycle.ACTIVE,
        actorKind = IntentActorKind.AGENT, dispatchPolicy = NodeDispatchPolicy.MANUAL_START,
        targetProfile = IntentTargetProfile.ANY, minLocality = IntentMinLocality.LOCAL_ONLY,
        parentId = parentId, dueAt = dueAt, recurrenceJson = recurrence,
        createdAt = createdAt, createdByKind = "USER", createdById = "u",
        modifiedAt = createdAt, modifiedByKind = "USER", modifiedById = "u",
    )

    private fun edge(id: String, from: String, to: String) = IntentEdgeRecord(
        id = id, projectId = "p", fromNodeId = from, toNodeId = to,
        edgeKind = IntentEdgeKind.DEPENDS_ON, createdAt = "2026-11-10T00:00:00Z",
    )
}
