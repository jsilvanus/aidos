package dev.aidos.ui.spatial.radar

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
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
    val error: String? = null,
)

sealed interface FocusRadarIntent {
    data object Refresh : FocusRadarIntent
    data class SelectZone(val zone: RadarZone?) : FocusRadarIntent
}

/** RFC-0124 amendment (UI-20): radar nodes are derived from the live [GraphSnapshot]. */
class FocusRadarPresenter(
    snapshot: StateFlow<GraphSnapshot>,
    scope: CoroutineScope,
) : MviPresenter<FocusRadarUiState, FocusRadarIntent> {

    private val _state = MutableStateFlow(FocusRadarUiState(isLoading = true))
    override val state: StateFlow<FocusRadarUiState> = _state.asStateFlow()

    init {
        scope.launch {
            snapshot.collect { snap ->
                val radarItems = snap.nodes.mapIndexed { index, node ->
                    val status = snap.statuses[node.id]?.derivedStatus ?: DerivedStatus.NOT_STARTED

                    val zone = when {
                        status == DerivedStatus.IN_PROGRESS || status == DerivedStatus.NEEDS_REVIEW -> RadarZone.STRIKE_ZONE
                        status == DerivedStatus.BLOCKED || status == DerivedStatus.NOT_STARTED -> RadarZone.INTERMEDIATE_ORBIT
                        else -> RadarZone.OUTER_PERIMETER
                    }

                    val angle = (2.0 * Math.PI * index / snap.nodes.size.coerceAtLeast(1).toDouble()).toFloat()

                    RadarNodeUiModel(node = node, status = status, zone = zone, angleRad = angle)
                }
                _state.update {
                    it.copy(
                        radarNodes = radarItems,
                        isLoading = snap.loadedAt == 0L && snap.error == null,
                        error = snap.error,
                    )
                }
            }
        }
    }

    override fun send(intent: FocusRadarIntent) {
        when (intent) {
            is FocusRadarIntent.Refresh -> Unit // the ProjectGraphStore refreshes itself
            is FocusRadarIntent.SelectZone -> {
                _state.update { it.copy(activeZone = intent.zone) }
            }
        }
    }
}
