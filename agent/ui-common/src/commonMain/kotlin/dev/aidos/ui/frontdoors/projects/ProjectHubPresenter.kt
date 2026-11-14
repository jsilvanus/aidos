package dev.aidos.ui.frontdoors.projects

import dev.aidos.api.ProjectCommands
import dev.aidos.api.ProjectSummary
import dev.aidos.ui.state.MviPresenter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectItemUiModel(
    val summary: ProjectSummary,
    val domain: WorkspaceDomain,
)

data class ProjectHubUiState(
    val projects: List<ProjectItemUiModel> = emptyList(),
    val activeProjectId: String? = null,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
)

sealed interface ProjectHubIntent {
    data object Refresh : ProjectHubIntent
    data class SelectProject(val projectId: String) : ProjectHubIntent
}

class ProjectHubPresenter(
    private val projectCommands: ProjectCommands,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviPresenter<ProjectHubUiState, ProjectHubIntent> {

    private val _state = MutableStateFlow(ProjectHubUiState(isLoading = true))
    override val state: StateFlow<ProjectHubUiState> = _state.asStateFlow()

    init {
        loadProjects()
    }

    override fun send(intent: ProjectHubIntent) {
        when (intent) {
            is ProjectHubIntent.Refresh -> loadProjects()
            is ProjectHubIntent.SelectProject -> {
                _state.update { it.copy(activeProjectId = intent.projectId) }
            }
        }
    }

    fun loadProjects() {
        scope.launch(ioDispatcher) {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val summaries = projectCommands.list()
                val items = summaries.map { summary ->
                    val isLife = summary.name.contains("personal", ignoreCase = true) ||
                            summary.name.contains("life", ignoreCase = true)
                    ProjectItemUiModel(
                        summary = summary,
                        domain = if (isLife) WorkspaceDomain.LIFE_AIR_GAPPED else WorkspaceDomain.CODE_GIT_BACKED,
                    )
                }

                _state.update {
                    it.copy(
                        projects = items,
                        activeProjectId = it.activeProjectId ?: items.firstOrNull()?.summary?.id,
                        isLoading = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isLoading = false, errorMessage = t.message ?: "Failed to list projects") }
            }
        }
    }
}
