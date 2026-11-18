package dev.aidos.ui.spatial.radar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForStatus
import dev.aidos.ui.workspace.WorkspaceController
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** Blips on the arc are dots this size in px; the hit target is still 24 dp (see [RadialRadarScreen]). */
private const val ARC_DOT_RADIUS = 6f

/**
 * Compact (handheld) view, Design 4 RFC-0111. A 180-degree arc sits in the thumb zone. Blocked and not-yet-
 * ready blips are dots along the arc. Strike-zone blips are large chips (at least 48 dp) along the bottom,
 * so one hand can reach them.
 */
@Composable
fun MobileHorizonArcView(
    presenter: FocusRadarPresenter,
    controller: WorkspaceController,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val hitPx = with(LocalDensity.current) { 24.dp.toPx() }
    val horizonBlips = state.radarNodes.filter { it.zone == RadarZone.HORIZON }
    val dots = remember(horizonBlips, boxSize) {
        placeOnArc(horizonBlips, boxSize.width.toFloat(), boxSize.height.toFloat())
    }
    val strike = state.radarNodes.filter { it.zone == RadarZone.STRIKE_ZONE }

    Column(modifier = modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .onSizeChanged { boxSize = it }
                .pointerInput(dots) {
                    detectTapGestures(onTap = { offset ->
                        hitTest(offset.x, offset.y, dots, hitPx)?.let { controller.openInspector(it.nodeId) }
                    })
                },
        ) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawHorizon(state, dots)
            }
        }

        Text(
            text = "Ready now",
            style = AidosTypography.baseTypography.labelMedium,
            color = Color(0xFF94A3B8),
            modifier = Modifier.padding(vertical = SpacingTokens.xs),
        )
        if (strike.isEmpty()) {
            Text(
                text = "Nothing ready right now.",
                style = AidosTypography.baseTypography.bodyMedium,
                color = Color(0xFF94A3B8),
            )
        } else {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                items(strike, key = { it.node.id }) { item ->
                    StrikeChip(item = item, onClick = { controller.openInspector(item.node.id) })
                }
            }
        }
    }
}

/** Draws the semicircle rings and the horizon dots. The arc's centre is the bottom middle of the box. */
private fun DrawScope.drawHorizon(state: FocusRadarUiState, dots: List<PlacedBlip>) {
    val radius = arcRadius(size.width, size.height)
    listOf(0.6f, 1.0f).forEach { ring ->
        val r = radius * ring
        drawArc(
            color = Color(0x2294A3B8),
            startAngle = 180f,
            sweepAngle = 180f,
            useCenter = false,
            topLeft = Offset(size.width / 2f - r, size.height - r),
            size = Size(r * 2f, r * 2f),
            style = Stroke(1f),
        )
    }
    val byId = state.radarNodes.associateBy { it.node.id }
    dots.forEach { dot ->
        val item = byId[dot.nodeId] ?: return@forEach
        drawCircle(color = blipColor(item), radius = ARC_DOT_RADIUS, center = Offset(dot.x, dot.y))
    }
}

@Composable
private fun StrikeChip(item: RadarNodeUiModel, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(24.dp),
        color = Color(0xFF1E293B),
        modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 48.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.sm),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(10.dp)
                    .background(colorForStatus(item.status), CircleShape),
            )
            Spacer(modifier = Modifier.width(SpacingTokens.sm))
            Text(
                text = item.node.title,
                style = AidosTypography.baseTypography.bodyMedium,
                color = Color.White,
                maxLines = 1,
            )
        }
    }
}

/** Radius of the arc's outer ring: the half-width of the box, or the full height, whichever is smaller. */
private fun arcRadius(width: Float, height: Float): Float = minOf(width / 2f, height) * 0.92f

/**
 * Canvas positions of horizon blips along the upper semicircle. A wedge fraction of 0 is the left end and 1
 * the right end. Each blip keeps its radius within the horizon band.
 */
internal fun placeOnArc(nodes: List<RadarNodeUiModel>, width: Float, height: Float): List<PlacedBlip> {
    if (width <= 0f || height <= 0f) return emptyList()
    val radius = arcRadius(width, height)
    val cx = width / 2f
    val cy = height
    return nodes.map { item ->
        val r = radius * item.polar.radius
        val angle = (-PI + PI * item.polar.fraction).toFloat()
        PlacedBlip(
            nodeId = item.node.id,
            x = cx + r * cos(angle),
            y = cy + r * sin(angle),
        )
    }
}
