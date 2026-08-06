package dev.aidos.androidapp.ui.runs

import dev.aidos.api.RuntimeClient
import dev.aidos.api.RunSummary
import dev.aidos.api.SessionDetail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
 * Platform-neutral presenter for the run list (M28).
 *
 * A run is a single user→session exchange: user sends a [dev.aidos.api.UserMessage], the
 * runtime creates a Run and returns a [dev.aidos.api.RunResult.Accepted]. This presenter
 * exposes the historical list. The event stream for a specific run is a separate screen.
 */
class RunListPresenter(
    private val client: RuntimeClient,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow<RunListUiState>(RunListUiState.Loading)
    val state: StateFlow<RunListUiState> = _state.asStateFlow()

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
                )
            } catch (e: Exception) {
                _state.value = RunListUiState.Error(sessionId, e.message ?: "Unknown error")
            }
        }
    }
}
