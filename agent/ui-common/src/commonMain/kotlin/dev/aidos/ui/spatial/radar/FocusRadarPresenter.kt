package dev.aidos.ui.spatial.radar

import dev.aidos.api.IntentCommands
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.ui.state.MviPresenter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RadarZone {
    STRIKE_ZONE,        // Active / Ready immediately (inner ring)
    INTERMEDIATE_ORBIT, // Blocked by single prereq or upcoming (middle ring)
    OUTER_PERIMETER,    // Distant goals / low priority (outer ring)
}

data class RadarNodeUiModel(
    val node: IntentNodeRecord,
    val status: DerivedStatus,
    val zone: RadarZone,
    val angleRad: Float,
)

data class FocusRadarUiState(
    val radarNodes: List<RadarNodeUiModel> = emptyList(),
    val activeZone: RadarZone? = null,
    val isLoading: Boolean = false,
)

sealed interface FocusRadarIntent {
    data object Refresh : FocusRadarIntent
    data class SelectZone(val zone: RadarZone?) : FocusRadarIntent
}

class FocusRadarPresenter(
    private val projectId: String,
    private val intentCommands: IntentCommands,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviPresenter<FocusRadarUiState, FocusRadarIntent> {

    private val _state = MutableStateFlow(FocusRadarUiState(isLoading = true))
    override val state: StateFlow<FocusRadarUiState> = _state.asStateFlow()

    init {
        loadRadar()
    }

    override fun send(intent: FocusRadarIntent) {
        when (intent) {
            is FocusRadarIntent.Refresh -> loadRadar()
            is FocusRadarIntent.SelectZone -> {
                _state.update { it.copy(activeZone = intent.zone) }
            }
        }
    }

    fun loadRadar() {
        scope.launch(ioDispatcher) {
            _state.update { it.copy(isLoading = true) }
            try {
                val nodes = intentCommands.listNodes(projectId)
                val radarItems = nodes.mapIndexed { index, node ->
                    val status = intentCommands.getNodeStatus(projectId, node.id)?.derivedStatus
                        ?: DerivedStatus.NOT_STARTED

                    val zone = when {
                        status == DerivedStatus.IN_PROGRESS || status == DerivedStatus.NEEDS_REVIEW -> RadarZone.STRIKE_ZONE
                        status == DerivedStatus.BLOCKED || status == DerivedStatus.NOT_STARTED -> RadarZone.INTERMEDIATE_ORBIT
                        else -> RadarZone.OUTER_PERIMETER
                    }

                    val angle = (2.0 * Math.PI * index / nodes.size.coerceAtLeast(1).toDouble()).toFloat()

                    RadarNodeUiModel(
                        node = node,
                        status = status,
                        zone = zone,
                        angleRad = angle,
                    )
                }

                _state.update {
                    it.copy(radarNodes = radarItems, isLoading = false)
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }
}
