package fi.italeino.aidos.engine.ui

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import dev.aidos.tcr.agent.AgentBudgetUi
import dev.aidos.tcr.agent.AgentEgressRecordUi
import dev.aidos.tcr.agent.AgentEgressSummaryUi
import dev.aidos.tcr.agent.AgentLocality
import dev.aidos.tcr.agent.AgentPlacement
import dev.aidos.tcr.agent.AgentRoleUi
import dev.aidos.tcr.agent.AgentRunStatus
import dev.aidos.tcr.agent.AgentRunUiModel
import dev.aidos.tcr.agent.AgentRuntimeOverviewStats
import dev.aidos.tcr.agent.ExecutedToolCallUi
import dev.aidos.tcr.agent.PendingToolCallUi
import dev.aidos.tcr.agent.TaskGraphNodeUi
import dev.aidos.tcr.agent.TcrGuardianConfigUiModel
import dev.aidos.tcr.agent.TcrProjectModelConfigUiModel
import dev.aidos.tcr.agent.TcrRunLaunchConfigUiModel
import dev.aidos.tcr.agent.TcrTaintApprovalPolicy
import dev.aidos.tcr.agent.WorkerOutcomeUi
import dev.aidos.tcr.agent.WorkerSpawnUi
import fi.italeino.aidos.engine.EngineService
import fi.italeino.aidos.engine.EngineState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Android ViewModel managing Agent Runtime Overview, Telemetry, Model Routing, and Guardian state for [AgentsPane].
 */
class AgentsViewModel(application: Application) : AndroidViewModel(application) {
    private val appContext = application.applicationContext

    @Volatile
    private var cachedInstalledModels: List<String> = emptyList()

    @Volatile
    private var cachedDecisionModels: List<String> = listOf("laya-multilingual")

    private val _state = MutableStateFlow(
        AgentOverviewUiState(
            stats = SampleAgentRuns.defaultStats(),
            runs = SampleAgentRuns.create(),
            isEngineRunning = EngineService.state.value == EngineState.READY,
            projectConfig = buildProjectConfig(),
            runLaunchConfig = buildRunLaunchConfig(),
        )
    )
    val state: StateFlow<AgentOverviewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            EngineService.state.collect { engineState ->
                _state.value = _state.value.copy(
                    isEngineRunning = engineState == EngineState.READY
                )
            }
        }
        viewModelScope.launch {
            while (true) {
                refreshInstalledModels()
                refresh()
                delay(1_500)
            }
        }
    }

    private suspend fun refreshInstalledModels() {
        val installed = withContext(Dispatchers.IO) {
            runCatching {
                EngineService.instance?.catalogManager?.listInstalled()?.getOrNull().orEmpty()
            }.getOrDefault(emptyList())
        }
        cachedInstalledModels = installed.map { it.modelId }.distinct()
        cachedDecisionModels = (listOf("laya-multilingual") + installed.filter {
            it.quantization.equals("ONNX", ignoreCase = true) || it.modelId.contains("laya", ignoreCase = true)
        }.map { it.modelId }).distinct()
    }

    private fun parsePolicy(raw: String?): TcrTaintApprovalPolicy =
        if (raw?.trim().equals("GUARDIAN", ignoreCase = true)) {
            TcrTaintApprovalPolicy.GUARDIAN
        } else {
            TcrTaintApprovalPolicy.MANUAL
        }

    private fun buildProjectConfig(): TcrProjectModelConfigUiModel {
        val policy = parsePolicy(SettingsStore.getProjectGuardianPolicy(appContext))
        return TcrProjectModelConfigUiModel(
            projectId = "engine-default",
            projectName = "Active Project Defaults",
            mainModelId = SettingsStore.getProjectMainModelId(appContext),
            defaultRunModelId = SettingsStore.getProjectDefaultRunModelId(appContext),
            allowMainModelRunOverride = SettingsStore.getProjectAllowMainModelRunOverride(appContext),
            enforcedLocality = AgentLocality.fromWire(SettingsStore.getProjectEnforcedLocalityWire(appContext)),
            guardianConfig = TcrGuardianConfigUiModel(
                policy = policy,
                decisionModelId = SettingsStore.getProjectDecisionModelId(appContext),
                guardianLlmModelId = SettingsStore.getProjectGuardianLlmModelId(appContext),
                availableDecisionModels = cachedDecisionModels,
                availableLlmModels = cachedInstalledModels,
            ),
            availableLlmModels = cachedInstalledModels,
            availableDecisionModels = cachedDecisionModels,
        )
    }

    private fun buildRunLaunchConfig(): TcrRunLaunchConfigUiModel = TcrRunLaunchConfigUiModel(
        guardianPolicyOverride = SettingsStore.getRunGuardianPolicyOverride(appContext)?.let(::parsePolicy),
        runModelOverride = SettingsStore.getRunModelOverride(appContext),
        localityOverride = SettingsStore.getRunLocalityOverrideWire(appContext)?.let { AgentLocality.fromWire(it) },
    )

    private fun syncConfig() {
        val projectConfig = buildProjectConfig()
        val launchConfig = buildRunLaunchConfig()
        EngineService.instance?.agentRuntime?.apply {
            setRoutingConfig(SettingsStore.buildAgentRoutingConfig(appContext))
            setRunLaunchConfig(SettingsStore.buildAgentRunLaunchConfig(appContext))
        }
        _state.value = _state.value.copy(
            projectConfig = projectConfig,
            runLaunchConfig = launchConfig,
        )
    }

    fun refresh() {
        val projectConfig = buildProjectConfig()
        val launchConfig = buildRunLaunchConfig()
        val runtime = EngineService.instance?.agentRuntime
        if (runtime == null) {
            _state.value = _state.value.copy(
                isEngineRunning = EngineService.state.value == EngineState.READY,
                projectConfig = projectConfig,
                runLaunchConfig = launchConfig,
            )
            return
        }
        runtime.setRoutingConfig(SettingsStore.buildAgentRoutingConfig(appContext))
        runtime.setRunLaunchConfig(SettingsStore.buildAgentRunLaunchConfig(appContext))

        val rawRuns = runtime.listAll()
        if (rawRuns.isEmpty()) {
            _state.value = _state.value.copy(
                isEngineRunning = EngineService.state.value == EngineState.READY,
                projectConfig = projectConfig,
                runLaunchConfig = launchConfig,
            )
            return
        }

        val rawStats = runtime.stats()
        val uiStats = AgentRuntimeOverviewStats(
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
            AgentRunUiModel(
                runId = r.runId,
                ownerApp = r.owner,
                modelId = r.model,
                placement = AgentPlacement.fromWire(r.placement),
                locality = AgentLocality.fromWire(r.locality),
                status = when (r.status) {
                    "running" -> AgentRunStatus.RUNNING
                    "tool_calls" -> AgentRunStatus.TOOL_CALLS
                    "completed" -> AgentRunStatus.COMPLETED
                    "failed" -> AgentRunStatus.FAILED
                    "cancelled" -> AgentRunStatus.CANCELLED
                    else -> AgentRunStatus.RUNNING
                },
                seq = r.seq,
                steps = r.steps,
                maxSteps = r.maxSteps,
                promptPreview = r.promptPreview,
                replyText = r.replyText,
                errorMessage = r.error,
                pendingToolCall = r.pendingToolCall?.let {
                    PendingToolCallUi(it.callId, it.name, it.arguments.toString())
                },
                executedTools = r.executedTools.map {
                    ExecutedToolCallUi(it.toolName, it.argumentsJson, it.resultText)
                },
                egressSummary = AgentEgressSummaryUi(
                    totalCalls = r.egressRecords.size,
                    totalBytesOut = r.egressRecords.sumOf { it.requestBytes },
                    totalBytesIn = r.egressRecords.sumOf { it.responseBytes },
                    records = r.egressRecords.map {
                        AgentEgressRecordUi(
                            destinationHost = it.destinationHost,
                            method = it.method,
                            requestBytes = it.requestBytes,
                            responseBytes = it.responseBytes,
                            payloadHash = it.payloadHash,
                            durationMs = it.durationMs,
                            outcome = it.outcome,
                            timestamp = it.timestamp,
                        )
                    },
                ),
                startedAt = r.startedAt,
                finishedAt = r.finishedAt,
                role = AgentRoleUi.fromWire(r.role),
                parentSessionId = r.parentSessionId,
                parentRunId = r.parentRunId,
                workerBrief = r.workerBrief,
                tasks = r.tasks.map { t ->
                    TaskGraphNodeUi(
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
                    WorkerSpawnUi(
                        taskId = s.taskId,
                        workerSessionId = s.workerSessionId,
                        childRunId = s.childRunId,
                        brief = s.brief,
                        budgetSplitWays = s.budgetSplitWays,
                        delegatedCapabilities = s.delegatedCapabilities,
                    )
                },
                budget = r.budget?.let { b ->
                    AgentBudgetUi(
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
                    WorkerOutcomeUi(
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
        _state.value = _state.value.copy(
            isEngineRunning = EngineService.state.value == EngineState.READY,
            stats = uiStats,
            runs = uiRuns,
            projectConfig = projectConfig,
            runLaunchConfig = launchConfig,
        )
    }

    fun selectAppFilter(app: String?) {
        _state.value = _state.value.copy(selectedAppFilter = app)
    }

    fun selectStatusFilter(status: AgentFilterTab) {
        _state.value = _state.value.copy(selectedStatusFilter = status)
    }

    fun selectPlacementFilter(placement: AgentPlacement?) {
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

    fun selectViewLayout(layout: AgentViewLayout) {
        _state.value = _state.value.copy(selectedViewLayout = layout)
    }

    fun selectRunDetail(runId: String?) {
        _state.value = _state.value.copy(selectedRunId = runId)
    }

    fun cancelRun(runId: String) {
        EngineService.instance?.agentRuntime?.cancelAny(runId)
        _state.value = _state.value.copy(
            runs = _state.value.runs.map {
                if (it.runId == runId) it.copy(status = AgentRunStatus.CANCELLED) else it
            }
        )
    }

    fun setMainModel(modelId: String) {
        SettingsStore.setProjectMainModelId(appContext, modelId)
        syncConfig()
    }

    fun setDefaultRunModel(modelId: String) {
        SettingsStore.setProjectDefaultRunModelId(appContext, modelId)
        syncConfig()
    }

    fun setProjectLocality(locality: AgentLocality) {
        SettingsStore.setProjectEnforcedLocalityWire(appContext, locality.wire)
        syncConfig()
    }

    fun setProjectGuardianPolicy(policy: TcrTaintApprovalPolicy) {
        SettingsStore.setProjectGuardianPolicy(appContext, policy.name)
        syncConfig()
    }

    fun setDecisionModel(modelId: String) {
        SettingsStore.setProjectDecisionModelId(appContext, modelId)
        syncConfig()
    }

    fun setGuardianLlm(modelId: String) {
        SettingsStore.setProjectGuardianLlmModelId(appContext, modelId)
        syncConfig()
    }

    fun setRunGuardianPolicyOverride(policy: TcrTaintApprovalPolicy?) {
        SettingsStore.setRunGuardianPolicyOverride(appContext, policy?.name)
        syncConfig()
    }

    fun setRunModelOverride(modelId: String) {
        SettingsStore.setRunModelOverride(appContext, modelId)
        syncConfig()
    }

    fun setRunLocalityOverride(locality: AgentLocality?) {
        SettingsStore.setRunLocalityOverrideWire(appContext, locality?.wire)
        syncConfig()
    }
}
