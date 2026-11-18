package dev.aidos.ui.views

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentGranularity
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.ui.layout.NavigationDestination
import dev.aidos.ui.pills.PillRole
import dev.aidos.ui.pills.pillsFor
import dev.aidos.ui.workspace.GraphSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC-0111 §3 / RFC-0124 (UI-42): pills derived from live runs, and the Gates badge count. */
class PresenceTest {

    private fun node(
        id: String,
        title: String = id,
        type: IntentNodeType = IntentNodeType.ATOMIC_TASK,
        scopePaths: List<String> = emptyList(),
    ) = IntentNodeRecord(
        id = id, projectId = "p", type = type, title = title, scopePaths = scopePaths,
        granularity = IntentGranularity.ATOMIC, lifecycle = IntentLifecycle.ACTIVE,
        actorKind = IntentActorKind.AGENT, dispatchPolicy = NodeDispatchPolicy.MANUAL_START,
        targetProfile = IntentTargetProfile.ANY, minLocality = IntentMinLocality.LOCAL_ONLY,
        createdAt = "2026-11-18T00:00:00Z", createdByKind = "USER", createdById = "u",
        modifiedAt = "2026-11-18T00:00:00Z", modifiedByKind = "USER", modifiedById = "u",
    )

    private fun status(
        node: IntentNodeRecord,
        derived: DerivedStatus = DerivedStatus.IN_PROGRESS,
        runs: List<String> = listOf("run-${node.id}"),
    ) = IntentNodeStatusView(
        node = node,
        derivedStatus = derived,
        effectiveDispatchPolicy = EffectiveDispatchPolicy.entries.first(),
        unmetDependencyIds = emptyList(),
        activeRunIds = runs,
        pendingPlatformChecks = false,
    )

    private fun snapshot(vararg entries: Pair<IntentNodeRecord, IntentNodeStatusView?>): GraphSnapshot {
        val nodes = entries.map { it.first }
        val statuses = entries.mapNotNull { (n, s) -> s?.let { n.id to it } }.toMap()
        return GraphSnapshot(
            projectId = "p", nodes = nodes, edges = emptyList(), statuses = statuses,
            proposals = emptyList(), loadedAt = 1L,
        )
    }

    @Test
    fun noActiveRunsLeavesOnlyTheAssistantPill() {
        val n = node("a")
        val pills = pillsFor(snapshot(n to status(n, runs = emptyList())), assistantActive = false)
        assertEquals(1, pills.size)
        assertEquals(PillRole.ORCHESTRATOR_RED, pills.single().role)
        assertFalse(pills.single().isRadiatingWave)
    }

    @Test
    fun assistantOpenMakesTheRedPillRadiate() {
        val pills = pillsFor(GraphSnapshot.empty("p"), assistantActive = true)
        assertTrue(pills.single().isRadiatingWave)
    }

    @Test
    fun workerRoleFollowsTheNode() {
        val review = node("review", scopePaths = listOf("src/x.kt"))
        val criterion = node("criterion", type = IntentNodeType.ACCEPTANCE_CRITERION)
        val code = node("code", scopePaths = listOf("src/y.kt"))
        val research = node("research")
        val pills = pillsFor(
            snapshot(
                review to status(review, derived = DerivedStatus.NEEDS_REVIEW),
                criterion to status(criterion),
                code to status(code),
                research to status(research),
            ),
            assistantActive = false,
        ).associateBy { it.activeTaskId }

        assertEquals(PillRole.VERIFIER_GREEN, pills.getValue("review").role)
        assertEquals(PillRole.VERIFIER_GREEN, pills.getValue("criterion").role)
        assertEquals(PillRole.WORKER_CODE_BLUE, pills.getValue("code").role)
        assertEquals(PillRole.RESEARCH_VIOLET, pills.getValue("research").role)
    }

    @Test
    fun workerPillsAreCappedAtSix() {
        val nodes = (1..8).map { node("n$it") }
        val snap = snapshot(*nodes.map { it to status(it) }.toTypedArray())
        val pills = pillsFor(snap, assistantActive = false)
        assertEquals(7, pills.size)
        assertEquals((1..6).map { "n$it" }, pills.drop(1).map { it.activeTaskId })
    }

    @Test
    fun workerPillsSitOnTheBottomRowAndCarryTheNodeId() {
        val a = node("a")
        val b = node("b")
        val pills = pillsFor(snapshot(a to status(a), b to status(b)), assistantActive = false)
        val workers = pills.drop(1)
        assertTrue(workers.all { it.yNormalized == 0.88f })
        assertEquals(listOf(0.12f, 0.24f), workers.map { it.xNormalized })
        assertEquals(listOf("a", "b"), workers.map { it.activeTaskId })
    }

    @Test
    fun longTitlesAreTruncatedToTwentyFourCharactersWithEllipsis() {
        val long = node("long", title = "a".repeat(30))
        val short = node("short", title = "Write the report")
        val pills = pillsFor(snapshot(long to status(long), short to status(short)), assistantActive = false)
        val labels = pills.drop(1).map { it.label }
        assertEquals("a".repeat(23) + "…", labels[0])
        assertEquals(24, labels[0].length)
        assertEquals("Write the report", labels[1])
    }

    @Test
    fun destinationCarriesAGatesBadgeCount() {
        val gates = NavigationDestination(id = "gates", label = "Gates (3)", icon = {}, badgeCount = 3)
        val inbox = NavigationDestination(id = "inbox", label = "Inbox", icon = {})
        assertEquals(3, gates.badgeCount)
        assertEquals(0, inbox.badgeCount)
    }
}
