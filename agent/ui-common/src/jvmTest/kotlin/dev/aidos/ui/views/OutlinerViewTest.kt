package dev.aidos.ui.views

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentCheckKind
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.paradigms.outliner.CausalOutlinerPresenter
import dev.aidos.ui.paradigms.outliner.CausalOutlinerUiState
import dev.aidos.ui.paradigms.outliner.OutlinerEntry
import dev.aidos.ui.paradigms.outliner.OutlinerSection
import dev.aidos.ui.paradigms.outliner.actionHorizon
import dev.aidos.ui.paradigms.outliner.buildEntries
import dev.aidos.ui.paradigms.outliner.childTypeFor
import dev.aidos.ui.paradigms.outliner.criteriaFor
import dev.aidos.ui.paradigms.outliner.jumpIndex
import dev.aidos.ui.paradigms.outliner.previousSiblingId
import dev.aidos.ui.paradigms.outliner.verificationLedger
import dev.aidos.ui.workspace.GraphSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun node(
    id: String,
    type: IntentNodeType = IntentNodeType.ATOMIC_TASK,
    parent: String? = null,
    priority: Int = 100,
    dueAt: String? = null,
    lifecycle: IntentLifecycle = IntentLifecycle.ACTIVE,
    checkKind: IntentCheckKind? = null,
    checkSpec: String? = null,
) = IntentNodeRecord(
    id = id,
    projectId = "p",
    type = type,
    title = id,
    parentId = parent,
    priority = priority,
    dueAt = dueAt,
    lifecycle = lifecycle,
    actorKind = IntentActorKind.AGENT,
    checkKind = checkKind,
    checkSpec = checkSpec,
    createdAt = "2026-11-18T00:00:00Z",
    createdByKind = "USER",
    createdById = "u",
    modifiedAt = "2026-11-18T00:00:00Z",
    modifiedByKind = "USER",
    modifiedById = "u",
)

private fun status(
    node: IntentNodeRecord,
    derived: DerivedStatus,
    unmet: List<String> = emptyList(),
) = IntentNodeStatusView(
    node = node,
    derivedStatus = derived,
    effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
    unmetDependencyIds = unmet,
    activeRunIds = emptyList(),
    pendingPlatformChecks = false,
)

private fun snapshot(nodes: List<IntentNodeRecord>, derived: Map<String, DerivedStatus>, unmet: Map<String, List<String>> = emptyMap()) =
    GraphSnapshot(
        projectId = "p",
        nodes = nodes,
        edges = emptyList(),
        statuses = nodes.associate { n -> n.id to status(n, derived[n.id] ?: DerivedStatus.NOT_STARTED, unmet[n.id].orEmpty()) },
        proposals = emptyList(),
        loadedAt = 1L,
    )

class OutlinerViewTest {

    @Test
    fun blockedTaskIsNotInActionHorizon() {
        val nodes = listOf(
            node("blocked"),
            node("dep"),
            node("done"),
            node("free"),
        )
        val snap = snapshot(
            nodes,
            derived = mapOf("blocked" to DerivedStatus.BLOCKED, "done" to DerivedStatus.DONE),
            unmet = mapOf("blocked" to listOf("dep")),
        )
        assertEquals(listOf("dep", "free"), actionHorizon(snap).map { it.id })
    }

    @Test
    fun horizonSortsByPriorityThenDueDate() {
        val nodes = listOf(
            node("a", priority = 200),
            node("b", priority = 100, dueAt = "2026-11-20"),
            node("c", priority = 100, dueAt = "2026-11-19"),
            node("d", priority = 100, dueAt = null),
        )
        assertEquals(listOf("c", "b", "d", "a"), actionHorizon(snapshot(nodes, emptyMap())).map { it.id })
    }

    @Test
    fun horizonHoldsOnlyActiveAtomicTasks() {
        val nodes = listOf(
            node("goal", type = IntentNodeType.GOAL),
            node("archived", lifecycle = IntentLifecycle.ARCHIVED),
            node("task"),
        )
        assertEquals(listOf("task"), actionHorizon(snapshot(nodes, emptyMap())).map { it.id })
    }

    @Test
    fun needsReviewTaskIsInLedgerWithItsCriteria() {
        val nodes = listOf(
            node("t"),
            node("c1", type = IntentNodeType.ACCEPTANCE_CRITERION, parent = "t", checkKind = IntentCheckKind.SHELL_COMMAND, checkSpec = "./gradlew test"),
            node("other"),
        )
        val snap = snapshot(nodes, mapOf("t" to DerivedStatus.NEEDS_REVIEW, "other" to DerivedStatus.DONE))

        assertEquals(listOf("t"), verificationLedger(snap).map { it.id })
        val criteria = criteriaFor("t", snap)
        assertEquals(listOf("c1"), criteria.map { it.id })
        assertEquals(IntentCheckKind.SHELL_COMMAND, criteria.single().checkKind)
        assertEquals("./gradlew test", criteria.single().checkSpec)
    }

    @Test
    fun criteriaAreDrawnUnderTheirTaskNotAsTreeRows() {
        val nodes = listOf(
            node("t"),
            node("c1", type = IntentNodeType.ACCEPTANCE_CRITERION, parent = "t"),
        )
        val rows = CausalOutlinerPresenter.buildFlattenedTree(nodes, emptyMap(), setOf("t"))
        assertEquals(1, rows.size)
        assertEquals(listOf("c1"), rows[0].criteria.map { it.id })
        assertTrue(!rows[0].hasChildren)
    }

    @Test
    fun childTypeFollowsTheOutlineLevels() {
        assertEquals(IntentNodeType.GOAL, childTypeFor(IntentNodeType.GROUP))
        assertEquals(IntentNodeType.SUB_GOAL, childTypeFor(IntentNodeType.GOAL))
        assertEquals(IntentNodeType.ATOMIC_TASK, childTypeFor(IntentNodeType.SUB_GOAL))
        assertEquals(IntentNodeType.ATOMIC_TASK, childTypeFor(IntentNodeType.ATOMIC_TASK))
    }

    @Test
    fun previousSiblingIsTheRowAboveAtTheSameLevel() {
        val nodes = listOf(
            node("g", type = IntentNodeType.GROUP),
            node("a", parent = "g"),
            node("b", parent = "g"),
            node("x", type = IntentNodeType.GROUP),
        )
        val rows = CausalOutlinerPresenter.buildFlattenedTree(nodes, emptyMap(), setOf("g", "x"))
        assertEquals("a", previousSiblingId("b", rows))
        assertNull(previousSiblingId("a", rows), "the first child has no sibling above it")
        assertEquals("g", previousSiblingId("x", rows), "a root row's sibling is the root above, not a child")
    }

    @Test
    fun collapsedSectionEmitsOnlyItsHeader() {
        val horizonRow = CausalOutlinerPresenter.buildFlattenedTree(listOf(node("h")), emptyMap(), emptySet())
        val state = CausalOutlinerUiState(
            horizon = horizonRow,
            collapsedSections = setOf(OutlinerSection.ACTION_HORIZON),
        )
        val keys = buildEntries(state).map { it.key }
        assertTrue("section:ACTION_HORIZON" in keys)
        assertTrue(keys.none { it.startsWith("h:") })
    }

    @Test
    fun jumpPrefersTheIntentTreeRow() {
        val row = CausalOutlinerPresenter.buildFlattenedTree(listOf(node("x")), emptyMap(), emptySet())
        val state = CausalOutlinerUiState(visibleRows = row, horizon = row)
        val entries = buildEntries(state)
        val index = jumpIndex(entries, "x")
        assertEquals("t:x", entries[index].key)
        assertTrue(entries[index] is OutlinerEntry.Row)
    }
}
