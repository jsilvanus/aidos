package dev.aidos.ui.spatial.radar

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.aidos.intent.DerivedStatus
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForStatus
import dev.aidos.ui.workspace.WorkspaceController
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** Radar radius as a fraction of the half-size of the drawing area, so the rim is never clipped. */
private const val RIM_FRACTION = 0.94f

/** Tap target for a blip, in dp (decision: 24 dp, see [hitTest]). */
private val HIT_RADIUS_DP = 24.dp

/**
 * Radial Focus Radar (Design 4, RFC-0111). Wide windows get the full 360-degree radar. Handhelds get the
 * 180-degree thumb-zone arc in [MobileHorizonArcView]. Both share the presenter and filter chips.
 */
@Composable
fun RadialRadarScreen(
    presenter: FocusRadarPresenter,
    controller: WorkspaceController,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()

    // Stand-in for runtimeClient.runtime.profile(): the app root does not hand the client to views yet.
    LaunchedEffect(formFactor) {
        presenter.send(FocusRadarIntent.SetDeviceProfile(profileForFormFactor(formFactor)))
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = "Radial Focus Radar",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.sm),
        )
        FilterChips(selected = state.filter, onSelect = { presenter.send(FocusRadarIntent.SetFilter(it)) })

        if (formFactor == FormFactor.HANDHELD_COMPACT) {
            MobileHorizonArcView(
                presenter = presenter,
                controller = controller,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
        } else {
            WideRadar(
                state = state,
                controller = controller,
                modifier = Modifier.weight(1f).fillMaxWidth(),
            )
            ZoneLabels(state = state, onSelect = { presenter.send(FocusRadarIntent.SelectZone(it)) })
            ActiveZoneList(state = state, controller = controller)
        }
    }
}

@Composable
private fun FilterChips(selected: RadarFilter, onSelect: (RadarFilter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = SpacingTokens.sm),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        RadarFilter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun WideRadar(
    state: FocusRadarUiState,
    controller: WorkspaceController,
    modifier: Modifier,
) {
    var boxSize by remember { mutableStateOf(IntSize.Zero) }
    val hitPx = with(LocalDensity.current) { HIT_RADIUS_DP.toPx() }
    val placed = remember(state.radarNodes, boxSize) {
        placeOnRadar(state.radarNodes, boxSize.width.toFloat(), boxSize.height.toFloat())
    }

    Box(
        modifier = modifier
            .onSizeChanged { boxSize = it }
            .pointerInput(placed) {
                detectTapGestures(onTap = { offset ->
                    hitTest(offset.x, offset.y, placed, hitPx)?.let { controller.openInspector(it.nodeId) }
                })
            },
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            drawRadar(state, placed)
        }
    }
}

@Composable
private fun ZoneLabels(state: FocusRadarUiState, onSelect: (RadarZone?) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
    ) {
        RadarZone.entries.forEach { zone ->
            val active = state.activeZone == zone
            TextButton(onClick = { onSelect(if (active) null else zone) }) {
                Text(
                    text = "${zone.label} ${state.countIn(zone)}",
                    style = AidosTypography.baseTypography.labelMedium,
                    color = if (active) Color.White else Color(0xFF94A3B8),
                )
            }
        }
    }
}

@Composable
private fun ActiveZoneList(state: FocusRadarUiState, controller: WorkspaceController) {
    val zone = state.activeZone ?: return
    val inZone = state.radarNodes.filter { it.zone == zone }
    if (inZone.isEmpty()) {
        Text(
            text = "Nothing in ${zone.label.lowercase()}.",
            style = AidosTypography.baseTypography.bodyMedium,
            color = Color(0xFF94A3B8),
        )
        return
    }
    LazyColumn(modifier = Modifier.fillMaxWidth().heightIn(max = 240.dp)) {
        items(inZone, key = { it.node.id }) { item ->
            Text(
                text = "${item.node.title}  ·  ${statusLabel(item.status)}",
                style = AidosTypography.baseTypography.bodyMedium,
                color = Color.White,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { controller.openInspector(item.node.id) }
                    .padding(vertical = SpacingTokens.sm),
            )
        }
    }
}

/** Radar drawing: rings at the zone bands, tractor beams, then blips. */
private fun DrawScope.drawRadar(state: FocusRadarUiState, placed: List<PlacedBlip>) {
    val centre = Offset(size.width / 2f, size.height / 2f)
    val rim = radarRadius(size.width, size.height)

    listOf(0.30f, 0.56f, 0.86f, 1.0f).forEach { ring ->
        drawCircle(color = Color(0x2294A3B8), radius = rim * ring, center = centre, style = Stroke(1f))
    }

    val byId = placed.associateBy { it.nodeId }
    state.beams.forEach { beam ->
        val from = byId[beam.fromId] ?: return@forEach
        val to = byId[beam.toId] ?: return@forEach
        drawLine(
            color = Color(0x3394A3B8),
            start = Offset(from.x, from.y),
            end = Offset(to.x, to.y),
            strokeWidth = 1f,
        )
    }

    state.radarNodes.forEach { item ->
        val point = byId[item.node.id] ?: return@forEach
        drawCircle(
            color = blipColor(item),
            radius = 7f,
            center = Offset(point.x, point.y),
        )
    }
}

/** Colour of a blip. Sealed blips are faded. NEEDS_REVIEW is amber, the same warning colour as the rest of the app. */
internal fun blipColor(item: RadarNodeUiModel): Color = when {
    item.zone == RadarZone.SEALED -> colorForStatus(DerivedStatus.DONE).copy(alpha = 0.35f)
    item.status == DerivedStatus.NEEDS_REVIEW -> ColorTokens.StatusReview
    else -> colorForStatus(item.status)
}

internal fun statusLabel(status: DerivedStatus): String = when (status) {
    DerivedStatus.NOT_STARTED -> "not started"
    DerivedStatus.IN_PROGRESS -> "in progress"
    DerivedStatus.BLOCKED -> "blocked"
    DerivedStatus.NEEDS_REVIEW -> "needs review"
    DerivedStatus.DONE -> "done"
    DerivedStatus.STALE -> "stale"
    DerivedStatus.CONFLICTED -> "conflicted"
}

/** Radius of the radar's rim, in the same units as the drawing area. */
internal fun radarRadius(width: Float, height: Float): Float = min(width, height) / 2f * RIM_FRACTION

/** Canvas positions of the blips on the full 360-degree radar. Empty until the canvas has a size. */
internal fun placeOnRadar(nodes: List<RadarNodeUiModel>, width: Float, height: Float): List<PlacedBlip> {
    if (width <= 0f || height <= 0f) return emptyList()
    val rim = radarRadius(width, height)
    val cx = width / 2f
    val cy = height / 2f
    return nodes.map { item ->
        val r = rim * item.polar.radius
        PlacedBlip(
            nodeId = item.node.id,
            x = cx + r * cos(item.polar.angleRad),
            y = cy + r * sin(item.polar.angleRad),
        )
    }
}
