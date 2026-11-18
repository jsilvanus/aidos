package dev.aidos.ui.spatial.canvas

import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class SpatialCanvasUiState(
    val nodes: List<IntentNodeRecord> = emptyList(),
    val edges: List<IntentEdgeRecord> = emptyList(),
    val positions: Map<String, Pair<Float, Float>> = emptyMap(),
    val statuses: Map<String, IntentNodeStatusView> = emptyMap(),
    val viewport: ViewportTransform = ViewportTransform(),
    val lodLevel: LodLevel = LodLevel.MESO_CLUSTER,
    val isLoading: Boolean = false,
    val error: String? = null,
    /** World point of a long press, waiting for the next GOAL the view creates (UI-35). Presenter state only. */
    val pendingGoalAt: Pair<Float, Float>? = null,
)

sealed interface SpatialCanvasIntent {
    data class Pan(val deltaX: Float, val deltaY: Float) : SpatialCanvasIntent
    data class Zoom(val zoomFactor: Float) : SpatialCanvasIntent
    /** Zoom that keeps the world point under the screen point ([screenX], [screenY]) where it is. */
    data class ZoomAt(val zoomFactor: Float, val screenX: Float, val screenY: Float) : SpatialCanvasIntent
    data class SetViewport(val viewport: ViewportTransform) : SpatialCanvasIntent
    data class PlaceNextGoalAt(val worldX: Float, val worldY: Float) : SpatialCanvasIntent
    data object ResetViewport : SpatialCanvasIntent
    data object Refresh : SpatialCanvasIntent
}

/**
 * RFC-0124 amendment (UI-20): graph data comes from the live [GraphSnapshot]; the viewport stays UI-only.
 * UI-35: positions are seeded from the previous snapshot, so the layout does not jump on each update.
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
                val before = _state.value
                // Small graphs lay out inline (cheap, and it keeps first paint synchronous); larger ones
                // run off the main thread. Only immutable inputs cross into the background.
                val laidOut = if (snap.nodes.size <= INLINE_LAYOUT_MAX) {
                    ForceDirectedLayout.layoutSeeded(snap.nodes, snap.edges, before.positions)
                } else {
                    withContext(Dispatchers.Default) {
                        ForceDirectedLayout.layoutSeeded(snap.nodes, snap.edges, before.positions)
                    }
                }
                val knownIds = before.nodes.mapTo(HashSet()) { it.id }
                val pending = before.pendingGoalAt
                val placedGoal = pending?.let { _ ->
                    snap.nodes.firstOrNull { it.type == IntentNodeType.GOAL && it.id !in knownIds }
                }
                val positions = if (placedGoal != null && pending != null) {
                    laidOut + (placedGoal.id to pending)
                } else {
                    laidOut
                }
                _state.update {
                    it.copy(
                        nodes = snap.nodes,
                        edges = snap.edges,
                        positions = positions,
                        statuses = snap.statuses,
                        isLoading = snap.loadedAt == 0L && snap.error == null,
                        error = snap.error,
                        pendingGoalAt = if (placedGoal != null) null else it.pendingGoalAt,
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
            is SpatialCanvasIntent.ZoomAt -> {
                _state.update { current ->
                    val vp = current.viewport
                    val newZoom = (vp.zoom * intent.zoomFactor).coerceIn(0.05f, 2.0f)
                    val world = vp.toWorldCoordinates(intent.screenX, intent.screenY)
                    val updatedVp = ViewportTransform(
                        panX = intent.screenX / newZoom - world.x,
                        panY = intent.screenY / newZoom - world.y,
                        zoom = newZoom,
                    )
                    current.copy(viewport = updatedVp, lodLevel = SemanticZoomManager.computeLod(newZoom))
                }
            }
            is SpatialCanvasIntent.SetViewport -> {
                _state.update {
                    it.copy(
                        viewport = intent.viewport,
                        lodLevel = SemanticZoomManager.computeLod(intent.viewport.zoom),
                    )
                }
            }
            is SpatialCanvasIntent.PlaceNextGoalAt -> {
                _state.update { it.copy(pendingGoalAt = intent.worldX to intent.worldY) }
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

    private companion object {
        const val INLINE_LAYOUT_MAX = 200
    }
}
