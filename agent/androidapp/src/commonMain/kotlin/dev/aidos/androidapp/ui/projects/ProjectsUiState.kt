package dev.aidos.androidapp.ui.projects

import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.ProjectSummary
import dev.aidos.api.RuntimeClient
import dev.aidos.settings.ModelLocality
import dev.aidos.settings.SettingDescriptor
import dev.aidos.settings.SettingSetByKind
import dev.aidos.settings.Settings
import dev.aidos.settings.SettingsStore
import dev.aidos.settings.SettingsWriter
import dev.aidos.settings.TaintApprovalPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock

/**
 * Pure-Kotlin taint approval policy aligned with [TaintApprovalPolicy] and `aidos-tcr`.
 */
enum class AgentTaintPolicy {
    MANUAL,
    GUARDIAN;

    fun toSettingsPolicy(): TaintApprovalPolicy = when (this) {
        MANUAL -> TaintApprovalPolicy.MANUAL
        GUARDIAN -> TaintApprovalPolicy.GUARDIAN
    }

    companion object {
        fun fromSettingsPolicy(policy: TaintApprovalPolicy): AgentTaintPolicy = when (policy) {
            TaintApprovalPolicy.MANUAL -> MANUAL
            TaintApprovalPolicy.GUARDIAN -> GUARDIAN
        }

        fun fromWire(raw: String?): AgentTaintPolicy =
            if (raw?.trim().equals("GUARDIAN", ignoreCase = true)) GUARDIAN else MANUAL
    }
}

/**
 * Pure-Kotlin inference locality tier mapping between `aidos-tcr` wire strings
 * (`local_only` / `trusted_cluster` / `any`) and `agent:settings` / [dev.aidos.api.RunOptions]
 * wire strings (`local` / `cluster` / `remote`).
 */
enum class AgentModelLocality(
    val wire: String,
    val settingsWire: String,
) {
    LOCAL_ONLY("local_only", "local"),
    TRUSTED_CLUSTER("trusted_cluster", "cluster"),
    ANY("any", "remote");

    fun toSettingsLocality(): ModelLocality = when (this) {
        LOCAL_ONLY -> ModelLocality.LOCAL
        TRUSTED_CLUSTER -> ModelLocality.CLUSTER
        ANY -> ModelLocality.REMOTE
    }

    companion object {
        fun fromSettingsLocality(locality: ModelLocality): AgentModelLocality = when (locality) {
            ModelLocality.LOCAL -> LOCAL_ONLY
            ModelLocality.CLUSTER -> TRUSTED_CLUSTER
            ModelLocality.REMOTE -> ANY
        }

        fun fromWire(raw: String?): AgentModelLocality = when (raw?.trim()?.lowercase()) {
            "local_only", "local" -> LOCAL_ONLY
            "trusted_cluster", "cluster" -> TRUSTED_CLUSTER
            else -> ANY
        }
    }
}

/**
 * Pure-Kotlin Guardian configuration state (Gate A Decision Model + Gate B Guardian LLM).
 */
data class GuardianConfigState(
    val policy: AgentTaintPolicy = AgentTaintPolicy.MANUAL,
    val decisionModelId: String = "laya-multilingual",
    val guardianLlmModelId: String = "",
)

/**
 * Pure-Kotlin model routing + Guardian configuration for global or per-project defaults.
 */
data class ModelRoutingConfigState(
    val mainModelId: String = "",
    val defaultRunModelId: String = "",
    val allowMainModelRunOverride: Boolean = true,
    val enforcedLocality: AgentModelLocality = AgentModelLocality.ANY,
    val guardianConfig: GuardianConfigState = GuardianConfigState(),
)

/**
 * UI state for the project list screen (M28, RFC-0050, RFC-0052).
 *
 * Three states: loading while the list is being fetched, ready with the list, or an error
 * the user can dismiss and retry. [selectedProjectId] is null until the user taps a project.
 */
sealed interface ProjectsUiState {
    data object Loading : ProjectsUiState

    data class Ready(
        val projects: List<ProjectSummary>,
        val selectedProjectId: String? = null,
        val globalConfig: ModelRoutingConfigState = ModelRoutingConfigState(),
        val projectConfigs: Map<String, ModelRoutingConfigState> = emptyMap(),
        val availableLlmModels: List<String> = emptyList(),
        val availableDecisionModels: List<String> = listOf("laya-multilingual"),
    ) : ProjectsUiState {
        val isEmpty: Boolean get() = projects.isEmpty()

        fun configForProject(projectId: String): ModelRoutingConfigState =
            projectConfigs[projectId] ?: globalConfig
    }

    data class Error(
        val message: String,
        val retryable: Boolean = true,
    ) : ProjectsUiState
}

/**
 * Platform-neutral presenter for the project list, global defaults, and per-project routing/Guardian
 * configuration (M28).
 *
 * Compose on Android binds [state] via `collectAsState()`; the same class is used in
 * JVM tests via [kotlinx.coroutines.test.runTest] and [kotlinx.coroutines.flow.first].
 */
class ProjectsPresenter(
    private val client: RuntimeClient,
    private val scope: CoroutineScope,
    private val settingsStore: SettingsStore? = null,
    private val settingsWriter: SettingsWriter? = null,
    private val nowIso: () -> String = { Clock.System.now().toString() },
) {
    private val _globalConfig = MutableStateFlow(loadInitialGlobalConfig())
    val globalConfig: StateFlow<ModelRoutingConfigState> = _globalConfig.asStateFlow()

    private val _projectConfigs = MutableStateFlow<Map<String, ModelRoutingConfigState>>(emptyMap())
    val projectConfigs: StateFlow<Map<String, ModelRoutingConfigState>> = _projectConfigs.asStateFlow()

    private var availableLlmModels: List<String> = emptyList()
    private var availableDecisionModels: List<String> = listOf("laya-multilingual")

    private val _state = MutableStateFlow<ProjectsUiState>(ProjectsUiState.Loading)
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    private fun loadInitialGlobalConfig(): ModelRoutingConfigState {
        val store = settingsStore ?: return ModelRoutingConfigState()
        return ModelRoutingConfigState(
            mainModelId = store.resolve(Settings.modelDefaultLlm).value,
            defaultRunModelId = store.resolve(Settings.modelDefaultRunLlm).value,
            allowMainModelRunOverride = true,
            enforcedLocality = AgentModelLocality.fromSettingsLocality(
                store.resolve(Settings.routingModelLocality).value
            ),
            guardianConfig = GuardianConfigState(
                policy = AgentTaintPolicy.fromSettingsPolicy(
                    store.resolve(Settings.trustTaintApprovalPolicy).value
                ),
                decisionModelId = store.resolve(Settings.trustGuardianDecisionModel).value,
                guardianLlmModelId = store.resolve(Settings.trustGuardianLlmModel).value,
            ),
        )
    }

    private fun loadStoredProjectConfig(projectId: String): ModelRoutingConfigState {
        val store = settingsStore ?: return _globalConfig.value
        return ModelRoutingConfigState(
            mainModelId = store.resolve(Settings.modelDefaultLlm, projectId = projectId).value,
            defaultRunModelId = store.resolve(Settings.modelDefaultRunLlm, projectId = projectId).value,
            allowMainModelRunOverride = true,
            enforcedLocality = AgentModelLocality.fromSettingsLocality(
                store.resolve(Settings.routingModelLocality, projectId = projectId).value
            ),
            guardianConfig = GuardianConfigState(
                policy = AgentTaintPolicy.fromSettingsPolicy(
                    store.resolve(Settings.trustTaintApprovalPolicy, projectId = projectId).value
                ),
                decisionModelId = store.resolve(Settings.trustGuardianDecisionModel, projectId = projectId).value,
                guardianLlmModelId = store.resolve(Settings.trustGuardianLlmModel, projectId = projectId).value,
            ),
        )
    }

    fun currentConfigForProject(projectId: String? = null): ModelRoutingConfigState =
        if (projectId == null) _globalConfig.value
        else _projectConfigs.value[projectId] ?: _globalConfig.value

    fun loadProjects() {
        val selectedBefore = (_state.value as? ProjectsUiState.Ready)?.selectedProjectId
        scope.launch {
            _state.value = ProjectsUiState.Loading
            try {
                val projects = client.projects.list()
                if (settingsStore != null) {
                    val hydrated = projects.associate { p ->
                        p.id to (_projectConfigs.value[p.id] ?: loadStoredProjectConfig(p.id))
                    }
                    _projectConfigs.value = hydrated + _projectConfigs.value
                }
                _state.value = ProjectsUiState.Ready(
                    projects = projects,
                    selectedProjectId = selectedBefore,
                    globalConfig = _globalConfig.value,
                    projectConfigs = _projectConfigs.value,
                    availableLlmModels = availableLlmModels,
                    availableDecisionModels = availableDecisionModels,
                )
            } catch (e: Exception) {
                _state.value = ProjectsUiState.Error(e.message ?: "Unknown error")
            }
        }
    }

    fun selectProject(projectId: String) {
        val current = _state.value
        if (current is ProjectsUiState.Ready) {
            _state.value = current.copy(selectedProjectId = projectId)
        }
    }

    fun clearSelection() {
        val current = _state.value
        if (current is ProjectsUiState.Ready) {
            _state.value = current.copy(selectedProjectId = null)
        }
    }

    fun createProject(name: String, description: String, slug: String) {
        scope.launch {
            val request = CreateProjectRequest(
                name = name,
                description = description,
                location = ProjectLocation.RuntimeManaged(slug),
            )
            when (val result = client.projects.create(request)) {
                is ProjectResult.Success -> loadProjects()
                is ProjectResult.Error -> _state.value = ProjectsUiState.Error(result.message)
            }
        }
    }

    fun setAvailableModels(
        llmModels: List<String>,
        decisionModels: List<String> = listOf("laya-multilingual"),
    ) {
        availableLlmModels = llmModels.distinct()
        availableDecisionModels = (listOf("laya-multilingual") + decisionModels).distinct()
        syncReadyState()
    }

    fun updateGlobalMainModel(modelId: String) {
        val clean = modelId.trim()
        updateGlobal { it.copy(mainModelId = clean) }
        persistUser(Settings.modelDefaultLlm, clean)
    }

    fun updateGlobalDefaultRunModel(modelId: String) {
        val clean = modelId.trim()
        updateGlobal { it.copy(defaultRunModelId = clean) }
        persistUser(Settings.modelDefaultRunLlm, clean)
    }

    fun updateGlobalLocality(locality: AgentModelLocality) {
        updateGlobal { it.copy(enforcedLocality = locality) }
        persistUser(Settings.routingModelLocality, locality.toSettingsLocality())
    }

    fun updateGlobalGuardianPolicy(policy: AgentTaintPolicy) {
        updateGlobal { it.copy(guardianConfig = it.guardianConfig.copy(policy = policy)) }
        persistUser(Settings.trustTaintApprovalPolicy, policy.toSettingsPolicy())
    }

    fun updateGlobalDecisionModel(modelId: String) {
        val clean = modelId.trim().ifEmpty { "laya-multilingual" }
        updateGlobal { it.copy(guardianConfig = it.guardianConfig.copy(decisionModelId = clean)) }
        persistUser(Settings.trustGuardianDecisionModel, clean)
    }

    fun updateGlobalGuardianLlm(modelId: String) {
        val clean = modelId.trim()
        updateGlobal { it.copy(guardianConfig = it.guardianConfig.copy(guardianLlmModelId = clean)) }
        persistUser(Settings.trustGuardianLlmModel, clean)
    }

    fun updateProjectMainModel(projectId: String, modelId: String) {
        val clean = modelId.trim()
        updateProject(projectId) { it.copy(mainModelId = clean) }
        persistProject(projectId, Settings.modelDefaultLlm, clean)
    }

    fun updateProjectDefaultRunModel(projectId: String, modelId: String) {
        val clean = modelId.trim()
        updateProject(projectId) { it.copy(defaultRunModelId = clean) }
        persistProject(projectId, Settings.modelDefaultRunLlm, clean)
    }

    fun updateProjectAllowMainModelOverride(projectId: String, allow: Boolean) {
        updateProject(projectId) { it.copy(allowMainModelRunOverride = allow) }
    }

    fun updateProjectLocality(projectId: String, locality: AgentModelLocality) {
        updateProject(projectId) { it.copy(enforcedLocality = locality) }
        persistProject(projectId, Settings.routingModelLocality, locality.toSettingsLocality())
    }

    fun updateProjectGuardianPolicy(projectId: String, policy: AgentTaintPolicy) {
        updateProject(projectId) { it.copy(guardianConfig = it.guardianConfig.copy(policy = policy)) }
        persistProject(projectId, Settings.trustTaintApprovalPolicy, policy.toSettingsPolicy())
    }

    fun updateProjectDecisionModel(projectId: String, modelId: String) {
        val clean = modelId.trim().ifEmpty { "laya-multilingual" }
        updateProject(projectId) { it.copy(guardianConfig = it.guardianConfig.copy(decisionModelId = clean)) }
        persistProject(projectId, Settings.trustGuardianDecisionModel, clean)
    }

    fun updateProjectGuardianLlm(projectId: String, modelId: String) {
        val clean = modelId.trim()
        updateProject(projectId) { it.copy(guardianConfig = it.guardianConfig.copy(guardianLlmModelId = clean)) }
        persistProject(projectId, Settings.trustGuardianLlmModel, clean)
    }

    private fun updateGlobal(transform: (ModelRoutingConfigState) -> ModelRoutingConfigState) {
        _globalConfig.value = transform(_globalConfig.value)
        syncReadyState()
    }

    private fun updateProject(
        projectId: String,
        transform: (ModelRoutingConfigState) -> ModelRoutingConfigState,
    ) {
        val current = _projectConfigs.value[projectId] ?: _globalConfig.value
        _projectConfigs.value = _projectConfigs.value + (projectId to transform(current))
        syncReadyState()
    }

    private fun syncReadyState() {
        val current = _state.value
        if (current is ProjectsUiState.Ready) {
            _state.value = current.copy(
                globalConfig = _globalConfig.value,
                projectConfigs = _projectConfigs.value,
                availableLlmModels = availableLlmModels,
                availableDecisionModels = availableDecisionModels,
            )
        }
    }

    private fun <T> persistUser(descriptor: SettingDescriptor<T>, value: T) {
        val writer = settingsWriter ?: return
        writer.writeUser(descriptor, descriptor.codec.encode(value), SettingSetByKind.USER, nowIso())
    }

    private fun <T> persistProject(projectId: String, descriptor: SettingDescriptor<T>, value: T) {
        val writer = settingsWriter ?: return
        writer.writeProject(descriptor, projectId, descriptor.codec.encode(value), SettingSetByKind.USER, nowIso())
    }
}
