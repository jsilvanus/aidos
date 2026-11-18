package dev.aidos.ui.frontdoors.projects

import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.ProjectCommands
import dev.aidos.api.ProjectLocation
import dev.aidos.api.ProjectResult
import dev.aidos.api.ProjectSummary
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentLifecycle
import dev.aidos.ui.frontdoors.inbox.ActionInboxPresenter
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProjectItemUiModel(
    val summary: ProjectSummary,
    val domain: WorkspaceDomain,
)

/** The "New project" dialog's fields. [type] is one of "generic", "personal", "code". */
data class NewProjectDraft(
    val name: String = "",
    val description: String = "",
    val type: String = "generic",
)

data class ProjectHubUiState(
    val projects: List<ProjectItemUiModel> = emptyList(),
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
    /** Non-null while the New project dialog is open. */
    val newProject: NewProjectDraft? = null,
    val creating: Boolean = false,
    val createError: String? = null,
    /** Set when a project was created; the screen switches to it and then sends [ProjectHubIntent.ConsumeCreated]. */
    val createdProjectId: String? = null,
)

sealed interface ProjectHubIntent {
    data object Refresh : ProjectHubIntent
    data object OpenNewProject : ProjectHubIntent
    data object CloseNewProject : ProjectHubIntent
    data class EditNewProject(val draft: NewProjectDraft) : ProjectHubIntent
    data object SubmitNewProject : ProjectHubIntent
    data object ConsumeCreated : ProjectHubIntent
}

/**
 * Lists real projects and creates new ones (UI-34). Switching the current project is the screen's
 * job, through the same callback the app already uses, so this presenter does not touch the controller.
 */
class ProjectHubPresenter(
    private val projectCommands: ProjectCommands,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val refreshMillis: Long = ActionInboxPresenter.PROJECT_LIST_MILLIS,
) : MviPresenter<ProjectHubUiState, ProjectHubIntent> {

    private val _state = MutableStateFlow(ProjectHubUiState(isLoading = true))
    override val state: StateFlow<ProjectHubUiState> = _state.asStateFlow()

    init {
        loadProjects()
        scope.launch {
            while (true) {
                delay(refreshMillis)
                loadProjects()
            }
        }
    }

    override fun send(intent: ProjectHubIntent) {
        when (intent) {
            is ProjectHubIntent.Refresh -> loadProjects()
            is ProjectHubIntent.OpenNewProject -> _state.update {
                it.copy(newProject = NewProjectDraft(), createError = null)
            }
            is ProjectHubIntent.CloseNewProject -> _state.update {
                it.copy(newProject = null, createError = null, creating = false)
            }
            is ProjectHubIntent.EditNewProject -> _state.update {
                it.copy(newProject = intent.draft, createError = null)
            }
            is ProjectHubIntent.SubmitNewProject -> submitNewProject()
            is ProjectHubIntent.ConsumeCreated -> _state.update { it.copy(createdProjectId = null) }
        }
    }

    fun loadProjects() {
        scope.launch(ioDispatcher) {
            _state.update { it.copy(isLoading = true, errorMessage = null) }
            try {
                val items = projectCommands.list().map { summary ->
                    ProjectItemUiModel(summary = summary, domain = domainOf(summary.name))
                }
                _state.update { it.copy(projects = items, isLoading = false) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.update { it.copy(isLoading = false, errorMessage = t.message ?: "Failed to list projects") }
            }
        }
    }

    private fun submitNewProject() {
        val draft = _state.value.newProject ?: return
        if (_state.value.creating) return
        val name = draft.name.trim()
        if (name.isEmpty()) {
            _state.update { it.copy(createError = "Name is required") }
            return
        }
        _state.update { it.copy(creating = true, createError = null) }
        scope.launch(ioDispatcher) {
            try {
                val slug = ProjectSlugs.unique(
                    ProjectSlugs.from(name),
                    projectCommands.list().map { it.projectPath },
                )
                val request = CreateProjectRequest(
                    name = name,
                    description = draft.description.trim(),
                    location = ProjectLocation.RuntimeManaged(slug),
                    initGit = draft.type == "code",
                    projectType = draft.type,
                )
                when (val result = projectCommands.create(request)) {
                    is ProjectResult.Success -> {
                        _state.update {
                            it.copy(creating = false, newProject = null, createdProjectId = result.project.id)
                        }
                        loadProjects()
                    }
                    is ProjectResult.Error -> _state.update {
                        it.copy(creating = false, createError = result.message)
                    }
                }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.update { it.copy(creating = false, createError = t.message ?: "Could not create the project") }
            }
        }
    }

    companion object {
        /** Name-based domain guess, unchanged from before UI-34 (the summary carries no project type). */
        fun domainOf(name: String): WorkspaceDomain {
            val isLife = name.contains("personal", ignoreCase = true) || name.contains("life", ignoreCase = true)
            val isWork = name.contains("work", ignoreCase = true) ||
                    name.contains("enterprise", ignoreCase = true) ||
                    name.contains("commercial", ignoreCase = true)
            return when {
                isLife -> WorkspaceDomain.LIFE_AIR_GAPPED
                isWork -> WorkspaceDomain.WORK_ENTERPRISE
                else -> WorkspaceDomain.CODE_GIT_BACKED
            }
        }

        private val OPEN_STATUSES = setOf(
            DerivedStatus.NOT_STARTED,
            DerivedStatus.IN_PROGRESS,
            DerivedStatus.BLOCKED,
            DerivedStatus.NEEDS_REVIEW,
        )

        /** Open tasks: active nodes not yet done. Needs me: the same list the Action Inbox shows. */
        fun countsFor(snapshot: GraphSnapshot): ProjectCounts = ProjectCounts(
            openTasks = snapshot.statuses.values.count {
                it.node.lifecycle == IntentLifecycle.ACTIVE && it.derivedStatus in OPEN_STATUSES
            },
            needsMe = ActionInboxPresenter.actionableForHuman(snapshot).size,
        )
    }
}

data class ProjectCounts(val openTasks: Int, val needsMe: Int)
