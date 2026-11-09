package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.approval.AppApprovalRecord
import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.ui.AgentFilterTab
import fi.italeino.aidos.engine.ui.AgentOverviewUiState
import fi.italeino.aidos.engine.ui.ResidentModel
import fi.italeino.aidos.engine.ui.StatusUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop counterpart of Android's StatusViewModel: polls the in-process host every 2 s, as the
 * Android Home screen does while visible (RAM and resident models change without UI events).
 */
class DesktopStatusModel(private val host: DesktopEngineHost, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(StatusUiState())
    val state: StateFlow<StatusUiState> = _state.asStateFlow()

    init {
        scope.launch {
            while (true) {
                refresh()
                delay(2_000)
            }
        }
    }

    fun toggleEngine() {
        scope.launch {
            if (host.isRunning.value) host.stop() else host.start()
            refresh()
        }
    }

    suspend fun refresh() {
        val approvals = host.approvalStore.listAllApprovals()
        val runtime = host.modelRuntime
        val resident = if (runtime == null) emptyList() else {
            val installedById = withContext(Dispatchers.IO) {
                host.catalogManager.listInstalled().getOrNull()
            }.orEmpty().associateBy { it.modelId }
            val now = System.currentTimeMillis()
            runtime.loaded().map { id ->
                val installed = installedById[id]
                ResidentModel(
                    id = id,
                    displayName = installed?.userLabel ?: id,
                    quantization = installed?.quantization ?: "unknown",
                    loadedAgoMs = runtime.loadedAtMillis(id)?.let { now - it } ?: 0L,
                )
            }
        }
        val port = host.boundPort.value
        _state.value = StatusUiState(
            isEngineRunning = host.isRunning.value,
            // EngineState has no "stopped" value (Android shows STARTING when off); say OFF here.
            engineStateName = when {
                host.isRunning.value && port != null -> "${host.state.value.name} · 127.0.0.1:$port"
                host.state.value == EngineState.STARTING && !host.isRunning.value -> "OFF"
                else -> host.state.value.name
            },
            residentModels = resident,
            memory = DesktopDevice.memory(),
            freeStorageGb = DesktopDevice.freeStorageGb(),
            approvedApps = approvals.filter { it.status == AppApprovalStatus.APPROVED }.map { it.displayName },
            pendingAppCount = approvals.count { it.status == AppApprovalStatus.PENDING },
        )
    }
}

/**
 * Desktop counterpart of ConnectedAppsViewModel. There is no Binder on Windows, so apps only
 * appear through [simulateHandshake], which records the same first-handshake entry
 * EngineHandshakeImpl would, letting the approve/deny/revoke/undo flow be exercised.
 */
class DesktopAppsModel(private val host: DesktopEngineHost, private val scope: CoroutineScope) {
    val approvals: StateFlow<List<AppApprovalRecord>> =
        host.approvalStore.watchApprovals().stateIn(scope, SharingStarted.Eagerly, emptyList())

    fun simulateHandshake(displayName: String) {
        val name = displayName.trim().ifEmpty { "Debug App" }
        val id = "debug." + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        scope.launch {
            val store = host.approvalStore
            if (store.getApproval(id) == null) store.recordFirstHandshake(id, name)
            else store.recordHandshakeAttempt(id)
        }
    }

    fun approve(id: String) = act { it.approveApp(id) }
    fun deny(id: String) = act { it.denyApp(id) }
    fun revoke(id: String) = act { it.revokeApproval(id) }
    fun undoDeny(id: String) = act { it.undoDenyApp(id) }

    private fun act(action: suspend (fi.italeino.aidos.engine.approval.AppApprovalStore) -> Unit) {
        scope.launch { action(host.approvalStore) }
    }
}

/**
 * Desktop ViewModel managing state for [AgentsPane].
 * Reactive telemetry for active runs, tool waits, model placement, and RFC-0042 egress.
 */
class DesktopAgentsModel(private val host: DesktopEngineHost, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(AgentOverviewUiState())
    val state: StateFlow<AgentOverviewUiState> = _state.asStateFlow()

    init {
        scope.launch {
            while (true) {
                refresh()
                delay(1_500)
            }
        }
    }

    fun selectAppFilter(app: String?) {
        _state.value = _state.value.copy(selectedAppFilter = app)
    }

    fun selectStatusFilter(status: AgentFilterTab) {
        _state.value = _state.value.copy(selectedStatusFilter = status)
    }

    fun selectRunDetail(runId: String?) {
        _state.value = _state.value.copy(selectedRunId = runId)
    }

    fun cancelRun(runId: String) {
        host.agentRuntime?.cancelAny(runId)
        refresh()
    }

    fun refresh() {
        val runtime = host.agentRuntime
        if (runtime == null) {
            _state.value = _state.value.copy(
                isEngineRunning = host.isRunning.value,
                stats = dev.aidos.tcr.agent.AgentRuntimeOverviewStats(),
                runs = emptyList()
            )
            return
        }

        val rawStats = runtime.stats()
        val rawRuns = runtime.listAll()

        val uiStats = dev.aidos.tcr.agent.AgentRuntimeOverviewStats(
            activeRuns = rawStats.activeRuns,
            maxConcurrentRuns = rawStats.maxConcurrentRuns,
            waitingToolCalls = rawStats.waitingToolCalls,
            completedRuns = rawStats.completedRuns,
            failedRuns = rawStats.failedRuns,
            totalRetainedRuns = rawStats.totalRetainedRuns,
            appsCount = rawStats.appsCount,
            runsByApp = rawStats.runsByApp,
            totalEgressCalls = rawStats.totalEgressCalls,
        )

        val uiRuns = rawRuns.map { r ->
            dev.aidos.tcr.agent.AgentRunUiModel(
                runId = r.runId,
                ownerApp = r.owner,
                modelId = r.model,
                placement = dev.aidos.tcr.agent.AgentPlacement.fromWire(r.placement),
                locality = dev.aidos.tcr.agent.AgentLocality.fromWire(r.locality),
                status = when (r.status) {
                    "running" -> dev.aidos.tcr.agent.AgentRunStatus.RUNNING
                    "tool_calls" -> dev.aidos.tcr.agent.AgentRunStatus.TOOL_CALLS
                    "completed" -> dev.aidos.tcr.agent.AgentRunStatus.COMPLETED
                    "failed" -> dev.aidos.tcr.agent.AgentRunStatus.FAILED
                    "cancelled" -> dev.aidos.tcr.agent.AgentRunStatus.CANCELLED
                    else -> dev.aidos.tcr.agent.AgentRunStatus.RUNNING
                },
                seq = r.seq,
                steps = r.steps,
                maxSteps = r.maxSteps,
                promptPreview = r.promptPreview,
                replyText = r.replyText,
                errorMessage = r.error,
                pendingToolCall = r.pendingToolCall?.let {
                    dev.aidos.tcr.agent.PendingToolCallUi(it.callId, it.name, it.arguments.toString())
                },
                executedTools = r.executedTools.map {
                    dev.aidos.tcr.agent.ExecutedToolCallUi(it.toolName, it.argumentsJson, it.resultText)
                },
                egressSummary = dev.aidos.tcr.agent.AgentEgressSummaryUi(
                    totalCalls = r.egressRecords.size,
                    totalBytesOut = r.egressRecords.sumOf { it.requestBytes },
                    totalBytesIn = r.egressRecords.sumOf { it.responseBytes },
                    records = r.egressRecords.map {
                        dev.aidos.tcr.agent.AgentEgressRecordUi(
                            destinationHost = it.destinationHost,
                            method = it.method,
                            requestBytes = it.requestBytes,
                            responseBytes = it.responseBytes,
                            payloadHash = it.payloadHash,
                            durationMs = it.durationMs,
                            outcome = it.outcome,
                            timestamp = it.timestamp
                        )
                    }
                ),
                startedAt = r.startedAt,
                finishedAt = r.finishedAt
            )
        }

        _state.value = _state.value.copy(
            isEngineRunning = host.isRunning.value,
            stats = uiStats,
            runs = uiRuns
        )
    }
}
