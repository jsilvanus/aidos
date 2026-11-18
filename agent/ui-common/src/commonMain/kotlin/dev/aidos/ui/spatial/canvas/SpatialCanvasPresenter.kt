package dev.aidos.ui.spatial.canvas

import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SpatialCanvasUiState(
    val nodes: List<IntentNodeRecord> = emptyList(),
    val edges: List<IntentEdgeRecord> = emptyList(),
    val positions: Map<String, Pair<Float, Float>> = emptyMap(),
    val statuses: Map<String, IntentNodeStatusView> = emptyMap(),
    val viewport: ViewportTransform = ViewportTransform(),
    val lodLevel: LodLevel = LodLevel.MESO_CLUSTER,
    val isLoading: Boolean = false,
    val error: String? = null,
)

sealed interface SpatialCanvasIntent {
    data class Pan(val deltaX: Float, val deltaY: Float) : SpatialCanvasIntent
    data class Zoom(val zoomFactor: Float) : SpatialCanvasIntent
    data object ResetViewport : SpatialCanvasIntent
    data object Refresh : SpatialCanvasIntent
}

/**
 * RFC-0124 amendment (UI-20): graph data comes from the live [GraphSnapshot]; the viewport stays UI-only.
 */
class SpatialCanvasPresenter(
    snapshot: StateFlow<GraphSnapshot>,
    scope: CoroutineScope,
) : MviPresenter<SpatialCanvasUiState, SpatialCanvasIntent> {

    private val _state = MutableStateFlow(SpatialCanvasUiState(isLoading = true))
    override val state: StateFlow<SpatialCanvasUiState> = _state.asStateFlow()

    init {
        scope.launch {
            snapshot.collect { snap ->
                val positions = ForceDirectedLayout.layoutDag(snap.nodes, snap.edges)
                _state.update {
                    it.copy(
                        nodes = snap.nodes,
                        edges = snap.edges,
                        positions = positions,
                        statuses = snap.statuses,
                        isLoading = snap.loadedAt == 0L && snap.error == null,
                        error = snap.error,
                    )
                }
            }
        }
    }

    override fun send(intent: SpatialCanvasIntent) {
        when (intent) {
            is SpatialCanvasIntent.Pan -> {
                _state.update { current ->
                    val updatedVp = current.viewport.copy(
                        panX = current.viewport.panX + intent.deltaX,
                        panY = current.viewport.panY + intent.deltaY,
                    )
                    current.copy(viewport = updatedVp)
                }
            }
            is SpatialCanvasIntent.Zoom -> {
                _state.update { current ->
                    val newZoom = (current.viewport.zoom * intent.zoomFactor).coerceIn(0.05f, 2.0f)
                    val updatedVp = current.viewport.copy(zoom = newZoom)
                    current.copy(
                        viewport = updatedVp,
                        lodLevel = SemanticZoomManager.computeLod(newZoom),
                    )
                }
            }
            is SpatialCanvasIntent.ResetViewport -> {
                _state.update {
                    it.copy(
                        viewport = ViewportTransform(),
                        lodLevel = LodLevel.MESO_CLUSTER,
                    )
                }
            }
            is SpatialCanvasIntent.Refresh -> Unit // the ProjectGraphStore refreshes itself
        }
    }
}
