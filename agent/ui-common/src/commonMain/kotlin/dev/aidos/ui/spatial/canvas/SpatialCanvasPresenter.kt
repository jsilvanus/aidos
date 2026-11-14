package dev.aidos.ui.spatial.canvas

import dev.aidos.api.IntentCommands
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentEdgeRecord
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

data class SpatialCanvasUiState(
    val nodes: List<IntentNodeRecord> = emptyList(),
    val edges: List<IntentEdgeRecord> = emptyList(),
    val positions: Map<String, Pair<Float, Float>> = emptyMap(),
    val statuses: Map<String, IntentNodeStatusView> = emptyMap(),
    val viewport: ViewportTransform = ViewportTransform(),
    val lodLevel: LodLevel = LodLevel.MESO_CLUSTER,
    val isLoading: Boolean = false,
)

sealed interface SpatialCanvasIntent {
    data class Pan(val deltaX: Float, val deltaY: Float) : SpatialCanvasIntent
    data class Zoom(val zoomFactor: Float) : SpatialCanvasIntent
    data object ResetViewport : SpatialCanvasIntent
    data object Refresh : SpatialCanvasIntent
}

class SpatialCanvasPresenter(
    private val projectId: String,
    private val intentCommands: IntentCommands,
    private val scope: CoroutineScope,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : MviPresenter<SpatialCanvasUiState, SpatialCanvasIntent> {

    private val _state = MutableStateFlow(SpatialCanvasUiState(isLoading = true))
    override val state: StateFlow<SpatialCanvasUiState> = _state.asStateFlow()

    init {
        loadGraph()
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
            is SpatialCanvasIntent.Refresh -> loadGraph()
        }
    }

    fun loadGraph() {
        scope.launch(ioDispatcher) {
            _state.update { it.copy(isLoading = true) }
            try {
                val nodes = intentCommands.listNodes(projectId)
                val edges = intentCommands.listEdges(projectId)
                val positions = ForceDirectedLayout.layoutDag(nodes, edges)

                val statusMap = mutableMapOf<String, IntentNodeStatusView>()
                for (node in nodes) {
                    val status = intentCommands.getNodeStatus(projectId, node.id)
                    if (status != null) statusMap[node.id] = status
                }

                _state.update {
                    it.copy(
                        nodes = nodes,
                        edges = edges,
                        positions = positions,
                        statuses = statusMap,
                        isLoading = false,
                    )
                }
            } catch (t: Throwable) {
                _state.update { it.copy(isLoading = false) }
            }
        }
    }
}
