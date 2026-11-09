package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aidos.tcr.agent.AgentRuntimeOverviewStats
import fi.italeino.aidos.engine.EngineService
import fi.italeino.aidos.engine.EngineState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Android ViewModel managing Agent Runtime Overview & Telemetry state for [AgentsPane].
 */
class AgentsViewModel : ViewModel() {
    private val _state = MutableStateFlow(AgentOverviewUiState())
    val state: StateFlow<AgentOverviewUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            EngineService.state.collect { engineState ->
                _state.value = _state.value.copy(
                    isEngineRunning = engineState == EngineState.READY
                )
            }
        }
    }

    fun selectAppFilter(app: String?) {
        _state.value = _state.value.copy(selectedAppFilter = app)
    }

    fun selectStatusFilter(status: AgentFilterTab) {
        _state.value = _state.value.copy(selectedStatusFilter = status)
    }

    fun selectViewLayout(layout: AgentViewLayout) {
        _state.value = _state.value.copy(selectedViewLayout = layout)
    }

    fun selectRunDetail(runId: String?) {
        _state.value = _state.value.copy(selectedRunId = runId)
    }

    fun cancelRun(runId: String) {
        // Future Binder / service call to EngineService for cancellation
    }
}
