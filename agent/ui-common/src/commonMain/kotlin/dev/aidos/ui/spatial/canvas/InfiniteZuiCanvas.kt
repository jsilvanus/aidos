package dev.aidos.ui.spatial.canvas

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.spatial.edges.BezierCausalEdge
import dev.aidos.ui.spatial.edges.EdgeCollisionRouter
import dev.aidos.ui.spatial.nodes.AtomicTaskCapsule
import dev.aidos.ui.spatial.nodes.ConstraintShieldNode
import dev.aidos.ui.spatial.nodes.GoalHexagonNode
import dev.aidos.ui.spatial.nodes.GroupNebulaRenderer
import dev.aidos.ui.spatial.nodes.SatelliteCriterionNode
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.colorForActor
import dev.aidos.ui.theme.colorForStatus
import dev.aidos.ui.workspace.WorkspaceController

private val NodeFill = Color(0xDD0F172A)
private val LabelColor = Color.White
private val MetaColor = Color(0xFF94A3B8)

/**
 * RFC-0111 Design 7 canvas on live data (UI-35). Semantic zoom picks what is drawn, goals show progress
 * arcs, tap opens the Inspector, long press on empty space makes a new goal, and active runs pulse.
 */
@Composable
fun InfiniteZuiCanvas(
    presenter: SpatialCanvasPresenter,
    controller: WorkspaceController,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()
    val latest by rememberUpdatedState(state)
    val workspace by controller.state.collectAsState()
    val textMeasurer = rememberTextMeasurer()
    val pulse by rememberInfiniteTransition(label = "canvas-pulse").animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(animation = tween(durationMillis = 900), repeatMode = RepeatMode.Reverse),
        label = "pulse",
    )

    val compact = formFactor == FormFactor.HANDHELD_COMPACT
    var canvasSize by remember { mutableStateOf(IntSize.Zero) }
    var opened by remember { mutableStateOf(false) }
    val focusId = remember(state.nodes, state.statuses, workspace.selectedNodeId) {
        CanvasFocus.initialFocus(state.nodes, state.statuses, workspace.selectedNodeId)
    }
    val focusPos = focusId?.let { state.positions[it] }
    // Phone: open once, centred on the focus node at zoom 0.8 (UI-35).
    LaunchedEffect(compact, focusId, focusPos, canvasSize) {
        if (compact && !opened && focusPos != null && canvasSize.width > 0) {
            presenter.send(
                SpatialCanvasIntent.SetViewport(
                    CanvasFocus.centredViewport(
                        focusPos.first,
                        focusPos.second,
                        canvasSize.width.toFloat(),
                        canvasSize.height.toFloat(),
                    ),
                ),
            )
            opened = true
        }
    }

    val tier = CanvasDetail.tierFor(state.viewport.zoom)
    val drawn = remember(state.nodes, tier) { state.nodes.filter { CanvasDetail.isDrawn(tier, it.type) } }
    val drawnIds = remember(drawn) { drawn.mapTo(HashSet()) { it.id } }
    val conflictedIds = remember(state.statuses) {
        state.statuses.filterValues { it.derivedStatus == DerivedStatus.CONFLICTED }.keys
    }
    val progress = remember(state.nodes, state.statuses) { CanvasDetail.progressByNode(state.nodes, state.statuses) }
    val crumbs = remember(state.nodes, focusId) { CanvasFocus.breadcrumb(focusId, state.nodes) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { canvasSize = it }
            .pointerInput(Unit) {
                detectTransformGestures { centroid, pan, zoom, _ ->
                    val vp = latest.viewport
                    if (pan != Offset.Zero) {
                        presenter.send(SpatialCanvasIntent.Pan(pan.x / vp.zoom, pan.y / vp.zoom))
                    }
                    if (zoom != 1f) {
                        presenter.send(SpatialCanvasIntent.ZoomAt(zoom, centroid.x, centroid.y))
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { point ->
                        val id = CanvasHitTest.nodeAt(point, latest.viewport, drawnPositions(latest))
                        if (id != null) controller.openInspector(id)
                    },
                    onLongPress = { point ->
                        val s = latest
                        if (CanvasHitTest.nodeAt(point, s.viewport, drawnPositions(s)) == null) {
                            val world = s.viewport.toWorldCoordinates(point.x, point.y)
                            presenter.send(SpatialCanvasIntent.PlaceNextGoalAt(world.x, world.y))
                            controller.createNode(
                                CreateIntentNodeRequest(projectId = "", title = "New goal", type = IntentNodeType.GOAL),
                            )
                        }
                    },
                )
            }
            .pointerInput(Unit) {
                // Ctrl + wheel zooms around the cursor (UI-35).
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent()
                        if (event.type == PointerEventType.Scroll && event.keyboardModifiers.isCtrlPressed) {
                            val change = event.changes.firstOrNull() ?: continue
                            val dy = change.scrollDelta.y
                            if (dy != 0f) {
                                val factor = if (dy < 0f) 1.1f else 1f / 1.1f
                                presenter.send(SpatialCanvasIntent.ZoomAt(factor, change.position.x, change.position.y))
                            }
                        }
                    }
                }
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val vp = state.viewport
            val zoom = vp.zoom

            // 1. Edges between drawn nodes only; none at the groups-only tier.
            if (tier != CanvasDetail.Tier.GROUPS_ONLY) {
                state.edges.forEach { edge ->
                    val (fromId, toId) = CanvasEdges.drawnEndpoints(edge)
                    if (fromId !in drawnIds || toId !in drawnIds) return@forEach
                    val a = state.positions[fromId] ?: return@forEach
                    val b = state.positions[toId] ?: return@forEach
                    val screenA = vp.toScreenCoordinates(a.first, a.second)
                    val screenB = vp.toScreenCoordinates(b.first, b.second)
                    // RFC-0106: edges meet each node at its boundary, not its centre.
                    val r = 24f * zoom
                    val (start, end) = EdgeCollisionRouter.routeAroundObstacles(
                        screenA,
                        screenB,
                        listOf(
                            Rect(screenA.x - r, screenA.y - r, screenA.x + r, screenA.y + r),
                            Rect(screenB.x - r, screenB.y - r, screenB.x + r, screenB.y + r),
                        ),
                    )
                    val conflicted = edge.fromNodeId in conflictedIds || edge.toNodeId in conflictedIds
                    val style = CanvasEdges.style(edge.edgeKind, conflicted)
                    BezierCausalEdge.drawBezierEdge(
                        drawScope = this,
                        start = start,
                        end = end,
                        color = style.color,
                        strokeWidth = style.strokeWidth,
                        dashed = style.dashed,
                        arrowAtEnd = style.arrowAtEnd,
                    )
                }
            }

            // 2. Nodes allowed at this zoom tier and inside the view.
            drawn.forEach { node ->
                val pos = state.positions[node.id] ?: return@forEach
                if (!vp.isPointInViewport(pos.first, pos.second, size.width, size.height)) return@forEach
                val centre = vp.toScreenCoordinates(pos.first, pos.second)
                val status = state.statuses[node.id]
                val derived = status?.derivedStatus ?: DerivedStatus.NOT_STARTED
                val actor = colorForActor(node.actorKind)
                val ring = if (CanvasDetail.isRunning(status)) actor.copy(alpha = pulse) else actor
                when (node.type) {
                    IntentNodeType.GROUP -> {
                        val r = 140f * zoom
                        GroupNebulaRenderer.drawNebula(
                            drawScope = this,
                            topLeft = Offset(centre.x - r, centre.y - r),
                            size = Size(2f * r, 2f * r),
                            accentColor = actor,
                            label = node.title,
                        )
                    }
                    IntentNodeType.GOAL, IntentNodeType.SUB_GOAL -> {
                        val r = (if (node.type == IntentNodeType.GOAL) 24f else 16f) * zoom
                        GoalHexagonNode.drawHexagon(
                            drawScope = this,
                            center = centre,
                            radius = r,
                            fillColor = NodeFill,
                            borderColor = colorForStatus(derived),
                        )
                        drawProgressArc(centre, r, progress[node.id] ?: 0f)
                    }
                    IntentNodeType.CONSTRAINT -> ConstraintShieldNode.drawShield(
                        drawScope = this,
                        center = centre,
                        size = 26f * zoom,
                        fillColor = NodeFill,
                        borderColor = ring,
                    )
                    IntentNodeType.ACCEPTANCE_CRITERION -> SatelliteCriterionNode.drawDiamond(
                        drawScope = this,
                        center = centre,
                        size = 18f * zoom,
                        fillColor = ColorTokens.ActorHybrid,
                        borderColor = Color.White,
                    )
                    IntentNodeType.ATOMIC_TASK -> AtomicTaskCapsule.drawCapsule(
                        drawScope = this,
                        center = centre,
                        width = 120f * zoom,
                        height = 36f * zoom,
                        fillColor = NodeFill,
                        borderColor = ring,
                    )
                }
                // 3. Full detail: title, status and scope count under each node.
                if (tier == CanvasDetail.Tier.FULL && node.type != IntentNodeType.GROUP) {
                    drawNodeLabel(textMeasurer, node, derived, centre, 18f * zoom + 6f)
                }
            }
        }

        if (compact && crumbs.isNotEmpty()) {
            Text(
                text = crumbs.joinToString(" › ") { it.title },
                style = MaterialTheme.typography.labelMedium,
                color = LabelColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
        }
        if (!compact) {
            Box(modifier = Modifier.align(Alignment.BottomEnd).padding(12.dp)) {
                CanvasMinimapView(nodePositions = state.positions, viewport = state.viewport)
            }
        }
    }
}

/** Nodes that are drawn at the current zoom, so taps and long presses ignore hidden ones. */
private fun drawnPositions(s: SpatialCanvasUiState): Map<String, Pair<Float, Float>> {
    val tier = CanvasDetail.tierFor(s.viewport.zoom)
    val out = HashMap<String, Pair<Float, Float>>()
    for (node in s.nodes) {
        if (!CanvasDetail.isDrawn(tier, node.type)) continue
        s.positions[node.id]?.let { out[node.id] = it }
    }
    return out
}

private fun DrawScope.drawProgressArc(centre: Offset, radius: Float, fraction: Float) {
    if (fraction <= 0f) return
    val r = radius + 5f
    drawArc(
        color = ColorTokens.StatusDone,
        startAngle = -90f,
        sweepAngle = 360f * fraction.coerceIn(0f, 1f),
        useCenter = false,
        topLeft = Offset(centre.x - r, centre.y - r),
        size = Size(2f * r, 2f * r),
        style = Stroke(width = 3f),
    )
}

private fun DrawScope.drawNodeLabel(
    measurer: TextMeasurer,
    node: IntentNodeRecord,
    derived: DerivedStatus,
    centre: Offset,
    offsetY: Float,
) {
    val title: TextLayoutResult = measurer.measure(
        text = node.title,
        style = TextStyle(color = LabelColor, fontSize = 11.sp),
    )
    drawText(title, topLeft = Offset(centre.x - title.size.width / 2f, centre.y + offsetY))
    val meta = buildString {
        append(derived.name.lowercase().replace('_', ' '))
        if (node.scopePaths.isNotEmpty()) append(" · ${node.scopePaths.size} scope")
    }
    val metaLayout: TextLayoutResult = measurer.measure(text = meta, style = TextStyle(color = MetaColor, fontSize = 9.sp))
    drawText(
        metaLayout,
        topLeft = Offset(centre.x - metaLayout.size.width / 2f, centre.y + offsetY + title.size.height),
    )
}
