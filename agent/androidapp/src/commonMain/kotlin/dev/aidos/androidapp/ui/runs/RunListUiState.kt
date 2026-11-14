package dev.aidos.androidapp.ui.runs

import dev.aidos.androidapp.ui.projects.AgentModelLocality
import dev.aidos.androidapp.ui.projects.AgentTaintPolicy
import dev.aidos.androidapp.ui.projects.ModelRoutingConfigState
import dev.aidos.api.RunOptions
import dev.aidos.api.RunResult
import dev.aidos.api.RunSummary
import dev.aidos.api.RuntimeClient
import dev.aidos.api.SessionDetail
import dev.aidos.api.UserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Pure-Kotlin per-run launch override state that cleanly maps to [RunOptions].
 *
 * `null` or blank fields inherit from the active project / global defaults.
 */
data class RunLaunchConfigState(
    val guardianPolicyOverride: AgentTaintPolicy? = null,
    val runModelOverride: String = "",
    val localityOverride: AgentModelLocality? = null,
) {
    fun effectivePolicy(projectDefaults: ModelRoutingConfigState = ModelRoutingConfigState()): AgentTaintPolicy =
        guardianPolicyOverride ?: projectDefaults.guardianConfig.policy

    fun effectiveModelId(projectDefaults: ModelRoutingConfigState = ModelRoutingConfigState()): String =
        runModelOverride.trim()
            .ifBlank { projectDefaults.defaultRunModelId.trim() }
            .ifBlank { projectDefaults.mainModelId.trim() }

    fun effectiveLocality(projectDefaults: ModelRoutingConfigState = ModelRoutingConfigState()): AgentModelLocality =
        localityOverride ?: projectDefaults.enforcedLocality

    fun toRunOptions(
        base: RunOptions = RunOptions(),
        projectDefaults: ModelRoutingConfigState? = null,
    ): RunOptions {
        val resolvedPolicy = guardianPolicyOverride ?: projectDefaults?.guardianConfig?.policy
        val resolvedModel = runModelOverride.trim().ifBlank { null }
            ?: projectDefaults?.defaultRunModelId?.trim()?.ifBlank { null }
            ?: projectDefaults?.mainModelId?.trim()?.ifBlank { null }
        val resolvedLocality = localityOverride ?: projectDefaults?.enforcedLocality
        return base.copy(
            taintApprovalPolicy = resolvedPolicy?.name ?: base.taintApprovalPolicy,
            modelId = resolvedModel ?: base.modelId,
            modelLocality = resolvedLocality?.settingsWire ?: base.modelLocality,
        )
    }
}

/**
 * Per-run display metadata showing effective Guardian policy, model provenance, and locality.
 */
data class RunDisplayMetadata(
    val runId: String,
    val guardianPolicy: AgentTaintPolicy = AgentTaintPolicy.MANUAL,
    val modelId: String = "",
    val modelChosenByMainModel: Boolean = false,
    val enforcedLocality: AgentModelLocality? = null,
)

/**
 * UI state for the run list within a session (M28, RFC-0050, RFC-0052).
 *
 * Runs are listed most-recent-first. An in-progress run (no [RunSummary.endedAt]) appears at
 * the top. The user taps a run to reach the event stream (see [EventStreamUiState]).
 */
sealed interface RunListUiState {
    data object Loading : RunListUiState

    data class Ready(
        val sessionId: String,
        val sessionName: String,
        val runs: List<RunSummary>,
        val launchConfig: RunLaunchConfigState = RunLaunchConfigState(),
        val runMetadata: Map<String, RunDisplayMetadata> = emptyMap(),
        val sendError: String? = null,
    ) : RunListUiState {
        val runningRun: RunSummary? get() = runs.firstOrNull { it.endedAt == null }
        val completedRuns: List<RunSummary> get() = runs.filter { it.endedAt != null }
    }

    data class Error(
        val sessionId: String,
        val message: String,
    ) : RunListUiState
}

/**
 * Platform-neutral presenter for the run list and per-run launch overrides (M28).
 *
 * A run is a single user→session exchange: user sends a [UserMessage], the
 * runtime creates a Run and returns a [RunResult.Accepted].
 */
class RunListPresenter(
    private val client: RuntimeClient,
    private val scope: CoroutineScope,
) {
    private val _launchConfig = MutableStateFlow(RunLaunchConfigState())
    val launchConfig: StateFlow<RunLaunchConfigState> = _launchConfig.asStateFlow()

    private val _runMetadata = MutableStateFlow<Map<String, RunDisplayMetadata>>(emptyMap())

    private val _state = MutableStateFlow<RunListUiState>(RunListUiState.Loading)
    val state: StateFlow<RunListUiState> = _state.asStateFlow()

    fun setGuardianPolicyOverride(policy: AgentTaintPolicy?) {
        _launchConfig.value = _launchConfig.value.copy(guardianPolicyOverride = policy)
        syncReady()
    }

    fun setRunModelOverride(modelId: String) {
        _launchConfig.value = _launchConfig.value.copy(runModelOverride = modelId.trim())
        syncReady()
    }

    fun setLocalityOverride(locality: AgentModelLocality?) {
        _launchConfig.value = _launchConfig.value.copy(localityOverride = locality)
        syncReady()
    }

    fun recordRunMetadata(metadata: RunDisplayMetadata) {
        _runMetadata.value = _runMetadata.value + (metadata.runId to metadata)
        syncReady()
    }

    fun buildUserMessage(
        content: String,
        attachments: List<String> = emptyList(),
        projectDefaults: ModelRoutingConfigState = ModelRoutingConfigState(),
        baseOptions: RunOptions = RunOptions(),
    ): UserMessage = UserMessage(
        content = content,
        attachments = attachments,
        runOptions = _launchConfig.value.toRunOptions(base = baseOptions, projectDefaults = projectDefaults),
    )

    fun sendMessage(
        sessionId: String,
        content: String,
        attachments: List<String> = emptyList(),
        projectDefaults: ModelRoutingConfigState = ModelRoutingConfigState(),
        baseOptions: RunOptions = RunOptions(),
    ) {
        if (content.isBlank()) return
        scope.launch {
            val message = buildUserMessage(
                content = content,
                attachments = attachments,
                projectDefaults = projectDefaults,
                baseOptions = baseOptions,
            )
            when (val result = client.sessions.send(sessionId, message)) {
                is RunResult.Accepted -> {
                    val config = _launchConfig.value
                    val chosenByMainModel = config.runModelOverride.isBlank() &&
                        projectDefaults.allowMainModelRunOverride &&
                        projectDefaults.mainModelId.isNotBlank() &&
                        message.runOptions.modelId != null &&
                        message.runOptions.modelId != projectDefaults.mainModelId
                    val metadata = RunDisplayMetadata(
                        runId = result.runId,
                        guardianPolicy = config.effectivePolicy(projectDefaults),
                        modelId = message.runOptions.modelId.orEmpty(),
                        modelChosenByMainModel = chosenByMainModel,
                        enforcedLocality = config.effectiveLocality(projectDefaults),
                    )
                    _runMetadata.value = _runMetadata.value + (result.runId to metadata)
                    load(sessionId)
                }
                is RunResult.Error -> {
                    val current = _state.value
                    if (current is RunListUiState.Ready && current.sessionId == sessionId) {
                        _state.value = current.copy(sendError = result.message)
                    } else {
                        _state.value = RunListUiState.Error(sessionId, result.message)
                    }
                }
            }
        }
    }

    fun load(sessionId: String) {
        scope.launch {
            _state.value = RunListUiState.Loading
            try {
                val detail: SessionDetail? = client.sessions.get(sessionId)
                if (detail == null) {
                    _state.value = RunListUiState.Error(sessionId, "Session not found")
                    return@launch
                }
                val runs = detail.recentRuns.sortedByDescending { it.startedAt }
                _state.value = RunListUiState.Ready(
                    sessionId = sessionId,
                    sessionName = detail.summary.name,
                    runs = runs,
                    launchConfig = _launchConfig.value,
                    runMetadata = _runMetadata.value,
                )
            } catch (e: Exception) {
                _state.value = RunListUiState.Error(sessionId, e.message ?: "Unknown error")
            }
        }
    }

    private fun syncReady() {
        val current = _state.value
        if (current is RunListUiState.Ready) {
            _state.value = current.copy(
                launchConfig = _launchConfig.value,
                runMetadata = _runMetadata.value,
            )
        }
    }
}
