package dev.aidos.ui.frontdoors.gates

import dev.aidos.api.CapabilityResult
import dev.aidos.api.EventFilter
import dev.aidos.api.RuntimeEventType
import dev.aidos.api.ParkedEffectRequest
import dev.aidos.api.ParkedEgressRequest
import dev.aidos.api.PendingCapabilityRequest
import dev.aidos.api.RuntimeClient
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentProposal
import dev.aidos.intent.ProposalResolutionDecision
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import dev.aidos.ui.workspace.WorkspaceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject

/** A proposed batch of graph changes, in plain words (RFC-0110, RFC-0012). */
data class ProposalUiModel(
    val proposalId: String,
    val summary: List<String>,
    val rationale: String,
    val runTaint: String,
)

/**
 * Gates shows five sources (RFC-0124, decision U9). The app root is the only caller that
 * constructs one; each section is hidden when empty.
 */
data class ApprovalGateUiState(
    val toolApprovals: List<ParkedEffectRequest> = emptyList(),
    val pendingEgressRequests: List<BlindedEgressRequestUiModel> = emptyList(),
    val capabilityRequests: List<PendingCapabilityRequest> = emptyList(),
    val proposals: List<ProposalUiModel> = emptyList(),
    val pendingVerifications: List<SplitVerificationUiModel> = emptyList(),
    val inspectedDiffs: List<FileDiffPreview>? = null,
    val message: String? = null,
) {
    val pendingCount: Int
        get() = toolApprovals.size + pendingEgressRequests.size + capabilityRequests.size +
            proposals.size + pendingVerifications.size
}

/** The Human Egress Gate Card's view of a message the runtime has parked (RFC-0113 §4.3). */
fun ParkedEgressRequest.toUiModel(): BlindedEgressRequestUiModel = BlindedEgressRequestUiModel(
    requestId = runId,
    projectId = projectId,
    channel = channel,
    recipient = recipient,
    payloadPreview = payload,
    cryptographicFingerprint = payloadFingerprint,
)

sealed interface ApprovalGateIntent {
    data class ApproveEgress(val requestId: String) : ApprovalGateIntent
    data class DenyEgress(val requestId: String, val reason: String) : ApprovalGateIntent
    data class ApproveToolEffect(val runId: String, val taskId: String) : ApprovalGateIntent
    data class DenyToolEffect(val runId: String, val taskId: String, val reason: String) : ApprovalGateIntent
    data class ApproveCapability(val requestId: String) : ApprovalGateIntent
    data class DenyCapability(val requestId: String, val reason: String) : ApprovalGateIntent
    data class ResolveProposal(val proposalId: String, val accept: Boolean) : ApprovalGateIntent
    data class ConfirmVerification(val nodeId: String) : ApprovalGateIntent
    /** Legacy seam: runs the host's verification callback. The app root never sends it. */
    data class RunVerification(val nodeId: String) : ApprovalGateIntent
    data class InspectDiff(val nodeId: String) : ApprovalGateIntent
    data object CloseDiffInspection : ApprovalGateIntent
}

/**
 * Presents every approval the person must make (RFC-0124 Gates, RFC-0113 §4.3, RFC-0110).
 *
 * Runtime-backed (`client` + `controller`): sources 1 to 3 (parked tool effects, parked egress,
 * capability requests) are polled every [POLL_MILLIS] by [start]; proposals and NEEDS_REVIEW
 * verification come from the current project's graph snapshot. Decisions go through the same
 * runtime calls the CLI uses, and every decision refreshes at once.
 *
 * The callback constructor keeps the older egress wiring working for hosts and tests that have no
 * runtime to hand. Nothing decides on its own: approvals always wait for a person's intent.
 */
class ApprovalGatePresenter private constructor(
    private val client: RuntimeClient?,
    private val controller: WorkspaceController?,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher,
    private val onEgressApproved: (suspend (String) -> Unit)?,
    private val onEgressDenied: (suspend (String, String) -> Unit)?,
    private val onVerificationExecuted: (suspend (String) -> Unit)?,
) : MviPresenter<ApprovalGateUiState, ApprovalGateIntent> {

    constructor(
        client: RuntimeClient,
        controller: WorkspaceController,
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    ) : this(client, controller, scope, ioDispatcher, null, null, null)

    constructor(
        scope: CoroutineScope,
        ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
        onEgressApproved: (suspend (String) -> Unit)? = null,
        onEgressDenied: (suspend (String, String) -> Unit)? = null,
        onVerificationExecuted: (suspend (String) -> Unit)? = null,
    ) : this(null, null, scope, ioDispatcher, onEgressApproved, onEgressDenied, onVerificationExecuted)

    private val _state = MutableStateFlow(ApprovalGateUiState())
    override val state: StateFlow<ApprovalGateUiState> = _state.asStateFlow()

    private val _pendingCount = MutableStateFlow(0)
    /** Sum of every section. The Gates badge (UI-42) reads this. */
    val pendingCount: StateFlow<Int> = _pendingCount.asStateFlow()

    /** Verifications staged by a host or test, shown next to the store's NEEDS_REVIEW nodes. */
    private val staged = MutableStateFlow<List<SplitVerificationUiModel>>(emptyList())
    private var started = false

    /** Starts the poll and follows the current project's graph. Idempotent; a no-op without a runtime. */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        if (started) return
        started = true
        val store = controller ?: return
        scope.launch {
            store.currentStore.flatMapLatest { it.snapshot }.collect { publishStore(it) }
        }
        scope.launch {
            while (true) {
                refreshPolled()
                delay(POLL_MILLIS)
            }
        }
        // UI-70: a tool call parked for approval shows at once; the poll above stays as the fallback.
        client?.let { runtime ->
            scope.launch {
                try {
                    runtime.events.subscribe(EventFilter(types = listOf(RuntimeEventType.TOOL_APPROVAL_REQUIRED)))
                        .collect { refreshAfterDecision() }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    // the poll covers it
                }
            }
        }
    }

    /** Loads all five sources now. Each source that fails keeps its last good list; the others still show. */
    suspend fun refreshPolled() {
        val runtime = client
        if (runtime != null) {
            val tools = sourceOrNull { runtime.capabilities.listParkedEffects() }
            val egress = sourceOrNull { runtime.capabilities.listParkedEgress() }
            val capabilities = sourceOrNull { runtime.capabilities.listPending() }
            commit {
                it.copy(
                    toolApprovals = tools ?: it.toolApprovals,
                    pendingEgressRequests = egress?.map { req -> req.toUiModel() } ?: it.pendingEgressRequests,
                    capabilityRequests = capabilities ?: it.capabilityRequests,
                )
            }
        }
        controller?.let { publishStore(it.currentStore.value.snapshot.value) }
    }

    fun stageEgressRequest(request: BlindedEgressRequestUiModel) {
        commit { it.copy(pendingEgressRequests = it.pendingEgressRequests + request) }
    }

    /**
     * Makes the egress list exactly [requests] (RFC-0113 §4.3 step 3): a message decided elsewhere
     * (CLI, another window) disappears from the card, and a newly parked one appears.
     */
    fun syncEgressRequests(requests: List<BlindedEgressRequestUiModel>) {
        commit { it.copy(pendingEgressRequests = requests) }
    }

    fun stageVerification(verification: SplitVerificationUiModel) {
        staged.value = staged.value + verification
        publishStore(controller?.currentStore?.value?.snapshot?.value)
    }

    override fun send(intent: ApprovalGateIntent) {
        when (intent) {
            is ApprovalGateIntent.ApproveEgress -> {
                removeEgress(intent.requestId)
                decide("Egress request authorized for transmission.") {
                    val legacy = onEgressApproved
                    if (legacy != null) {
                        legacy(intent.requestId)
                    } else {
                        // RealRuntimeClient.approveEffect reads only runId; taskId is not used.
                        client?.capabilities?.approveEffect(intent.requestId, intent.requestId)?.raise()
                    }
                }
            }
            is ApprovalGateIntent.DenyEgress -> {
                removeEgress(intent.requestId)
                decide("Egress request denied.") {
                    val legacy = onEgressDenied
                    if (legacy != null) {
                        legacy(intent.requestId, intent.reason)
                    } else {
                        client?.capabilities?.denyEffect(intent.requestId, intent.requestId, intent.reason)
                    }
                }
            }
            is ApprovalGateIntent.ApproveToolEffect -> {
                commit { it.copy(toolApprovals = it.toolApprovals.filterNot { r -> r.runId == intent.runId && r.taskId == intent.taskId }) }
                decide("Tool call approved.") {
                    client?.capabilities?.approveEffect(intent.runId, intent.taskId)?.raise()
                }
            }
            is ApprovalGateIntent.DenyToolEffect -> {
                commit { it.copy(toolApprovals = it.toolApprovals.filterNot { r -> r.runId == intent.runId && r.taskId == intent.taskId }) }
                decide("Tool call denied.") {
                    client?.capabilities?.denyEffect(intent.runId, intent.taskId, intent.reason)
                }
            }
            is ApprovalGateIntent.ApproveCapability -> {
                commit { it.copy(capabilityRequests = it.capabilityRequests.filterNot { r -> r.requestId == intent.requestId }) }
                decide("Capability granted.") {
                    client?.capabilities?.approve(intent.requestId)
                }
            }
            is ApprovalGateIntent.DenyCapability -> {
                commit { it.copy(capabilityRequests = it.capabilityRequests.filterNot { r -> r.requestId == intent.requestId }) }
                decide("Capability request denied.") {
                    client?.capabilities?.deny(intent.requestId, intent.reason)
                }
            }
            is ApprovalGateIntent.ResolveProposal -> {
                val store = controller ?: return
                commit { it.copy(proposals = it.proposals.filterNot { p -> p.proposalId == intent.proposalId }) }
                val decision = if (intent.accept) ProposalResolutionDecision.ACCEPTED else ProposalResolutionDecision.REJECTED
                store.resolveProposal(intent.proposalId, decision)
                commit { it.copy(message = if (intent.accept) "Proposal accepted." else "Proposal rejected.") }
                scope.launch(ioDispatcher) { refreshAfterDecision() }
            }
            is ApprovalGateIntent.ConfirmVerification -> {
                val store = controller
                removeVerification(intent.nodeId)
                if (store != null) {
                    store.confirmImplementation(intent.nodeId)
                    commit { it.copy(message = "Implementation confirmed.") }
                    scope.launch(ioDispatcher) { refreshAfterDecision() }
                }
            }
            is ApprovalGateIntent.RunVerification -> {
                removeVerification(intent.nodeId)
                scope.launch(ioDispatcher) {
                    onVerificationExecuted?.invoke(intent.nodeId)
                    commit { it.copy(message = "Verification completed.") }
                }
            }
            is ApprovalGateIntent.InspectDiff -> inspectDiff()
            is ApprovalGateIntent.CloseDiffInspection -> commit { it.copy(inspectedDiffs = null) }
        }
    }

    private fun inspectDiff() {
        val runtime = client ?: return
        val projectId = controller?.state?.value?.projectId ?: return
        scope.launch(ioDispatcher) {
            try {
                val summary = runtime.diff.changes(projectId)
                commit {
                    it.copy(
                        inspectedDiffs = summary.files.map { file -> FileDiffPreview(filePath = file.path, lines = emptyList()) },
                    )
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                commit { it.copy(message = "Could not read the diff: ${t.message ?: t::class.simpleName}") }
            }
        }
    }

    private fun removeEgress(requestId: String) {
        commit { it.copy(pendingEgressRequests = it.pendingEgressRequests.filterNot { req -> req.requestId == requestId }) }
    }

    private fun removeVerification(nodeId: String) {
        staged.value = staged.value.filterNot { it.nodeId == nodeId }
        commit { it.copy(pendingVerifications = it.pendingVerifications.filterNot { v -> v.nodeId == nodeId }) }
    }

    /** Runs one decision off the UI thread, reports the outcome, then refreshes every source. */
    private fun decide(success: String, action: suspend () -> Unit) {
        scope.launch(ioDispatcher) {
            val message = try {
                action()
                success
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                "Failed: ${t.message ?: t::class.simpleName}"
            }
            commit { it.copy(message = message) }
            refreshAfterDecision()
        }
    }

    private suspend fun refreshAfterDecision() {
        try {
            refreshPolled()
        } catch (c: CancellationException) {
            throw c
        } catch (_: Throwable) {
            // the next poll tries again
        }
    }

    private fun publishStore(snapshot: GraphSnapshot?) {
        val proposals = snapshot?.proposals.orEmpty().filter { it.state == "PENDING" }.map { it.toUiModel() }
        val verifications = snapshot?.statuses?.values
            .orEmpty()
            .filter { it.derivedStatus == DerivedStatus.NEEDS_REVIEW }
            .map { view ->
                SplitVerificationUiModel(
                    nodeId = view.node.id,
                    taskTitle = view.node.title,
                    checkKind = view.node.checkKind?.name ?: "REVIEW",
                    checkSpec = view.node.checkSpec.orEmpty(),
                    scopePaths = view.node.scopePaths,
                )
            }
        commit { it.copy(proposals = proposals, pendingVerifications = verifications + staged.value) }
    }

    private fun commit(transform: (ApprovalGateUiState) -> ApprovalGateUiState) {
        _state.value = transform(_state.value)
        _pendingCount.value = _state.value.pendingCount
    }

    private suspend fun <T> sourceOrNull(read: suspend () -> T): T? = try {
        read()
    } catch (c: CancellationException) {
        throw c
    } catch (_: Throwable) {
        null
    }

    private fun CapabilityResult.raise() {
        if (this is CapabilityResult.Error) throw IllegalStateException("$code: $message")
    }

    companion object {
        const val POLL_MILLIS = 3_000L
    }
}

private val operationJson = Json { ignoreUnknownKeys = true }

/** One proposal in plain words, from its encoded operations (RFC-0012 `IntentOperation`). */
internal fun describeOperation(encoded: String): String {
    val obj: JsonObject = runCatching { operationJson.parseToJsonElement(encoded).jsonObject }.getOrNull()
        ?: return encoded
    fun field(name: String): String? = (obj[name] as? JsonPrimitive)?.contentOrNull
    return when (field("_kind")) {
        "AddNode" -> {
            val type = field("type")?.lowercase()?.replace('_', ' ') ?: "node"
            val parent = field("parentId")?.let { " under $it" }.orEmpty()
            "Create $type \"${field("title").orEmpty()}\"$parent"
        }
        "ModifyNode" -> "Change node ${field("id").orEmpty()}" + field("title")?.let { " to \"$it\"" }.orEmpty()
        "AddDependency" -> "Add dependency ${field("fromNodeId").orEmpty()} → ${field("toNodeId").orEmpty()}"
        "ArchiveNode" -> "Archive node ${field("id").orEmpty()}"
        "AddEdge" -> "Link ${field("fromNodeId").orEmpty()} → ${field("toNodeId").orEmpty()} (${field("edgeKind")?.lowercase() ?: "edge"})"
        "RemoveEdge" -> "Remove link ${field("fromNodeId").orEmpty()} → ${field("toNodeId").orEmpty()}"
        else -> encoded
    }
}

private fun IntentProposal.toUiModel() = ProposalUiModel(
    proposalId = id,
    summary = operations.map(::describeOperation),
    rationale = rationale,
    runTaint = runTaint,
)
