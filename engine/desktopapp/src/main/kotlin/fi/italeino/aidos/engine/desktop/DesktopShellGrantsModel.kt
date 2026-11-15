package fi.italeino.aidos.engine.desktop

import dev.aidos.api.CapabilityResult
import dev.aidos.api.GrantCapabilityRequest
import dev.aidos.api.ProjectResult
import dev.aidos.api.RuntimeClient
import dev.aidos.tcr.engine.ShellGrantUi
import dev.aidos.tcr.engine.ShellGrantsUiState
import dev.aidos.tcr.engine.ShellProjectUi
import dev.aidos.tcr.engine.ShellSessionUi
import dev.aidos.tcr.engine.parseShellScope
import dev.aidos.tcr.engine.shellScopeString
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.datetime.Clock
import kotlin.time.Duration.Companion.hours

/**
 * Presenter for the Shell grants tab (RFC-0033). [connectionProvider] is called on first use, so opening the
 * app never touches the project databases until the user opens this tab. It talks to the running Agent daemon
 * when there is one and to an in-process runtime otherwise (RFC-0115); the pane says which.
 */
class DesktopShellGrantsModel(
    private val scope: CoroutineScope,
    private val connectionProvider: () -> AgentConnection,
) {
    private val connection by lazy(connectionProvider)
    private val client: RuntimeClient get() = connection.client
    private val _state = MutableStateFlow(ShellGrantsUiState())
    val state: StateFlow<ShellGrantsUiState> = _state.asStateFlow()

    fun refresh() = run {
        val s = _state.value
        loadProjects(keepProject = s.selectedProjectId, keepSession = s.selectedSessionId)
    }

    private fun run(block: suspend () -> Unit) {
        scope.launch {
            try {
                block()
                _state.update { it.copy(error = null) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: e::class.simpleName) }
            }
        }
    }

    private suspend fun loadProjects(keepProject: String?, keepSession: String?) {
        _state.update { it.copy(runtimeNote = if (connection is AgentConnection.Attached) "Connected to the running Agent runtime." else "Agent runtime not running: using this app's own. Grants apply once the daemon next opens this project.") }
        val projects = client.projects.list().map { ShellProjectUi(it.id, it.name, it.projectPath) }
        _state.update { it.copy(projects = projects) }
        if (keepProject != null && projects.any { p -> p.id == keepProject }) loadSessions(keepProject, keepSession)
    }

    private suspend fun loadSessions(projectId: String, keepSession: String?) {
        // A project's database is only open once the project is; opening it takes no lock and runs nothing.
        val opened = client.projects.open(projectId)
        check(opened is ProjectResult.Success) { (opened as? ProjectResult.Error)?.message ?: "could not open the project" }
        val sessions = client.sessions.list(projectId).filter { it.state != dev.aidos.api.SessionState.ARCHIVED }.map { ShellSessionUi(it.id, it.name) }
        _state.update { it.copy(selectedProjectId = projectId, sessions = sessions, selectedSessionId = keepSession?.takeIf { s -> sessions.any { x -> x.id == s } }) }
        _state.value.selectedSessionId?.let { loadGrants(it) } ?: _state.update { it.copy(grants = emptyList()) }
    }

    private suspend fun loadGrants(sessionId: String) {
        val grants = client.capabilities.list(sessionId).filter { it.permission == "SHELL_EXEC" }.mapNotNull { c ->
            parseShellScope(c.scope)?.let { (dir, cmds) -> ShellGrantUi(c.id, dir, cmds, c.expiresAt?.toString()) }
        }
        _state.update { it.copy(selectedSessionId = sessionId, grants = grants) }
    }

    fun selectProject(projectId: String) = run { _state.update { it.copy(selectedSessionId = null) }; loadSessions(projectId, null) }

    fun selectSession(sessionId: String) = run { loadGrants(sessionId) }

    fun grant(workingDirectory: String, commands: List<String>?, lifetimeHours: Long?) = run {
        val sessionId = _state.value.selectedSessionId ?: return@run
        val result = client.capabilities.grant(
            GrantCapabilityRequest(
                sessionId = sessionId,
                permission = "SHELL_EXEC",
                scope = shellScopeString(workingDirectory, commands),
                expiresAt = lifetimeHours?.let { Clock.System.now() + it.hours },
            ),
        )
        if (result is CapabilityResult.Error) error(result.message)
        loadGrants(sessionId)
    }

    fun revoke(capabilityId: String) = run {
        val sessionId = _state.value.selectedSessionId ?: return@run
        client.capabilities.revoke(capabilityId)
        loadGrants(sessionId)
    }
}
