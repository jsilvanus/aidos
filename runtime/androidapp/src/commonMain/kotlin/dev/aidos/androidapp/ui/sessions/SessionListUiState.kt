package dev.aidos.androidapp.ui.sessions

import dev.aidos.api.RuntimeClient
import dev.aidos.api.SessionSummary
import dev.aidos.api.SessionState
import dev.aidos.api.CreateSessionRequest
import dev.aidos.api.SessionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for the session list screen (M28, RFC-0050, RFC-0052).
 *
 * Sessions are scoped to one project. The list is sorted by [SessionSummary.lastActiveAt]
 * descending — most recently active first. Archived sessions are hidden by default.
 */
sealed interface SessionListUiState {
    data object Loading : SessionListUiState

    data class Ready(
        val projectId: String,
        val sessions: List<SessionSummary>,
        val showArchived: Boolean = false,
    ) : SessionListUiState {
        val visibleSessions: List<SessionSummary>
            get() = if (showArchived) sessions
                    else sessions.filter { it.state != SessionState.ARCHIVED }
    }

    data class Error(
        val projectId: String,
        val message: String,
    ) : SessionListUiState
}

/**
 * Platform-neutral presenter for the session list (M28).
 *
 * Bound to a single project. Sessions are sorted newest-first for comfortable one-handed
 * access on a phone — the most active sessions are reachable without scrolling.
 */
class SessionListPresenter(
    private val client: RuntimeClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<SessionListUiState>(SessionListUiState.Loading)
    val state: StateFlow<SessionListUiState> = _state.asStateFlow()

    fun load(projectId: String) {
        scope.launch {
            _state.value = SessionListUiState.Loading
            try {
                val sessions = client.sessions.list(projectId)
                    .sortedByDescending { it.lastActiveAt }
                _state.value = SessionListUiState.Ready(projectId = projectId, sessions = sessions)
            } catch (e: Exception) {
                _state.value = SessionListUiState.Error(
                    projectId = projectId,
                    message = e.message ?: "Unknown error",
                )
            }
        }
    }

    fun toggleArchived() {
        val current = _state.value
        if (current is SessionListUiState.Ready) {
            _state.value = current.copy(showArchived = !current.showArchived)
        }
    }

    fun createSession(projectId: String, name: String) {
        scope.launch {
            val request = CreateSessionRequest(projectId = projectId, name = name)
            when (val result = client.sessions.create(request)) {
                is SessionResult.Success -> load(projectId)
                is SessionResult.Error -> _state.value = SessionListUiState.Error(
                    projectId = projectId,
                    message = result.message,
                )
            }
        }
    }
}
