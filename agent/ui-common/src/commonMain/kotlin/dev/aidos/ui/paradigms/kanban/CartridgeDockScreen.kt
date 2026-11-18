package dev.aidos.ui.paradigms.kanban

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import dev.aidos.api.UpdateIntentNodeRequest
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Cartridge Dock (RFC-0111 Design 3, RFC-0124 UI-36). Desktop and expanded show the board with
 * goal swimlanes and drag between columns; compact shows the Cartridge Deck of Ready cartridges
 * with Start and Done buttons. Every move is decided by [CartridgeDockPresenter.drop]; a refused
 * move shows its reason in a snackbar and the card stays where it was.
 */
@Composable
fun CartridgeDockScreen(
    presenter: CartridgeDockPresenter,
    controller: WorkspaceController,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun perform(action: DockAction) {
        when (action) {
            is DockAction.Start -> controller.startNodeRun(action.nodeId)
            is DockAction.Complete -> controller.completeHumanNode(action.nodeId)
            is DockAction.Confirm -> controller.confirmImplementation(action.nodeId)
            is DockAction.Refuse -> scope.launch { snackbar.showSnackbar(action.reason) }
            DockAction.NoOp -> Unit
        }
    }

    key(presenter) {
        Box(modifier = modifier.fillMaxSize().background(ColorTokens.SurfaceDark)) {
            if (formFactor == FormFactor.HANDHELD_COMPACT) {
                CartridgeDeck(
                    state = state,
                    onStart = { perform(presenter.drop(it, DockLane.IN_FLIGHT)) },
                    onDone = { perform(presenter.drop(it, DockLane.DONE)) },
                    onOpen = { controller.openInspector(it) },
                    onToggleDispatch = { node -> controller.updateNode(dispatchRequest(node)) },
                )
            } else {
                DockBoard(
                    state = state,
                    presenter = presenter,
                    onPerform = ::perform,
                    onOpen = { controller.openInspector(it) },
                    onToggleDispatch = { node -> controller.updateNode(dispatchRequest(node)) },
                )
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

private fun dispatchRequest(node: IntentNodeRecord) = UpdateIntentNodeRequest(
    projectId = node.projectId,
    nodeId = node.id,
    dispatchPolicy = toggledDispatch(node.dispatchPolicy),
)

private val LaneWidth = 220.dp

@Composable
private fun DockBoard(
    state: CartridgeDockUiState,
    presenter: CartridgeDockPresenter,
    onPerform: (DockAction) -> Unit,
    onOpen: (String) -> Unit,
    onToggleDispatch: (IntentNodeRecord) -> Unit,
) {
    val vertical = rememberScrollState()
    val horizontal = rememberScrollState()
    var drag by remember { mutableStateOf<DragSession?>(null) }
    var boardOrigin by remember { mutableStateOf(Offset.Zero) }
    val cardRects = remember { HashMap<String, Rect>() }
    val laneRects = remember { HashMap<DockLane, Rect>() }

    fun laneAt(x: Float): DockLane? =
        DockLane.entries.firstOrNull { lane -> laneRects[lane]?.let { x >= it.left && x < it.right } == true }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { boardOrigin = it.boundsInRoot().topLeft },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(vertical)
                .padding(SpacingTokens.base),
            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            Text(
                text = "Cartridge Dock",
                style = AidosTypography.baseTypography.headlineSmall,
                color = Color.White,
            )

            if (state.attention.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(horizontal),
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                ) {
                    Text(
                        text = "Attention",
                        style = AidosTypography.baseTypography.labelSmall,
                        color = Color(0xFFF59E0B),
                        modifier = Modifier.padding(top = 6.dp),
                    )
                    state.attention.forEach { (node, status) ->
                        Box(
                            modifier = Modifier
                                .background(Color(0xFF451A03), shape = RoundedCornerShape(6.dp))
                                .clickable { onOpen(node.id) }
                                .padding(horizontal = 8.dp, vertical = 4.dp),
                        ) {
                            Text(
                                text = "${node.title} · ${status.name}",
                                style = AidosTypography.baseTypography.labelSmall,
                                color = Color(0xFFFDE68A),
                            )
                        }
                    }
                }
            }

            // Header: one cell per lane, recorded so a drag can tell which lane it is over.
            Row(modifier = Modifier.horizontalScroll(horizontal)) {
                DockLane.entries.forEach { lane ->
                    val count = state.columns.firstOrNull { it.lane == lane }?.tasks?.size ?: 0
                    Text(
                        text = "${lane.title} ($count)",
                        style = AidosTypography.baseTypography.titleSmall,
                        color = Color(0xFF94A3B8),
                        modifier = Modifier
                            .width(LaneWidth)
                            .padding(horizontal = SpacingTokens.xs)
                            .onGloballyPositioned { laneRects[lane] = it.boundsInRoot() },
                    )
                }
            }

            if (state.swimlanes.isEmpty() && !state.isLoading) {
                Text(
                    text = "No tasks yet. Create one from the Inspector or the Assistant.",
                    style = AidosTypography.baseTypography.bodySmall,
                    color = Color(0xFF94A3B8),
                )
            }

            state.swimlanes.forEach { lane ->
                Text(
                    text = lane.title,
                    style = AidosTypography.baseTypography.labelLarge,
                    color = Color.White,
                    modifier = Modifier.padding(top = SpacingTokens.sm),
                )
                Row(modifier = Modifier.horizontalScroll(horizontal)) {
                    DockLane.entries.forEach { column ->
                        Column(
                            modifier = Modifier
                                .width(LaneWidth)
                                .padding(horizontal = SpacingTokens.xs)
                                .background(Color(0xFF0F172A), shape = RoundedCornerShape(CornerTokens.sm))
                                .padding(SpacingTokens.sm),
                            verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                        ) {
                            lane.cells[column].orEmpty().forEach { node ->
                                val status = state.columns.firstOrNull { c -> c.tasks.any { it.first.id == node.id } }
                                    ?.tasks?.first { it.first.id == node.id }?.second ?: DerivedStatus.NOT_STARTED
                                val dragging = drag?.node?.id == node.id
                                TaskCartridgeView(
                                    node = node,
                                    status = status,
                                    onClick = { onOpen(node.id) },
                                    unmetDependencies = state.unmet[node.id] ?: 0,
                                    onToggleDispatch = { onToggleDispatch(node) },
                                    modifier = Modifier
                                        .alpha(if (dragging) 0.35f else 1f)
                                        .onGloballyPositioned { cardRects[node.id] = it.boundsInRoot() }
                                        .pointerInput(node.id, column, presenter) {
                                            detectDragGestures(
                                                onDragStart = { local ->
                                                    cardRects[node.id]?.let { r ->
                                                        drag = DragSession(node, r.topLeft + local, Offset.Zero)
                                                    }
                                                },
                                                onDrag = { change, amount ->
                                                    change.consume()
                                                    drag = drag?.let { it.copy(total = it.total + amount) }
                                                },
                                                onDragEnd = {
                                                    val d = drag
                                                    drag = null
                                                    if (d != null) {
                                                        val pointer = d.pointerStart + d.total
                                                        laneAt(pointer.x)?.let { target ->
                                                            onPerform(presenter.drop(d.node.id, target))
                                                        }
                                                    }
                                                },
                                                onDragCancel = { drag = null },
                                            )
                                        },
                                )
                            }
                        }
                    }
                }
            }
        }

        drag?.let { d ->
            val pointer = d.pointerStart + d.total
            val density = LocalDensity.current.density
            Box(
                modifier = Modifier
                    .offset {
                        IntOffset(
                            (pointer.x - boardOrigin.x - 60f * density).roundToInt(),
                            (pointer.y - boardOrigin.y - 14f * density).roundToInt(),
                        )
                    }
                    .width(LaneWidth)
                    .background(Color(0xFF334155), shape = RoundedCornerShape(CornerTokens.sm))
                    .border(1.dp, Color(0xFF64748B), RoundedCornerShape(CornerTokens.sm))
                    .padding(SpacingTokens.sm),
            ) {
                Text(text = d.node.title, style = AidosTypography.baseTypography.labelLarge, color = Color.White)
            }
        }
    }
}

private data class DragSession(
    val node: IntentNodeRecord,
    /** Where the pointer went down, in root coordinates. */
    val pointerStart: Offset,
    /** How far the pointer has moved since. */
    val total: Offset,
)

@Composable
private fun CartridgeDeck(
    state: CartridgeDockUiState,
    onStart: (String) -> Unit,
    onDone: (String) -> Unit,
    onOpen: (String) -> Unit,
    onToggleDispatch: (IntentNodeRecord) -> Unit,
) {
    var showAll by remember { mutableStateOf(false) }
    val ready = state.columns.firstOrNull { it.lane == DockLane.READY }?.tasks.orEmpty()
    val cards = deckFilter(ready.map { it.first }, showAll)

    Column(
        modifier = Modifier.fillMaxSize().padding(SpacingTokens.base),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Cartridge Deck",
                style = AidosTypography.baseTypography.headlineSmall,
                color = Color.White,
            )
            FilterChip(
                selected = showAll,
                onClick = { showAll = !showAll },
                label = { Text(if (showAll) "All" else "Phone and any") },
            )
        }

        if (cards.isEmpty()) {
            Text(
                text = if (showAll) "Nothing is ready to start." else "No ready cartridges for this phone. Tap All to see the rest.",
                style = AidosTypography.baseTypography.bodyMedium,
                color = Color(0xFF94A3B8),
            )
        }

        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            // One cartridge fills most of the phone width, so the next one peeks in: a pager.
            val cardWidth = maxWidth * 0.85f
            LazyRow(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.md)) {
                items(cards, key = { it.id }) { node ->
                    TaskCartridgeView(
                        node = node,
                        status = DerivedStatus.NOT_STARTED,
                        onClick = { onOpen(node.id) },
                        onToggleDispatch = { onToggleDispatch(node) },
                        modifier = Modifier.width(cardWidth),
                        actions = {
                            Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                                Button(onClick = { onStart(node.id) }) { Text("Start") }
                                OutlinedButton(onClick = { onDone(node.id) }) { Text("Done") }
                            }
                        },
                    )
                }
            }
        }
    }
}
