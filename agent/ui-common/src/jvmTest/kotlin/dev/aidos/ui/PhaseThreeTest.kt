package dev.aidos.ui

import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.CreateIntentProposalRequest
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.IntentCommands
import dev.aidos.api.ProjectCommands
import dev.aidos.api.ProjectResult
import dev.aidos.api.ProjectSummary
import dev.aidos.api.UpdateIntentNodeRequest
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
import dev.aidos.intent.IntentOperation
import dev.aidos.intent.IntentProposal
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.intent.ProposalResolutionDecision
import dev.aidos.intent.SnapshotReconcileResult
import dev.aidos.kernel.PlatformProfile
import dev.aidos.ui.frontdoors.gates.ApprovalGateIntent
import dev.aidos.ui.frontdoors.gates.ApprovalGatePresenter
import dev.aidos.ui.frontdoors.gates.toUiModel
import dev.aidos.ui.frontdoors.gates.BlindedEgressRequestUiModel
import dev.aidos.ui.frontdoors.gates.SplitVerificationUiModel
import dev.aidos.ui.frontdoors.inbox.ActionInboxIntent
import dev.aidos.ui.frontdoors.inbox.ActionInboxPresenter
import dev.aidos.ui.frontdoors.projects.ProjectHubIntent
import dev.aidos.ui.frontdoors.projects.ProjectHubPresenter
import dev.aidos.ui.frontdoors.projects.WorkspaceDomain
import dev.aidos.ui.theme.SpacingTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PhaseThreeTest {

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testBlindedEgressGateInterceptAndApprovalFlow() = runTest {
        var networkPacketsSent = 0
        var approvedRequestId: String? = null

        val presenter = ApprovalGatePresenter(
            scope = this,
            ioDispatcher = Dispatchers.Unconfined,
            onEgressApproved = { reqId ->
                approvedRequestId = reqId
                networkPacketsSent++
            }
        )

        val egressRequest = BlindedEgressRequestUiModel(
            requestId = "req-999",
            projectId = "project-personal-life",
            channel = "Matrix",
            recipient = "@family-chat:matrix.org",
            payloadPreview = "Dinner is at 7pm tonight.",
            cryptographicFingerprint = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
            taintLevel = "HIGH",
        )

        // Request parked in gate
        presenter.stageEgressRequest(egressRequest)
        assertEquals(1, presenter.state.value.pendingEgressRequests.size)
        assertEquals(0, networkPacketsSent, "Zero packets must be transmitted while parked!")

        // User approves explicit declassification
        presenter.send(ApprovalGateIntent.ApproveEgress("req-999"))
        advanceUntilIdle()

        assertEquals("req-999", approvedRequestId)
        assertEquals(1, networkPacketsSent)
        assertEquals(0, presenter.state.value.pendingEgressRequests.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testGateCardFollowsTheRuntimesParkedMessagesAndDecidesThroughTheRun() = runTest {
        val decided = mutableListOf<String>()
        val presenter = ApprovalGatePresenter(
            scope = this,
            ioDispatcher = Dispatchers.Unconfined,
            onEgressApproved = { runId -> decided += "approve:$runId" },
            onEgressDenied = { runId, reason -> decided += "deny:$runId:$reason" },
        )
        val parked = dev.aidos.api.ParkedEgressRequest(
            runId = "run-1", projectId = "life", channel = "sms", recipient = "Alice", payload = "Dinner at 8:30.",
        )
        presenter.syncEgressRequests(listOf(parked.toUiModel()))
        val card = presenter.state.value.pendingEgressRequests.single()
        assertEquals("run-1", card.requestId, "the card's id is the parked Run, so Approve resumes exactly that Run")
        assertEquals("Dinner at 8:30.", card.payloadPreview)
        assertEquals(parked.payloadFingerprint, card.cryptographicFingerprint)
        assertEquals(64, card.cryptographicFingerprint.length)

        presenter.send(ApprovalGateIntent.DenyEgress("run-1", "not now"))
        advanceUntilIdle()
        assertEquals(listOf("deny:run-1:not now"), decided)

        // The runtime no longer lists it (decided elsewhere): the card goes away.
        presenter.syncEgressRequests(emptyList())
        assertEquals(0, presenter.state.value.pendingEgressRequests.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testSplitVerificationMobileToDesktopHandoff() = runTest {
        var executedVerificationNodeId: String? = null

        val presenter = ApprovalGatePresenter(
            scope = this,
            ioDispatcher = Dispatchers.Unconfined,
            onVerificationExecuted = { nodeId ->
                executedVerificationNodeId = nodeId
            }
        )

        val verification = SplitVerificationUiModel(
            nodeId = "node-auth-check",
            taskTitle = "Run authentication suite",
            checkKind = "SHELL_COMMAND",
            checkSpec = "./gradlew :agent:identity:jvmTest",
            scopePaths = listOf("agent/identity/Auth.kt"),
        )

        presenter.stageVerification(verification)
        assertEquals(1, presenter.state.value.pendingVerifications.size)

        // 1-click execution trigger
        presenter.send(ApprovalGateIntent.RunVerification("node-auth-check"))
        advanceUntilIdle()

        assertEquals("node-auth-check", executedVerificationNodeId)
        assertEquals(0, presenter.state.value.pendingVerifications.size)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    @Test
    fun testActionInboxAndProjectHubAggregation() = runTest {
        val sampleProject = ProjectSummary(
            id = "proj-1",
            name = "personal-life",
            description = "Personal tasks",
            projectPath = "/storage/personal",
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            lastActiveAt = Instant.parse("2026-01-01T00:00:00Z"),
            sessionCount = 1,
        )

        val sampleNode = IntentNodeRecord(
            id = "task-groceries",
            projectId = "proj-1",
            type = IntentNodeType.ATOMIC_TASK,
            title = "Buy fresh vegetables",
            description = "Tomatoes and celery",
            granularity = IntentGranularity.ATOMIC,
            lifecycle = IntentLifecycle.ACTIVE,
            actorKind = IntentActorKind.HUMAN,
            dispatchPolicy = NodeDispatchPolicy.MANUAL_START,
            createdAt = "2026-11-14T20:00:00Z",
            createdByKind = "USER",
            createdById = "local-user",
            modifiedAt = "2026-11-14T20:00:00Z",
            modifiedByKind = "USER",
            modifiedById = "local-user",
        )

        val statusView = IntentNodeStatusView(
            node = sampleNode,
            derivedStatus = DerivedStatus.NOT_STARTED,
            effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
            unmetDependencyIds = emptyList(),
            activeRunIds = emptyList(),
            pendingPlatformChecks = false,
        )

        val fakeProjectCommands = FakeProjectCommands(listOf(sampleProject))
        val fakeIntentCommands = FakeIntentCommands(listOf(statusView))

        val inboxPresenter = ActionInboxPresenter(
            intentCommands = fakeIntentCommands,
            projectCommands = fakeProjectCommands,
            scope = this,
            ioDispatcher = Dispatchers.Unconfined,
        )
        advanceUntilIdle()

        val inboxState = inboxPresenter.state.value
        assertFalse(inboxState.isLoading)
        assertEquals(1, inboxState.items.size)
        assertEquals("Buy fresh vegetables", inboxState.items.first().node.title)
        assertTrue(inboxState.items.first().isLifeProject)

        val hubPresenter = ProjectHubPresenter(
            projectCommands = fakeProjectCommands,
            scope = this,
            ioDispatcher = Dispatchers.Unconfined,
        )
        advanceUntilIdle()

        val hubState = hubPresenter.state.value
        assertEquals(1, hubState.projects.size)
        assertEquals(WorkspaceDomain.LIFE_AIR_GAPPED, hubState.projects.first().domain)
    }

    @Test
    fun testAccessibilityTouchTargetCompliance() {
        // Minimum touch target is 48dp
        assertTrue(SpacingTokens.minTouchTarget.value >= 48f)
    }

    private class FakeProjectCommands(private val projects: List<ProjectSummary>) : ProjectCommands {
        override suspend fun create(request: CreateProjectRequest) = error("Not needed")
        override suspend fun open(projectId: String) = error("Not needed")
        override suspend fun close(projectId: String) {}
        override suspend fun list(): List<ProjectSummary> = projects
        override suspend fun get(projectId: String) = null
        override suspend fun delete(projectId: String, confirm: Boolean) {}
    }

    private class FakeIntentCommands(
        private val actionable: List<IntentNodeStatusView> = emptyList(),
    ) : IntentCommands {
        override suspend fun createNode(request: CreateIntentNodeRequest) = error("Not needed")
        override suspend fun updateNode(request: UpdateIntentNodeRequest) = error("Not needed")
        override suspend fun archiveNode(projectId: String, nodeId: String) = error("Not needed")
        override suspend fun getNodeStatus(projectId: String, nodeId: String, projectDefaultPolicy: EffectiveDispatchPolicy) = null
        override suspend fun listNodes(projectId: String, includeArchived: Boolean, actorKind: IntentActorKind?, parentId: String?, filterByParent: Boolean) = emptyList<IntentNodeRecord>()
        override suspend fun listActionableForHuman(projectId: String, projectDefaultPolicy: EffectiveDispatchPolicy) = actionable
        override suspend fun listReadyForAgent(projectId: String, profile: PlatformProfile, locality: IntentMinLocality, projectDefaultPolicy: EffectiveDispatchPolicy, onlyAutoDispatch: Boolean) = emptyList<IntentNodeStatusView>()
        override suspend fun completeHumanNode(projectId: String, nodeId: String, userId: String, note: String?): IntentNodeRecord {
            return actionable.first { it.node.id == nodeId }.node
        }
        override suspend fun addEdge(projectId: String, fromNodeId: String, toNodeId: String, edgeKind: IntentEdgeKind) = error("Not needed")
        override suspend fun removeEdge(projectId: String, fromNodeId: String, toNodeId: String, edgeKind: IntentEdgeKind) = false
        override suspend fun listEdges(projectId: String, fromNodeId: String?, toNodeId: String?, edgeKind: IntentEdgeKind?) = emptyList<IntentEdgeRecord>()
        override suspend fun propose(request: CreateIntentProposalRequest) = error("Not needed")
        override suspend fun listProposals(projectId: String, state: String?) = emptyList<IntentProposal>()
        override suspend fun resolveProposal(projectId: String, proposalId: String, decision: ProposalResolutionDecision, resolvedByUserId: String, editedOperations: List<IntentOperation>?) = error("Not needed")
        override suspend fun confirmImplementation(projectId: String, nodeId: String, confirmedByKind: String, confirmedById: String, criterionIds: List<String>?) {}
        override suspend fun exportSnapshotJson(projectId: String) = ""
        override suspend fun importAndReconcileSnapshot(projectId: String, snapshotJson: String) = error("Not needed")
    }
}
