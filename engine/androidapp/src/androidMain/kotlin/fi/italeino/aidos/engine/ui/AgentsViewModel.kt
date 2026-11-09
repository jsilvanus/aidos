package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aidos.tcr.agent.AgentPlacement
import dev.aidos.tcr.agent.AgentRunStatus
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
    private val _state = MutableStateFlow(
        AgentOverviewUiState(
            stats = SampleAgentRuns.defaultStats(),
            runs = SampleAgentRuns.create(),
            isEngineRunning = EngineService.state.value == EngineState.READY
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
        _state.value = _state.value.copy(
            runs = _state.value.runs.map {
                if (it.runId == runId) it.copy(status = AgentRunStatus.CANCELLED) else it
            }
        )
    }
}
