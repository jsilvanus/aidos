package dev.aidos.androidapp.ui.projects

import dev.aidos.api.ProjectSummary
import dev.aidos.api.RuntimeClient
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the project list screen (M28, RFC-0050, RFC-0052).
 *
 * Three states: loading while the list is being fetched, ready with the list, or an error
 * the user can dismiss and retry. [selectedProjectId] is null until the user taps a project.
 *
 * State is flat and derived from the [RuntimeClient] — the presenter computes this; nothing
 * is stored on-device beyond what the runtime already tracks.
 */
sealed interface ProjectsUiState {
    data object Loading : ProjectsUiState

    data class Ready(
        val projects: List<ProjectSummary>,
        val selectedProjectId: String? = null,
    ) : ProjectsUiState {
        val isEmpty: Boolean get() = projects.isEmpty()
    }

    data class Error(
        val message: String,
        val retryable: Boolean = true,
    ) : ProjectsUiState
}

/**
 * Platform-neutral presenter for the project list (M28).
 *
 * Compose on Android binds [state] via `collectAsState()`; the same class is used in
 * JVM tests via [kotlinx.coroutines.test.runTest] and [kotlinx.coroutines.flow.first].
 */
class ProjectsPresenter(
    private val client: RuntimeClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<ProjectsUiState>(ProjectsUiState.Loading)
    val state: StateFlow<ProjectsUiState> = _state.asStateFlow()

    fun loadProjects() {
        scope.launch {
            _state.value = ProjectsUiState.Loading
            try {
                val projects = client.projects.list()
                _state.value = ProjectsUiState.Ready(projects)
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
}
