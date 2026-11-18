package dev.aidos.ui

import dev.aidos.api.CapabilityCommands
import dev.aidos.api.CapabilityResult
import dev.aidos.api.CapabilitySummary
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.DiffQueries
import dev.aidos.api.EventFilter
import dev.aidos.api.EventSubscriptions
import dev.aidos.api.GrantCapabilityRequest
import dev.aidos.api.IntentCommands
import dev.aidos.api.MockRuntimeClient
import dev.aidos.api.ParkedEffectRequest
import dev.aidos.api.ParkedEgressRequest
import dev.aidos.api.PendingCapabilityRequest
import dev.aidos.api.RuntimeClient
import dev.aidos.api.RuntimeEvent
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentOperation
import dev.aidos.intent.IntentProposal
import dev.aidos.intent.ProposalResolutionDecision
import dev.aidos.ui.frontdoors.gates.ApprovalGateIntent
import dev.aidos.ui.frontdoors.gates.ApprovalGatePresenter
import dev.aidos.ui.frontdoors.gates.describeOperation
import dev.aidos.ui.workspace.WorkspaceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RFC-0124 Gates (UI-32): five sources, decisions reach the runtime with the right ids. */
class GatesViewTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    private class FakeCaps(
        val effects: MutableList<ParkedEffectRequest> = mutableListOf(),
        val egress: MutableList<ParkedEgressRequest> = mutableListOf(),
        val requests: MutableList<PendingCapabilityRequest> = mutableListOf(),
        private val failEffects: Boolean = false,
    ) : CapabilityCommands {
        val calls = mutableListOf<String>()

        override suspend fun grant(request: GrantCapabilityRequest): CapabilityResult = error("unused in Gates")
        override suspend fun revoke(capabilityId: String) = Unit
        override suspend fun list(sessionId: String): List<CapabilitySummary> = emptyList()
        override suspend fun listPending(): List<PendingCapabilityRequest> = requests.toList()
        override suspend fun listParkedEgress(): List<ParkedEgressRequest> = egress.toList()
        override suspend fun listParkedEffects(): List<ParkedEffectRequest> {
            if (failEffects) throw IllegalStateException("tool list unavailable")
            return effects.toList()
        }

        override suspend fun approve(requestId: String): CapabilityResult {
            calls += "approve:$requestId"
            requests.removeAll { it.requestId == requestId }
            return CapabilityResult.Success(requestId)
        }

        override suspend fun deny(requestId: String, reason: String) {
            calls += "deny:$requestId:$reason"
            requests.removeAll { it.requestId == requestId }
        }

        override suspend fun approveEffect(runId: String, taskId: String): CapabilityResult {
            calls += "approveEffect:$runId:$taskId"
            effects.removeAll { it.runId == runId }
            egress.removeAll { it.runId == runId }
            return CapabilityResult.Success(runId)
        }

        override suspend fun denyEffect(runId: String, taskId: String, reason: String) {
            calls += "denyEffect:$runId:$taskId:$reason"
            effects.removeAll { it.runId == runId }
            egress.removeAll { it.runId == runId }
        }

        override suspend fun answerPrompt(runId: String, answer: String): CapabilityResult = CapabilityResult.Success(runId)
    }

    private class FakeIntents(
        private val base: IntentCommands,
        val nodes: List<IntentNodeRecord>,
        val statuses: Map<String, IntentNodeStatusView>,
        val proposals: MutableList<IntentProposal>,
    ) : IntentCommands by base {
        val resolved = mutableListOf<String>()
        val confirmed = mutableListOf<String>()

        override suspend fun listNodes(
            projectId: String, includeArchived: Boolean, actorKind: IntentActorKind?, parentId: String?, filterByParent: Boolean,
        ): List<IntentNodeRecord> = nodes

        override suspend fun getNodeStatus(
            projectId: String, nodeId: String, projectDefaultPolicy: EffectiveDispatchPolicy,
        ): IntentNodeStatusView? = statuses[nodeId]

        override suspend fun listEdges(
            projectId: String, fromNodeId: String?, toNodeId: String?, edgeKind: IntentEdgeKind?,
        ): List<IntentEdgeRecord> = emptyList()

        override suspend fun listProposals(projectId: String, state: String?): List<IntentProposal> =
            proposals.filter { state == null || it.state == state }

        override suspend fun resolveProposal(
            projectId: String,
            proposalId: String,
            decision: ProposalResolutionDecision,
            resolvedByUserId: String,
            editedOperations: List<IntentOperation>?,
        ): IntentProposal {
            resolved += "$proposalId:$decision"
            val found = proposals.first { it.id == proposalId }
            proposals.remove(found)
            return found.copy(state = decision.name)
        }

        override suspend fun confirmImplementation(
            projectId: String, nodeId: String, confirmedByKind: String, confirmedById: String, criterionIds: List<String>?,
        ) {
            confirmed += nodeId
        }
    }

    private class GatesRuntime(
        override val capabilities: CapabilityCommands,
        override val intents: IntentCommands,
    ) : RuntimeClient by MockRuntimeClient() {
        override val events: EventSubscriptions = object : EventSubscriptions {
            override fun subscribe(filter: EventFilter): Flow<RuntimeEvent> = emptyFlow()
        }
    }

    private inner class Harness(val caps: FakeCaps, val intents: FakeIntents) {
        val runtime = GatesRuntime(caps, intents)
        val controller = WorkspaceController(runtime, scope, "p", "gates")
        val presenter = ApprovalGatePresenter(runtime, controller, scope, Dispatchers.Unconfined)

        /** Loads the graph and every polled source once, as the presenter's poll does. */
        suspend fun load() {
            controller.currentStore.value.refreshNow()
            presenter.refreshPolled()
        }
    }

    private fun node(id: String, title: String) = IntentNodeRecord(
        id = id,
        projectId = "p",
        type = IntentNodeType.ATOMIC_TASK,
        title = title,
        createdAt = T,
        createdByKind = "HUMAN",
        createdById = "juha",
        modifiedAt = T,
        modifiedByKind = "HUMAN",
        modifiedById = "juha",
    )

    private fun statusOf(node: IntentNodeRecord, status: DerivedStatus) = IntentNodeStatusView(
        node = node,
        derivedStatus = status,
        effectiveDispatchPolicy = EffectiveDispatchPolicy.MANUAL_START,
        unmetDependencyIds = emptyList(),
        activeRunIds = emptyList(),
        pendingPlatformChecks = false,
    )

    private fun proposal(id: String) = IntentProposal(
        id = id,
        projectId = "p",
        operations = listOf("""{"_kind":"AddNode","parentId":"g","id":"t9","type":"ATOMIC_TASK","title":"Write tests","priority":100}"""),
        rationale = "split the work",
        proposedByRunId = "run-7",
        proposedAt = T,
        runTaint = "UNTRUSTED",
        state = "PENDING",
        resolvedByUserId = null,
        resolvedAt = null,
        expiresAt = null,
    )

    private fun fullHarness(caps: FakeCaps = defaultCaps(), review: Boolean = true): Harness {
        val verify = node("v1", "Check auth")
        val nodes = listOf(verify)
        val statuses = mapOf("v1" to statusOf(verify, if (review) DerivedStatus.NEEDS_REVIEW else DerivedStatus.DONE))
        val intents = FakeIntents(
            base = MockRuntimeClient().intents,
            nodes = nodes,
            statuses = statuses,
            proposals = mutableListOf(proposal("prop-1")),
        )
        return Harness(caps, intents)
    }

    private fun defaultCaps() = FakeCaps(
        effects = mutableListOf(
            ParkedEffectRequest(
                runId = "run-1", taskId = "task-1", projectId = "p", toolName = "shell.exec",
                argumentsJson = """{"cmd":"ls"}""", runTaint = "UNTRUSTED",
            ),
        ),
        egress = mutableListOf(
            ParkedEgressRequest(runId = "run-egress", projectId = "p", channel = "sms", recipient = "Alice", payload = "Dinner at 8:30."),
        ),
        requests = mutableListOf(PendingCapabilityRequest("cap-1", "sess-1", "net.http", "needs weather data")),
    )

    @Test
    fun everySourceFillsItsSection() = runBlocking {
        val h = fullHarness()
        h.load()
        val state = h.presenter.state.value
        assertEquals(1, state.toolApprovals.size)
        assertEquals(1, state.pendingEgressRequests.size)
        assertEquals(1, state.capabilityRequests.size)
        assertEquals(1, state.proposals.size)
        assertEquals(1, state.pendingVerifications.size)
        assertEquals("Write tests", state.proposals.single().summary.single().substringAfter('"').substringBefore('"'))
    }

    @Test
    fun approvingAToolCallRemovesItAndResumesThatRun() = runBlocking {
        val h = fullHarness()
        h.load()
        h.presenter.send(ApprovalGateIntent.ApproveToolEffect("run-1", "task-1"))
        assertTrue("approveEffect:run-1:task-1" in h.caps.calls)
        assertEquals(0, h.presenter.state.value.toolApprovals.size)
    }

    @Test
    fun decisionsPassTheReasonToTheRuntime() = runBlocking {
        val h = fullHarness()
        h.load()
        h.presenter.send(ApprovalGateIntent.DenyToolEffect("run-1", "task-1", "not on this machine"))
        h.presenter.send(ApprovalGateIntent.DenyCapability("cap-1", "no network"))
        h.presenter.send(ApprovalGateIntent.DenyEgress("run-egress", "not now"))
        assertTrue("denyEffect:run-1:task-1:not on this machine" in h.caps.calls, h.caps.calls.toString())
        assertTrue("deny:cap-1:no network" in h.caps.calls, h.caps.calls.toString())
        assertTrue("denyEffect:run-egress:run-egress:not now" in h.caps.calls, h.caps.calls.toString())
        assertEquals(0, h.presenter.state.value.capabilityRequests.size)
    }

    @Test
    fun pendingCountIsTheSumOfEverySection() = runBlocking {
        val h = fullHarness()
        h.load()
        assertEquals(5, h.presenter.pendingCount.value)
        h.presenter.send(ApprovalGateIntent.ApproveCapability("cap-1"))
        assertEquals(4, h.presenter.pendingCount.value)
    }

    @Test
    fun aFailingSourceDoesNotHideTheOthers() = runBlocking {
        val caps = defaultCaps().let { FakeCaps(it.effects, it.egress, it.requests, failEffects = true) }
        val h = fullHarness(caps)
        h.load()
        val state = h.presenter.state.value
        assertEquals(0, state.toolApprovals.size)
        assertEquals(1, state.pendingEgressRequests.size)
        assertEquals(1, state.capabilityRequests.size)
        assertEquals(1, state.proposals.size)
        assertEquals(1, state.pendingVerifications.size)
    }

    @Test
    fun acceptingAProposalResolvesItThroughTheController() = runBlocking {
        val h = fullHarness()
        h.load()
        h.presenter.send(ApprovalGateIntent.ResolveProposal("prop-1", accept = true))
        assertEquals(listOf("prop-1:ACCEPTED"), h.intents.resolved)
        assertEquals(0, h.presenter.state.value.proposals.size)
    }

    @Test
    fun confirmingAVerificationConfirmsTheNodeInTheProject() = runBlocking {
        val h = fullHarness()
        h.load()
        h.presenter.send(ApprovalGateIntent.ConfirmVerification("v1"))
        assertEquals(listOf("v1"), h.intents.confirmed)
        assertEquals(0, h.presenter.state.value.pendingVerifications.size)
    }

    @Test
    fun nothingWaitingMeansACountOfZero() = runBlocking {
        val h = Harness(FakeCaps(), FakeIntents(MockRuntimeClient().intents, emptyList(), emptyMap(), mutableListOf()))
        h.load()
        assertEquals(0, h.presenter.pendingCount.value)
        assertEquals(null, h.presenter.state.value.inspectedDiffs)
    }

    @Test
    fun describesProposalOperationsInPlainWords() {
        val json = """{"_kind":"AddDependency","fromNodeId":"a","toNodeId":"b"}"""
        assertEquals("Add dependency a → b", describeOperation(json))
        assertEquals("not json", describeOperation("not json"))
    }

    companion object {
        private const val T = "2026-11-18T10:00:00Z"
    }
}
