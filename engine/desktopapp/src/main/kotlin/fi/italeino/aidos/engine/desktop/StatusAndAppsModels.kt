package fi.italeino.aidos.engine.desktop

import dev.aidos.tcr.agent.AgentLocality
import dev.aidos.tcr.agent.TcrGuardianConfigUiModel
import dev.aidos.tcr.agent.TcrProjectModelConfigUiModel
import dev.aidos.tcr.agent.TcrRunLaunchConfigUiModel
import dev.aidos.tcr.agent.TcrTaintApprovalPolicy
import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.agent.AgentRoutingConfigDto
import fi.italeino.aidos.engine.agent.AgentRunLaunchConfigDto
import fi.italeino.aidos.engine.approval.AppApprovalRecord
import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.ui.AgentFilterTab
import fi.italeino.aidos.engine.ui.AgentOverviewUiState
import fi.italeino.aidos.engine.ui.ResidentModel
import fi.italeino.aidos.engine.ui.SampleAgentRuns
import fi.italeino.aidos.engine.ui.StatusUiState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Desktop counterpart of Android's StatusViewModel: polls the in-process host every 2 s, as the
 * Android Home screen does while visible (RAM and resident models change without UI events).
 */
class DesktopStatusModel(
    private val host: DesktopEngineHost,
    private val scope: CoroutineScope,
    private val status: () -> fi.italeino.aidos.engine.control.StatusControl = { LocalStatusControl(host) },
    private val apps: () -> fi.italeino.aidos.engine.control.AppsControl = { LocalAppsControl(host) },
) {
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
            // An Engine service already owns the port and engine.json: starting a second one would break it.
            if (!host.isRunning.value && !EngineAttach.shouldStartOwnEngine(autoStart = true)) return@launch
            if (host.isRunning.value) host.stop() else host.start()
            refresh()
        }
    }

    suspend fun refresh() {
        try {
            val info = status().status()
            val approvals = apps().list()
            _state.value = StatusUiState(
                isEngineRunning = info.running,
                engineStateName = info.stateName,
                residentModels = info.residentModels.map { ResidentModel(it.id, it.displayName, it.quantization, it.loadedAgoMs) },
                memory = DesktopDevice.memory(),
                freeStorageGb = DesktopDevice.freeStorageGb(),
                approvedApps = approvals.filter { it.status == AppApprovalStatus.APPROVED }.map { it.displayName },
                pendingAppCount = approvals.count { it.status == AppApprovalStatus.PENDING },
                linkedPeerCount = info.linkedPeerCount,
            )
        } catch (_: Exception) {
            // The Engine service stopped answering: show it as off rather than a stale "running".
            _state.value = _state.value.copy(isEngineRunning = false, engineStateName = "OFF")
        }
    }
}

/**
 * Desktop counterpart of ConnectedAppsViewModel. There is no Binder on Windows, so apps only
 * appear through [simulateHandshake], which records the same first-handshake entry
 * EngineHandshakeImpl would, letting the approve/deny/revoke/undo flow be exercised.
 */
class DesktopAppsModel(
    private val host: DesktopEngineHost,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 1_000,
    private val control: () -> fi.italeino.aidos.engine.control.AppsControl = { LocalAppsControl(host) },
) {
    private val _approvals = MutableStateFlow<List<AppApprovalRecord>>(emptyList())

    /** The apps and their decisions, polled through [fi.italeino.aidos.engine.control.AppsControl] (RFC-0115, D61). */
    val approvals: StateFlow<List<AppApprovalRecord>> = _approvals.asStateFlow()

    init {
        scope.launch {
            while (isActive) {
                refresh()
                delay(pollMillis)
            }
        }
    }

    // A failed poll keeps the last list on screen: an Engine that is restarting is not "no apps".
    private suspend fun refresh() {
        runCatching { control().list() }.onSuccess { _approvals.value = it }
    }

    fun simulateHandshake(displayName: String) {
        val name = displayName.trim().ifEmpty { "Debug App" }
        val id = "debug." + name.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
        scope.launch {
            val store = host.approvalStore
            if (store.getApproval(id) == null) store.recordFirstHandshake(id, name)
            else store.recordHandshakeAttempt(id)
            refresh()
        }
    }

    fun approve(id: String) = act { it.approve(id) }
    fun deny(id: String) = act { it.deny(id) }
    fun revoke(id: String) = act { it.revoke(id) }
    fun setAgentAllowed(id: String, allowed: Boolean) = act { it.setAgentAllowed(id, allowed) }
    fun setLocalityLimit(id: String, localityLimit: String?) = act { it.setLocalityLimit(id, localityLimit) }
    fun undoDeny(id: String) = act { it.undoDeny(id) }

    private fun act(action: suspend (fi.italeino.aidos.engine.control.AppsControl) -> Boolean) {
        scope.launch {
            runCatching { action(control()) }
            refresh()
        }
    }
}

/**
 * Desktop ViewModel managing state for [AgentsPane].
 * Reactive telemetry for active runs, tool waits, model placement, and RFC-0042 egress.
 */
class DesktopAgentsModel(
    private val host: DesktopEngineHost,
    private val scope: CoroutineScope,
    private val prefs: DesktopPreferences? = null,
) {
    private val _state = MutableStateFlow(AgentOverviewUiState())
    val state: StateFlow<AgentOverviewUiState> = _state.asStateFlow()

    @Volatile
    private var cachedInstalledModels: List<String> = emptyList()

    init {
        refresh()
        scope.launch {
            while (true) {
                refreshInstalledModels()
                refresh()
                delay(1_500)
            }
        }
    }

    private suspend fun refreshInstalledModels() {
        val installed = withContext(Dispatchers.IO) {
            host.catalogManager.listInstalled().getOrNull().orEmpty()
        }
        cachedInstalledModels = installed.map { it.modelId }.distinct()
    }

    private fun parsePolicy(raw: String?): TcrTaintApprovalPolicy =
        if (raw?.trim().equals("GUARDIAN", ignoreCase = true)) {
            TcrTaintApprovalPolicy.GUARDIAN
        } else {
            TcrTaintApprovalPolicy.MANUAL
        }

    private fun buildProjectConfig(): TcrProjectModelConfigUiModel {
        val data = prefs?.data?.value ?: DesktopPreferencesData()
        val policy = parsePolicy(data.projectGuardianPolicy ?: data.globalGuardianPolicy)
        val decisionModel = data.projectDecisionModelId.ifBlank { data.globalDecisionModelId }
        val guardianLlm = data.projectGuardianLlmModelId.ifBlank { data.globalGuardianLlmModelId }
        val locality = AgentLocality.fromWire(data.projectEnforcedLocality ?: data.globalLocality)
        val mainModel = data.projectMainModelId.ifBlank { data.globalMainModelId }
        val defaultRunModel = data.projectDefaultRunModelId.ifBlank { data.globalDefaultRunModelId }
        val decisionChoices = (listOf("laya-multilingual") + cachedInstalledModels).distinct()
        return TcrProjectModelConfigUiModel(
            projectId = "engine-default",
            projectName = "Active Project Defaults",
            mainModelId = mainModel,
            defaultRunModelId = defaultRunModel,
            allowMainModelRunOverride = data.projectAllowMainModelRunOverride,
            enforcedLocality = locality,
            guardianConfig = TcrGuardianConfigUiModel(
                policy = policy,
                decisionModelId = decisionModel,
                guardianLlmModelId = guardianLlm,
                availableDecisionModels = decisionChoices,
                availableLlmModels = cachedInstalledModels,
            ),
            availableLlmModels = cachedInstalledModels,
            availableDecisionModels = decisionChoices,
        )
    }

    private fun buildRunLaunchConfig(): TcrRunLaunchConfigUiModel {
        val data = prefs?.data?.value ?: DesktopPreferencesData()
        return TcrRunLaunchConfigUiModel(
            guardianPolicyOverride = data.runGuardianPolicyOverride?.let(::parsePolicy),
            runModelOverride = data.runModelOverride,
            localityOverride = data.runLocalityOverride?.let { AgentLocality.fromWire(it) },
        )
    }

    fun syncConfigToRuntime() {
        val projectConfig = buildProjectConfig()
        val launchConfig = buildRunLaunchConfig()
        host.agentRuntime?.apply {
            setRoutingConfig(
                AgentRoutingConfigDto(
                    mainModelId = projectConfig.mainModelId,
                    defaultRunModelId = projectConfig.defaultRunModelId,
                    allowMainModelRunOverride = projectConfig.allowMainModelRunOverride,
                    enforcedLocality = projectConfig.enforcedLocality.wire,
                    guardianPolicy = projectConfig.guardianConfig.policy.name,
                    decisionModelId = projectConfig.guardianConfig.decisionModelId,
                    guardianLlmModelId = projectConfig.guardianConfig.guardianLlmModelId,
                )
            )
            setRunLaunchConfig(
                AgentRunLaunchConfigDto(
                    guardianPolicyOverride = launchConfig.guardianPolicyOverride?.name,
                    runModelOverride = launchConfig.runModelOverride,
                    localityOverride = launchConfig.localityOverride?.wire,
                )
            )
        }
        _state.value = _state.value.copy(
            projectConfig = projectConfig,
            runLaunchConfig = launchConfig,
        )
    }

    fun setMainModel(modelId: String) {
        prefs?.setProjectMainModelId(modelId)
        syncConfigToRuntime()
    }

    fun setDefaultRunModel(modelId: String) {
        prefs?.setProjectDefaultRunModelId(modelId)
        syncConfigToRuntime()
    }

    fun setProjectLocality(locality: AgentLocality) {
        prefs?.setProjectEnforcedLocality(locality.wire)
        syncConfigToRuntime()
    }

    fun setProjectGuardianPolicy(policy: TcrTaintApprovalPolicy) {
        prefs?.setProjectGuardianPolicy(policy.name)
        syncConfigToRuntime()
    }

    fun setDecisionModel(modelId: String) {
        prefs?.setProjectDecisionModelId(modelId)
        syncConfigToRuntime()
    }

    fun setGuardianLlm(modelId: String) {
        prefs?.setProjectGuardianLlmModelId(modelId)
        syncConfigToRuntime()
    }

    fun setRunGuardianPolicyOverride(policy: TcrTaintApprovalPolicy?) {
        prefs?.setRunGuardianPolicyOverride(policy?.name)
        syncConfigToRuntime()
    }

    fun setRunModelOverride(modelId: String) {
        prefs?.setRunModelOverride(modelId)
        syncConfigToRuntime()
    }

    fun setRunLocalityOverride(locality: AgentLocality?) {
        prefs?.setRunLocalityOverride(locality?.wire)
        syncConfigToRuntime()
    }

    fun selectAppFilter(app: String?) {
        _state.value = _state.value.copy(selectedAppFilter = app)
    }

    fun selectStatusFilter(status: AgentFilterTab) {
        _state.value = _state.value.copy(selectedStatusFilter = status)
    }

    fun selectPlacementFilter(placement: dev.aidos.tcr.agent.AgentPlacement?) {
        _state.value = _state.value.copy(selectedPlacementFilter = placement)
    }

    fun search(query: String) {
        _state.value = _state.value.copy(searchQuery = query)
    }

    fun resetFilters() {
        _state.value = _state.value.copy(
            selectedAppFilter = null,
            selectedStatusFilter = AgentFilterTab.ALL,
            selectedPlacementFilter = null,
            searchQuery = "",
        )
    }

    fun selectViewLayout(layout: fi.italeino.aidos.engine.ui.AgentViewLayout) {
        _state.value = _state.value.copy(selectedViewLayout = layout)
    }

    fun selectRunDetail(runId: String?) {
        _state.value = _state.value.copy(selectedRunId = runId)
    }

    fun cancelRun(runId: String) {
        host.agentRuntime?.cancelAny(runId)
        refresh()
    }

    fun refresh() {
        val projectConfig = buildProjectConfig()
        val runLaunchConfig = buildRunLaunchConfig()
        val runtime = host.agentRuntime
        if (runtime == null) {
            _state.value = _state.value.copy(
                isEngineRunning = host.isRunning.value,
                stats = dev.aidos.tcr.agent.AgentRuntimeOverviewStats(),
                runs = emptyList(),
                projectConfig = projectConfig,
                runLaunchConfig = runLaunchConfig,
            )
            return
        }
        syncConfigToRuntime()

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

        val uiRuns = if (rawRuns.isNotEmpty()) {
            rawRuns.map { r ->
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
                    finishedAt = r.finishedAt,
                    role = dev.aidos.tcr.agent.AgentRoleUi.fromWire(r.role),
                    parentSessionId = r.parentSessionId,
                    parentRunId = r.parentRunId,
                    workerBrief = r.workerBrief,
                    tasks = r.tasks.map { t ->
                        dev.aidos.tcr.agent.TaskGraphNodeUi(
                            taskId = t.taskId,
                            ordinal = t.ordinal,
                            kind = t.kind,
                            description = t.description,
                            state = t.state,
                            dependsOnTaskIds = t.dependsOnTaskIds,
                            toolName = t.toolName,
                            awaitingRunId = t.awaitingRunId,
                        )
                    },
                    workerSpawns = r.workerSpawns.map { s ->
                        dev.aidos.tcr.agent.WorkerSpawnUi(
                            taskId = s.taskId,
                            workerSessionId = s.workerSessionId,
                            childRunId = s.childRunId,
                            brief = s.brief,
                            budgetSplitWays = s.budgetSplitWays,
                            delegatedCapabilities = s.delegatedCapabilities,
                        )
                    },
                    budget = r.budget?.let { b ->
                        dev.aidos.tcr.agent.AgentBudgetUi(
                            modelCalls = b.modelCalls,
                            inputTokens = b.inputTokens,
                            outputTokens = b.outputTokens,
                            costUnits = b.costUnits,
                            steps = b.steps,
                            maxSteps = b.maxSteps,
                            splitWays = b.splitWays,
                        )
                    },
                    workerOutcome = r.workerOutcome?.let { o ->
                        dev.aidos.tcr.agent.WorkerOutcomeUi(
                            workerSessionId = o.workerSessionId,
                            commitRef = o.commitRef,
                            outcomeSummary = o.outcomeSummary,
                            committedAt = o.committedAt,
                        )
                    },
                    isParkedOnChild = r.isParkedOnChild,
                    awaitingChildRunId = r.awaitingChildRunId,
                    guardianPolicy = parsePolicy(r.guardianPolicy),
                    modelChosenByMainModel = r.modelChosenByMainModel,
                    enforcedLocality = r.enforcedLocality?.let { AgentLocality.fromWire(it) },
                )
            }
        } else {
            // Demonstration data showcasing RFC-0011 Driver/Worker Topology, DAG dependencies, budget splits & treeless commits
            SampleAgentRuns.create()
        }

        _state.value = _state.value.copy(
            isEngineRunning = host.isRunning.value,
            stats = uiStats,
            runs = uiRuns,
            projectConfig = projectConfig,
            runLaunchConfig = runLaunchConfig,
        )
    }

    private fun sampleOrchestrationRuns(): List<dev.aidos.tcr.agent.AgentRunUiModel> {
        val driverRun = dev.aidos.tcr.agent.AgentRunUiModel(
            runId = "run-driver-001",
            ownerApp = "Dictator Workspace",
            modelId = "gemma-2-2b-it",
            placement = dev.aidos.tcr.agent.AgentPlacement.LOCAL,
            locality = dev.aidos.tcr.agent.AgentLocality.LOCAL_ONLY,
            status = dev.aidos.tcr.agent.AgentRunStatus.TOOL_CALLS,
            role = dev.aidos.tcr.agent.AgentRoleUi.DRIVER,
            seq = 4,
            steps = 4,
            maxSteps = 12,
            promptPreview = "Refactor audio pipeline and spawn worker to process audio frequency buffers",
            replyText = "",
            isParkedOnChild = true,
            awaitingChildRunId = "run-worker-002",
            tasks = listOf(
                dev.aidos.tcr.agent.TaskGraphNodeUi("t1", 1, "TOOL_CALL", "Inspect audio input device descriptors", "COMPLETED"),
                dev.aidos.tcr.agent.TaskGraphNodeUi("t2", 2, "COMPOSITE", "Spawn worker: analyze FFT frequency buffers", "AWAITING_INPUT", listOf("t1"), awaitingRunId = "run-worker-002"),
                dev.aidos.tcr.agent.TaskGraphNodeUi("t3", 3, "TOOL_CALL", "Merge audio transform buffers", "PENDING", listOf("t2")),
                dev.aidos.tcr.agent.TaskGraphNodeUi("t4", 4, "MODEL_CALL", "Draft voice recognition response", "PENDING", listOf("t3")),
            ),
            workerSpawns = listOf(
                dev.aidos.tcr.agent.WorkerSpawnUi(
                    taskId = "t2",
                    workerSessionId = "sess-worker-002",
                    childRunId = "run-worker-002",
                    brief = "Analyze FFT frequency buffers",
                    budgetSplitWays = 2,
                    delegatedCapabilities = listOf("STT_QUERY", "FS_READ")
                )
            ),
            budget = dev.aidos.tcr.agent.AgentBudgetUi(
                modelCalls = 8,
                inputTokens = 8000L,
                outputTokens = 4000L,
                steps = 12,
                maxSteps = 12,
                splitWays = 2
            ),
            startedAt = System.currentTimeMillis() - 120_000,
        )

        val workerRun = dev.aidos.tcr.agent.AgentRunUiModel(
            runId = "run-worker-002",
            ownerApp = "Dictator Workspace",
            modelId = "gemma-2-2b-it",
            placement = dev.aidos.tcr.agent.AgentPlacement.LOCAL,
            locality = dev.aidos.tcr.agent.AgentLocality.LOCAL_ONLY,
            status = dev.aidos.tcr.agent.AgentRunStatus.RUNNING,
            role = dev.aidos.tcr.agent.AgentRoleUi.WORKER,
            parentSessionId = "sess-driver-001",
            parentRunId = "run-driver-001",
            workerBrief = "Analyze FFT frequency buffers",
            seq = 2,
            steps = 2,
            maxSteps = 6,
            promptPreview = "Task Brief: Analyze FFT frequency buffers (delegated from driver)",
            replyText = "",
            tasks = listOf(
                dev.aidos.tcr.agent.TaskGraphNodeUi("wt1", 1, "TOOL_CALL", "Read raw audio stream samples", "COMPLETED"),
                dev.aidos.tcr.agent.TaskGraphNodeUi("wt2", 2, "MODEL_CALL", "Compute frequency bins & spectral centroids", "RUNNING", listOf("wt1")),
            ),
            budget = dev.aidos.tcr.agent.AgentBudgetUi(
                modelCalls = 4,
                inputTokens = 4000L,
                outputTokens = 2000L,
                steps = 6,
                maxSteps = 6,
                splitWays = 2
            ),
            startedAt = System.currentTimeMillis() - 60_000,
        )

        val completedWorkerRun = dev.aidos.tcr.agent.AgentRunUiModel(
            runId = "run-worker-003",
            ownerApp = "Agentic Messenger",
            modelId = "qwen-2.5-1.5b",
            placement = dev.aidos.tcr.agent.AgentPlacement.LOCAL,
            locality = dev.aidos.tcr.agent.AgentLocality.LOCAL_ONLY,
            status = dev.aidos.tcr.agent.AgentRunStatus.COMPLETED,
            role = dev.aidos.tcr.agent.AgentRoleUi.WORKER,
            parentSessionId = "sess-amp-driver-1",
            parentRunId = "run-amp-driver-1",
            workerBrief = "Verify cryptographic Universal Envelope signatures",
            seq = 3,
            steps = 3,
            maxSteps = 4,
            promptPreview = "Verify envelope cryptographic signatures and provenance seals",
            replyText = "Verification complete: 3 envelope signatures verified valid against DeviceIdentity keys.",
            tasks = listOf(
                dev.aidos.tcr.agent.TaskGraphNodeUi("at1", 1, "TOOL_CALL", "Read envelope header signatures", "COMPLETED"),
                dev.aidos.tcr.agent.TaskGraphNodeUi("at2", 2, "MODEL_CALL", "Verify Ed25519 signatures", "COMPLETED", listOf("at1")),
                dev.aidos.tcr.agent.TaskGraphNodeUi("at3", 3, "TOOL_CALL", "Write verification receipt", "COMPLETED", listOf("at2")),
            ),
            workerOutcome = dev.aidos.tcr.agent.WorkerOutcomeUi(
                workerSessionId = "sess-amp-worker-3",
                commitRef = "refs/aidos/workers/sess-amp-worker-3",
                outcomeSummary = "Verified 3 envelope signatures; zero cryptographic errors found.",
                committedAt = "2026-11-09T15:40:00Z"
            ),
            startedAt = System.currentTimeMillis() - 300_000,
            finishedAt = System.currentTimeMillis() - 240_000,
        )

        return listOf(driverRun, workerRun, completedWorkerRun)
    }
}
