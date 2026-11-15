package dev.aidos.ui.spatial.canvas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.spatial.edges.BezierCausalEdge
import dev.aidos.ui.spatial.edges.EdgeCollisionRouter
import androidx.compose.ui.geometry.Rect
import dev.aidos.ui.spatial.nodes.AtomicTaskCapsule
import dev.aidos.ui.spatial.nodes.GoalHexagonNode
import dev.aidos.ui.spatial.nodes.SatelliteCriterionNode
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.colorForActor
import dev.aidos.ui.theme.colorForStatus

@Composable
fun InfiniteZuiCanvas(
    presenter: SpatialCanvasPresenter,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()
    val viewport = state.viewport
    val lod = state.lodLevel

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (pan != Offset.Zero) {
                        presenter.send(SpatialCanvasIntent.Pan(pan.x / viewport.zoom, pan.y / viewport.zoom))
                    }
                    if (zoom != 1f) {
                        presenter.send(SpatialCanvasIntent.Zoom(zoom))
                    }
                }
            }
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val canvasW = size.width
            val canvasH = size.height

            // 1. Draw Causal Edges (Culled if sub-pixel)
            if (lod != LodLevel.MACRO_GALAXY) {
                state.edges.forEach { edge ->
                    val fromPos = state.positions[edge.fromNodeId]
                    val toPos = state.positions[edge.toNodeId]
                    if (fromPos != null && toPos != null) {
                        val screenFrom = viewport.toScreenCoordinates(fromPos.first, fromPos.second)
                        val screenTo = viewport.toScreenCoordinates(toPos.first, toPos.second)

                        // RFC-0106: anchor the edge on each node's boundary instead of its centre.
                        val r = 24f * viewport.zoom
                        val (anchorFrom, anchorTo) = EdgeCollisionRouter.routeAroundObstacles(
                            screenFrom,
                            screenTo,
                            listOf(
                                Rect(screenFrom.x - r, screenFrom.y - r, screenFrom.x + r, screenFrom.y + r),
                                Rect(screenTo.x - r, screenTo.y - r, screenTo.x + r, screenTo.y + r),
                            ),
                        )
                        BezierCausalEdge.drawBezierEdge(
                            drawScope = this,
                            start = anchorFrom,
                            end = anchorTo,
                            color = Color(0x6638BDF8),
                            strokeWidth = 2f * viewport.zoom,
                        )
                    }
                }
            }

            // 2. Draw Nodes with LOD Culling
            state.nodes.forEach { node ->
                val pos = state.positions[node.id] ?: return@forEach
                if (!viewport.isPointInViewport(pos.first, pos.second, canvasW, canvasH)) {
                    return@forEach // LOD Viewport Culling
                }

                val screenPos = viewport.toScreenCoordinates(pos.first, pos.second)
                val actorColor = colorForActor(node.actorKind)
                val status = state.statuses[node.id]?.derivedStatus ?: dev.aidos.intent.DerivedStatus.NOT_STARTED
                val statusColor = colorForStatus(status)

                when (lod) {
                    LodLevel.MACRO_GALAXY -> {
                        // Tiny dot or cluster representation
                        drawCircle(
                            color = actorColor,
                            radius = 4f,
                            center = screenPos,
                        )
                    }
                    LodLevel.MESO_CLUSTER -> {
                        when (node.type) {
                            IntentNodeType.GOAL -> {
                                GoalHexagonNode.drawHexagon(
                                    drawScope = this,
                                    center = screenPos,
                                    radius = 24f * viewport.zoom,
                                    fillColor = Color(0xDD0F172A),
                                    borderColor = statusColor,
                                )
                            }
                            IntentNodeType.ACCEPTANCE_CRITERION -> {
                                SatelliteCriterionNode.drawDiamond(
                                    drawScope = this,
                                    center = screenPos,
                                    size = 18f * viewport.zoom,
                                    fillColor = ColorTokens.ActorHybrid,
                                    borderColor = Color.White,
                                )
                            }
                            else -> {
                                AtomicTaskCapsule.drawCapsule(
                                    drawScope = this,
                                    center = screenPos,
                                    width = 60f * viewport.zoom,
                                    height = 20f * viewport.zoom,
                                    fillColor = Color(0xDD1E293B),
                                    borderColor = actorColor,
                                )
                            }
                        }
                    }
                    LodLevel.MICRO_BLUEPRINT -> {
                        // High-detail blueprint capsules
                        AtomicTaskCapsule.drawCapsule(
                            drawScope = this,
                            center = screenPos,
                            width = 120f * viewport.zoom,
                            height = 36f * viewport.zoom,
                            fillColor = Color(0xFF1E293B),
                            borderColor = statusColor,
                        )
                    }
                }
            }
        }
    }
}
