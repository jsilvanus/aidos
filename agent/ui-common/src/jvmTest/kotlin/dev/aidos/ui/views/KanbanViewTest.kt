package dev.aidos.ui.views

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.ui.testSnapshot
import dev.aidos.ui.paradigms.kanban.CartridgeDockPresenter
import dev.aidos.ui.paradigms.kanban.CartridgeDockPresenter.Companion.build
import dev.aidos.ui.paradigms.kanban.CartridgeDockUiState
import dev.aidos.ui.paradigms.kanban.DockAction
import dev.aidos.ui.paradigms.kanban.DockLane
import dev.aidos.ui.paradigms.kanban.buildSwimlanes
import dev.aidos.ui.paradigms.kanban.deckFilter
import dev.aidos.ui.paradigms.kanban.dispatchLabel
import dev.aidos.ui.paradigms.kanban.dropAction
import dev.aidos.ui.paradigms.kanban.laneFor
import dev.aidos.ui.paradigms.kanban.toggledDispatch
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0111 Design 3 / RFC-0124 UI-36: the Cartridge Dock rules, tested without Compose. */
class KanbanViewTest {

    private fun node(
        id: String,
        type: IntentNodeType = IntentNodeType.ATOMIC_TASK,
        actor: IntentActorKind = IntentActorKind.AGENT,
        parentId: String? = null,
        target: IntentTargetProfile? = null,
        priority: Int = 100,
        lifecycle: IntentLifecycle = IntentLifecycle.ACTIVE,
    ) = IntentNodeRecord(
        id = id, projectId = "p", type = type, title = "Title $id", actorKind = actor, parentId = parentId,
        targetProfile = target, priority = priority, lifecycle = lifecycle,
        granularity = IntentGranularity.ATOMIC, dispatchPolicy = NodeDispatchPolicy.MANUAL_START,
        createdAt = "2026-11-18T00:00:00Z", createdByKind = "USER", createdById = "u",
        modifiedAt = "2026-11-18T00:00:00Z", modifiedByKind = "USER", modifiedById = "u",
    )

    private fun view(node: IntentNodeRecord, status: DerivedStatus, unmet: List<String> = emptyList()) =
        IntentNodeStatusView(
            node = node,
            derivedStatus = status,
            effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
            unmetDependencyIds = unmet,
            activeRunIds = emptyList(),
            pendingPlatformChecks = false,
        )

    private fun dependsOn(from: String, to: String) = IntentEdgeRecord(
        id = "$from-$to", projectId = "p", fromNodeId = from, toNodeId = to,
        edgeKind = IntentEdgeKind.DEPENDS_ON, createdAt = "2026-11-18T00:00:00Z",
    )

    private fun laneOf(state: CartridgeDockUiState, id: String): DockLane? =
        state.columns.firstOrNull { col -> col.tasks.any { it.first.id == id } }?.lane

    @Test
    fun eachStatusLandsInItsColumn() {
        assertEquals(DockLane.BLOCKED, laneFor(DerivedStatus.BLOCKED, 0))
        assertEquals(DockLane.READY, laneFor(DerivedStatus.NOT_STARTED, 0))
        assertEquals(DockLane.IN_FLIGHT, laneFor(DerivedStatus.IN_PROGRESS, 0))
        assertEquals(DockLane.VERIFY, laneFor(DerivedStatus.NEEDS_REVIEW, 0))
        assertEquals(DockLane.DONE, laneFor(DerivedStatus.DONE, 0))
        assertNull(laneFor(DerivedStatus.STALE, 0))
        assertNull(laneFor(DerivedStatus.CONFLICTED, 0))
    }

    @Test
    fun readyRequiresNoUnmetDependencies() {
        assertEquals(DockLane.BLOCKED, laneFor(DerivedStatus.NOT_STARTED, 1))

        val a = node("a")
        val b = node("b")
        val state = build(
            testSnapshot(
                "p",
                nodes = listOf(a, b),
                edges = listOf(dependsOn("b", "a")),
                statuses = mapOf(
                    "a" to view(a, DerivedStatus.NOT_STARTED),
                    "b" to view(b, DerivedStatus.NOT_STARTED, unmet = listOf("a")),
                ),
            ),
        )
        assertEquals(DockLane.READY, laneOf(state, "a"))
        assertEquals(DockLane.BLOCKED, laneOf(state, "b"))
        assertEquals(1, state.unmet["b"])
    }

    @Test
    fun staleAndConflictedGoToTheAttentionStrip() {
        val s = node("s")
        val c = node("c")
        val state = build(
            testSnapshot(
                "p",
                nodes = listOf(s, c),
                statuses = mapOf("s" to view(s, DerivedStatus.STALE), "c" to view(c, DerivedStatus.CONFLICTED)),
            ),
        )
        assertEquals(setOf("s", "c"), state.attention.map { it.first.id }.toSet())
        assertTrue(state.columns.all { col -> col.tasks.isEmpty() })
    }

    @Test
    fun swimlanesGroupByNearestGoalWithNoGoalLast() {
        val goal = node("g1", type = IntentNodeType.GOAL)
        val sub = node("s1", type = IntentNodeType.SUB_GOAL, parentId = "g1")
        val deep = node("t1", parentId = "s1")
        val direct = node("t2", parentId = "g1")
        val orphan = node("t3")
        val byId = listOf(goal, sub, deep, direct, orphan).associateBy { it.id }
        val tasks = mapOf(
            "t1" to (deep to DockLane.READY),
            "t2" to (direct to DockLane.IN_FLIGHT),
            "t3" to (orphan to DockLane.READY),
        )
        val lanes = buildSwimlanes(tasks, byId)
        assertEquals(listOf<String?>("g1", null), lanes.map { it.goalId })
        assertEquals("No goal", lanes.last().title)
        val first = lanes.first()
        assertEquals(listOf("t1"), first.cells.getValue(DockLane.READY).map { it.id })
        assertEquals(listOf("t2"), first.cells.getValue(DockLane.IN_FLIGHT).map { it.id })
        assertEquals(DockLane.entries.size, first.cells.size)
    }

    @Test
    fun agentReadyCardStartsARun() {
        val t = node("t", actor = IntentActorKind.AGENT)
        assertEquals(DockAction.Start("t"), dropAction(t, DockLane.READY, DockLane.IN_FLIGHT))
        val h = node("h", actor = IntentActorKind.HYBRID)
        assertEquals(DockAction.Start("h"), dropAction(h, DockLane.READY, DockLane.IN_FLIGHT))
    }

    @Test
    fun humanCardCompletesFromAnyColumn() {
        val human = node("u", actor = IntentActorKind.HUMAN)
        for (from in listOf(DockLane.BLOCKED, DockLane.READY, DockLane.IN_FLIGHT, DockLane.VERIFY)) {
            assertEquals(DockAction.Complete("u"), dropAction(human, from, DockLane.DONE), "from $from")
        }
    }

    @Test
    fun verifyDockConfirmsImplementation() {
        val t = node("t")
        assertEquals(DockAction.Confirm("t"), dropAction(t, DockLane.VERIFY, DockLane.DONE))
    }

    @Test
    fun otherDropsSnapBackWithAReason() {
        val agent = node("t", actor = IntentActorKind.AGENT)
        val human = node("h", actor = IntentActorKind.HUMAN)

        assertEquals(DockAction.Refuse("Only ready tasks can start"), dropAction(agent, DockLane.BLOCKED, DockLane.IN_FLIGHT))
        assertEquals(DockAction.Refuse("Only human tasks or verified work can be marked done"), dropAction(agent, DockLane.IN_FLIGHT, DockLane.DONE))
        assertEquals(DockAction.Refuse("Human tasks are completed, not started"), dropAction(human, DockLane.READY, DockLane.IN_FLIGHT))
        assertEquals(DockAction.Refuse("Done tasks stay done"), dropAction(agent, DockLane.DONE, DockLane.READY))
        assertEquals(DockAction.Refuse("Blocked by 2 tasks"), dropAction(agent, DockLane.READY, DockLane.IN_FLIGHT, unmetDependencies = 2))
        assertEquals(DockAction.Refuse("Blocked by 1 task"), dropAction(human, DockLane.READY, DockLane.DONE, unmetDependencies = 1))
        assertEquals(DockAction.NoOp, dropAction(agent, DockLane.READY, DockLane.READY))
        assertTrue(dropAction(agent, DockLane.READY, DockLane.BLOCKED) is DockAction.Refuse)
        assertTrue(dropAction(agent, DockLane.READY, DockLane.VERIFY) is DockAction.Refuse)
    }

    @Test
    fun deckShowsPhoneAndAnyCartridgesUnlessAll() {
        val phone = node("p", target = IntentTargetProfile.MOBILE)
        val any = node("a", target = IntentTargetProfile.ANY)
        val untargeted = node("n", target = null)
        val desktop = node("d", target = IntentTargetProfile.DESKTOP)
        val server = node("s", target = IntentTargetProfile.HEADLESS_SERVER)
        val ready = listOf(phone, any, untargeted, desktop, server)

        assertEquals(listOf("p", "a", "n"), deckFilter(ready, showAll = false).map { it.id })
        assertEquals(5, deckFilter(ready, showAll = true).size)
    }

    @Test
    fun dispatchToggleSwitchesBetweenAutoAndManual() {
        assertEquals("MANUAL", dispatchLabel(NodeDispatchPolicy.MANUAL_START))
        assertEquals(NodeDispatchPolicy.AUTO_WHEN_READY, toggledDispatch(NodeDispatchPolicy.MANUAL_START))
        assertEquals("AUTO", dispatchLabel(toggledDispatch(NodeDispatchPolicy.MANUAL_START)))
        assertEquals(NodeDispatchPolicy.MANUAL_START, toggledDispatch(NodeDispatchPolicy.AUTO_WHEN_READY))
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun presenterDecidesDropsFromTheCurrentBoard() = runTest {
        val a = node("a")
        val b = node("b")
        val store = MutableStateFlow<GraphSnapshot>(
            testSnapshot(
                "p",
                nodes = listOf(a, b),
                edges = listOf(dependsOn("b", "a")),
                statuses = mapOf(
                    "a" to view(a, DerivedStatus.NOT_STARTED),
                    "b" to view(b, DerivedStatus.NOT_STARTED, unmet = listOf("a")),
                ),
            ),
        )
        val presenter = CartridgeDockPresenter(store, backgroundScope)
        runCurrent()
        assertEquals(DockAction.Start("a"), presenter.drop("a", DockLane.IN_FLIGHT))
        assertEquals(DockAction.Refuse("Blocked by 1 task"), presenter.drop("b", DockLane.IN_FLIGHT))
        assertTrue(presenter.drop("missing", DockLane.DONE) is DockAction.Refuse)
    }
}
