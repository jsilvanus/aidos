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
import dev.aidos.intent.IntentProposal
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.ui.frontdoors.inbox.ActionInboxPresenter
import dev.aidos.ui.frontdoors.inbox.ActionItemUiModel
import dev.aidos.ui.workspace.GraphSnapshot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class InboxViewTest {

    private fun node(
        id: String,
        actor: IntentActorKind = IntentActorKind.HUMAN,
        priority: Int = 100,
        dueAt: String? = null,
        createdAt: String = "2026-11-10T00:00:00Z",
        modifiedAt: String = "2026-11-10T00:00:00Z",
        project: String = "p",
    ) = IntentNodeRecord(
        id = id, projectId = project, type = IntentNodeType.ATOMIC_TASK, title = id,
        granularity = IntentGranularity.ATOMIC, lifecycle = IntentLifecycle.ACTIVE,
        actorKind = actor, dispatchPolicy = NodeDispatchPolicy.MANUAL_START, priority = priority,
        dueAt = dueAt, createdAt = createdAt, modifiedAt = modifiedAt,
        createdByKind = "USER", createdById = "u", modifiedByKind = "USER", modifiedById = "u",
    )

    private fun view(n: IntentNodeRecord, status: DerivedStatus, unmet: List<String> = emptyList()) =
        IntentNodeStatusView(
            node = n,
            derivedStatus = status,
            effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
            unmetDependencyIds = unmet,
            activeRunIds = emptyList(),
            pendingPlatformChecks = false,
        )

    private fun item(v: IntentNodeStatusView, unblockedBy: String? = null) = ActionItemUiModel(
        node = v.node, statusView = v, projectName = "Work", unblockedBy = unblockedBy,
    )

    private fun snapshot(
        nodes: List<Pair<IntentNodeRecord, DerivedStatus>>,
        edges: List<IntentEdgeRecord> = emptyList(),
        proposals: List<IntentProposal> = emptyList(),
        projectId: String = "p",
    ): GraphSnapshot = GraphSnapshot(
        projectId = projectId,
        nodes = nodes.map { it.first },
        edges = edges,
        statuses = nodes.associate { (n, s) -> n.id to view(n, s) },
        proposals = proposals,
        loadedAt = 1L,
    )

    private fun dep(from: String, to: String) = IntentEdgeRecord(
        id = "$from-$to", projectId = "p", fromNodeId = from, toNodeId = to,
        edgeKind = IntentEdgeKind.DEPENDS_ON, createdAt = "2026-11-10T00:00:00Z",
    )

    @Test
    fun sortPutsOpenUnblockedWorkFirstThenPriorityThenDueDate() {
        val blockedHigh = view(node("blocked", priority = 1), DerivedStatus.NOT_STARTED, unmet = listOf("x"))
        val reviewTop = view(node("review", priority = 2), DerivedStatus.NEEDS_REVIEW)
        val lowPriority = view(node("low", priority = 200), DerivedStatus.NOT_STARTED)
        val dueSoon = view(node("due", priority = 100, dueAt = "2026-11-20"), DerivedStatus.IN_PROGRESS)
        val undated = view(node("undated", priority = 100), DerivedStatus.NOT_STARTED)

        val sorted = ActionInboxPresenter.sortForInbox(
            listOf(reviewTop, blockedHigh, lowPriority, undated, dueSoon).map { item(it) }
        ).map { it.node.id }

        assertEquals(listOf("due", "undated", "low", "blocked", "review"), sorted)
    }

    @Test
    fun doneItemsAreNotInTheInbox() {
        val actionable = ActionInboxPresenter.actionableForHuman(
            snapshot(
                listOf(
                    node("open") to DerivedStatus.NOT_STARTED,
                    node("done") to DerivedStatus.DONE,
                )
            )
        ).map { it.node.id }
        assertEquals(listOf("open"), actionable)
    }

    @Test
    fun unblockedByIsSetOnlyWhenTheLastSatisfiedDependencyIsAgent() {
        val agentTask = node("agent", actor = IntentActorKind.AGENT, modifiedAt = "2026-11-12T00:00:00Z")
        val humanTask = node("human", modifiedAt = "2026-11-11T00:00:00Z")
        val mine = node("mine")
        val withAgentLast = snapshot(
            listOf(agentTask to DerivedStatus.DONE, humanTask to DerivedStatus.DONE, mine to DerivedStatus.NOT_STARTED),
            edges = listOf(dep("mine", "agent"), dep("mine", "human")),
        )
        assertEquals("agent", ActionInboxPresenter.unblockedByTitle(withAgentLast, "mine"))

        val humanLast = node("human2", modifiedAt = "2026-11-13T00:00:00Z")
        val withHumanLast = snapshot(
            listOf(agentTask to DerivedStatus.DONE, humanLast to DerivedStatus.DONE, mine to DerivedStatus.NOT_STARTED),
            edges = listOf(dep("mine", "agent"), dep("mine", "human2")),
        )
        assertNull(ActionInboxPresenter.unblockedByTitle(withHumanLast, "mine"))

        val noDeps = snapshot(listOf(mine to DerivedStatus.NOT_STARTED))
        assertNull(ActionInboxPresenter.unblockedByTitle(noDeps, "mine"))
    }

    @Test
    fun gatesCountIsPendingProposalsPlusNeedsReviewNodes() {
        val proposal = IntentProposal(
            id = "pr1", projectId = "p", operations = emptyList(), rationale = "",
            proposedByRunId = "r", proposedAt = "2026-11-10T00:00:00Z", runTaint = "NONE",
            state = "PENDING", resolvedByUserId = null, resolvedAt = null, expiresAt = null,
        )
        val s = snapshot(
            listOf(
                node("a") to DerivedStatus.NEEDS_REVIEW,
                node("b", actor = IntentActorKind.AGENT) to DerivedStatus.NEEDS_REVIEW,
                node("c") to DerivedStatus.DONE,
            ),
            proposals = listOf(proposal),
        )
        assertEquals(3, ActionInboxPresenter.gatesCount(s))
        assertEquals(0, ActionInboxPresenter.gatesCount(snapshot(listOf(node("d") to DerivedStatus.NOT_STARTED))))
    }

    @Test
    fun projectScopeKeepsOnlyCurrentProjectItemsWhenAsked() {
        val mine = item(view(node("m", project = "life"), DerivedStatus.NOT_STARTED))
        val other = item(view(node("o", project = "work"), DerivedStatus.NOT_STARTED))
        val all = listOf(mine, other)

        val state = dev.aidos.ui.frontdoors.inbox.ActionInboxUiState(
            items = all, currentProjectId = "life", currentProjectOnly = false,
        )
        assertEquals(listOf("m", "o"), state.visibleItems.map { it.node.id })
        assertEquals(listOf("m"), state.copy(currentProjectOnly = true).visibleItems.map { it.node.id })
    }
}
