package dev.aidos.ui.paradigms.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import dev.aidos.api.UpdateIntentNodeRequest
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForStatus
import dev.aidos.ui.workspace.WorkspaceController
import kotlinx.datetime.LocalDate
import kotlin.math.roundToInt
import kotlin.math.sqrt

private val LabelWidth = 180.dp
private val HeaderHeight = 28.dp
private val RowHeight = 36.dp
private val BarHeight = 22.dp
private val HandleWidth = 10.dp
private val OverdueColor = Color(0xFFDC2626)
private val DependencyColor = Color(0xFF94A3B8)
private val TodayColor = Color(0xFFFACC15)

/**
 * Timeline view (RFC-0112 §2.1, RFC-0124 UI-37). Two modes: by due date (Dates) and by dependency
 * depth (Sequence). On desktop Dates is a Gantt chart; on compact screens it is an agenda grouped by
 * day. Tapping a bar opens the Node Inspector. On desktop the right edge of a bar can be dragged to
 * change `dueAt`.
 */
@Composable
fun TimelineGanttScreen(
    presenter: TimelineGanttPresenter,
    formFactor: FormFactor = FormFactor.DESKTOP_DOCKED,
    onNodeClick: (IntentNodeRecord) -> Unit = {},
    controller: WorkspaceController,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()
    val isCompact = formFactor == FormFactor.HANDHELD_COMPACT
    val openNode: (IntentNodeRecord) -> Unit = { node ->
        presenter.send(TimelineGanttIntent.SelectNode(node.id))
        controller.openInspector(node.id)
        onNodeClick(node)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = if (state.mode == TimelineMode.DATES) "Timeline by due date" else "Timeline by sequence",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.sm),
        )
        ModeToggle(mode = state.mode, onSelect = { presenter.send(TimelineGanttIntent.SetMode(it)) })

        val dates = state.dates
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (state.mode == TimelineMode.DATES && dates != null) {
                DatesContent(
                    model = dates,
                    isCompact = isCompact,
                    onOpen = openNode,
                    onReschedule = { bar, newDueDay ->
                        controller.updateNode(
                            UpdateIntentNodeRequest(
                                projectId = controller.state.value.projectId,
                                nodeId = bar.node.id,
                                dueAt = LocalDate.fromEpochDays(newDueDay).toString(),
                            ),
                        )
                    },
                )
            } else if (state.bars.isEmpty() && !state.isLoading) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = "No scheduled tasks in this project.",
                        style = AidosTypography.baseTypography.bodyMedium,
                        color = ColorTokens.StatusStale,
                    )
                }
            } else {
                SequenceList(state = state, isCompact = isCompact, onOpen = openNode)
            }
        }
    }
}

@Composable
private fun ModeToggle(mode: TimelineMode, onSelect: (TimelineMode) -> Unit) {
    Row(
        modifier = Modifier.padding(bottom = SpacingTokens.md),
        horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        ModeChip(label = "By due date", selected = mode == TimelineMode.DATES) { onSelect(TimelineMode.DATES) }
        ModeChip(label = "By sequence", selected = mode == TimelineMode.SEQUENCE) { onSelect(TimelineMode.SEQUENCE) }
    }
}

@Composable
private fun ModeChip(label: String, selected: Boolean, onClick: () -> Unit) {
    Text(
        text = label,
        style = AidosTypography.baseTypography.labelMedium,
        color = if (selected) Color.White else ColorTokens.StatusStale,
        modifier = Modifier
            .background(
                if (selected) Color(0xFF334155) else Color.Transparent,
                shape = RoundedCornerShape(CornerTokens.sm),
            )
            .clickable(onClick = onClick)
            .padding(horizontal = SpacingTokens.md, vertical = SpacingTokens.xs),
    )
}

@Composable
private fun DatesContent(
    model: DateTimelineModel,
    isCompact: Boolean,
    onOpen: (IntentNodeRecord) -> Unit,
    onReschedule: (DateBarUiModel, Int) -> Unit,
) {
    Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (model.bars.isEmpty()) {
                Text(
                    text = "No tasks have a due date yet. Set one in the Node Inspector to see them here.",
                    style = AidosTypography.baseTypography.bodyMedium,
                    color = ColorTokens.StatusStale,
                    modifier = Modifier.padding(vertical = SpacingTokens.sm),
                )
            } else if (isCompact) {
                AgendaList(model = model, onOpen = onOpen)
            } else {
                DatesGantt(model = model, onOpen = onOpen, onReschedule = onReschedule)
            }
        }
        if (model.unscheduled.isNotEmpty()) {
            UnscheduledList(nodes = model.unscheduled, onOpen = onOpen)
        }
    }
}

private sealed interface GanttRow {
    data class Header(val title: String) : GanttRow
    data class Bar(val bar: DateBarUiModel) : GanttRow
}

/** Bars are already sorted by goal, so each goal forms one contiguous group. */
private fun groupRows(bars: List<DateBarUiModel>): List<GanttRow> {
    val rows = mutableListOf<GanttRow>()
    var started = false
    var lastGoalId: String? = null
    bars.forEach { bar ->
        if (!started || bar.goalId != lastGoalId) {
            rows += GanttRow.Header(bar.goalTitle ?: "No goal")
            lastGoalId = bar.goalId
            started = true
        }
        rows += GanttRow.Bar(bar)
    }
    return rows
}

@Composable
private fun DatesGantt(
    model: DateTimelineModel,
    onOpen: (IntentNodeRecord) -> Unit,
    onReschedule: (DateBarUiModel, Int) -> Unit,
) {
    val rows = remember(model) { groupRows(model.bars) }
    val barsById = remember(model) { model.bars.associateBy { it.node.id } }

    Column(modifier = Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val labelPx = with(density) { LabelWidth.toPx() }
            val chartPx = (with(density) { maxWidth.toPx() } - labelPx).coerceAtLeast(1f)
            val span = (model.rangeEndDay - model.rangeStartDay).coerceAtLeast(1)
            val pxPerDay = chartPx / span
            val headerPx = with(density) { HeaderHeight.toPx() }
            val rowPx = with(density) { RowHeight.toPx() }

            // Vertical centre of every bar row, so dependency arrows can be drawn across rows.
            val centreY = HashMap<String, Float>()
            var y = 0f
            rows.forEach { row ->
                when (row) {
                    is GanttRow.Header -> {
                        y += headerPx
                    }
                    is GanttRow.Bar -> {
                        centreY[row.bar.node.id] = y + rowPx / 2f
                        y += rowPx
                    }
                }
            }

            Box(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.fillMaxWidth()) {
                    rows.forEach { row ->
                        when (row) {
                            is GanttRow.Header -> GoalHeader(title = row.title)
                            is GanttRow.Bar -> BarRow(
                                bar = row.bar,
                                rangeStartDay = model.rangeStartDay,
                                pxPerDay = pxPerDay,
                                onOpen = onOpen,
                                onReschedule = onReschedule,
                            )
                        }
                    }
                }
                Canvas(modifier = Modifier.matchParentSize()) {
                    val todayDay = model.today.toEpochDays()
                    if (todayDay in model.rangeStartDay..model.rangeEndDay) {
                        val x = labelPx + (todayDay - model.rangeStartDay + 0.5f) * pxPerDay
                        drawLine(TodayColor, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
                    }
                    model.dependencies.forEach { (fromId, toId) ->
                        val from = barsById[fromId] ?: return@forEach
                        val to = barsById[toId] ?: return@forEach
                        val fromY = centreY[fromId] ?: return@forEach
                        val toY = centreY[toId] ?: return@forEach
                        val start = Offset(labelPx + (from.dueDay + 1 - model.rangeStartDay) * pxPerDay, fromY)
                        val end = Offset(labelPx + (to.startDay - model.rangeStartDay) * pxPerDay, toY)
                        drawArrow(start, end)
                    }
                }
            }
        }
    }
}

/** A line from [start] to [end] with a small arrowhead at [end]. */
private fun DrawScope.drawArrow(start: Offset, end: Offset) {
    val dx = end.x - start.x
    val dy = end.y - start.y
    val length = sqrt(dx * dx + dy * dy).coerceAtLeast(1f)
    val ux = dx / length
    val uy = dy / length
    val head = 8.dp.toPx()
    val baseX = end.x - ux * head
    val baseY = end.y - uy * head
    val px = -uy * head * 0.5f
    val py = ux * head * 0.5f
    drawLine(DependencyColor, start, end, strokeWidth = 1.5f)
    drawLine(DependencyColor, end, Offset(baseX + px, baseY + py), strokeWidth = 1.5f)
    drawLine(DependencyColor, end, Offset(baseX - px, baseY - py), strokeWidth = 1.5f)
}

@Composable
private fun GoalHeader(title: String) {
    Text(
        text = title,
        style = AidosTypography.baseTypography.titleSmall,
        color = ColorTokens.StatusStale,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().height(HeaderHeight).padding(top = SpacingTokens.xs),
    )
}

@Composable
private fun BarRow(
    bar: DateBarUiModel,
    rangeStartDay: Int,
    pxPerDay: Float,
    onOpen: (IntentNodeRecord) -> Unit,
    onReschedule: (DateBarUiModel, Int) -> Unit,
) {
    val density = LocalDensity.current
    val statusColor = if (bar.overdue) OverdueColor else colorForStatus(bar.status)
    var dragPx by remember(bar.node.id, bar.dueDay) { mutableStateOf(0f) }
    val currentBar by rememberUpdatedState(bar)
    val currentPxPerDay by rememberUpdatedState(pxPerDay)
    val currentOnReschedule by rememberUpdatedState(onReschedule)

    val leftPx = (bar.startDay - rangeStartDay) * pxPerDay
    val basePx = (bar.dueDay + 1 - bar.startDay).coerceAtLeast(1) * pxPerDay
    val widthPx = (basePx + dragPx).coerceAtLeast(pxPerDay)
    val title = if (bar.recurring) "↻ ${bar.node.title}" else bar.node.title

    Row(
        modifier = Modifier.fillMaxWidth().height(RowHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = bar.node.title,
            style = AidosTypography.baseTypography.labelMedium,
            color = Color.White,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(LabelWidth).padding(end = SpacingTokens.sm),
        )
        Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset(leftPx.roundToInt(), 0) }
                    .width(with(density) { widthPx.toDp() })
                    .height(BarHeight)
                    .background(statusColor.copy(alpha = 0.35f), shape = RoundedCornerShape(CornerTokens.xs))
                    .border(1.dp, statusColor, shape = RoundedCornerShape(CornerTokens.xs))
                    .clickable { onOpen(bar.node) }
                    .padding(horizontal = SpacingTokens.xs),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    text = title,
                    style = AidosTypography.baseTypography.labelSmall,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // Drag handle on the right edge: dragging it moves `dueAt` by whole days on release.
            Box(
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .offset { IntOffset((leftPx + widthPx - HandleWidth.toPx() / 2f).roundToInt(), 0) }
                    .width(HandleWidth)
                    .height(BarHeight)
                    .pointerInput(bar.node.id) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val days = (dragPx / currentPxPerDay).roundToInt()
                                dragPx = 0f
                                val newDue = (currentBar.dueDay + days).coerceAtLeast(currentBar.startDay)
                                if (newDue != currentBar.dueDay) currentOnReschedule(currentBar, newDue)
                            },
                            onDragCancel = { dragPx = 0f },
                        ) { change, amount ->
                            change.consume()
                            dragPx += amount
                        }
                    },
            )
        }
    }
}

@Composable
private fun AgendaList(model: DateTimelineModel, onOpen: (IntentNodeRecord) -> Unit) {
    val agenda = remember(model) { TimelineGanttPresenter.buildAgenda(model.bars) }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        contentPadding = PaddingValues(vertical = SpacingTokens.xs),
        modifier = Modifier.fillMaxSize(),
    ) {
        agenda.forEach { day ->
            item(key = "day-${day.day}") {
                val isToday = day.day == model.today
                val isPast = day.day < model.today
                Text(
                    text = if (isToday) "${day.day} • today" else day.day.toString(),
                    style = AidosTypography.baseTypography.titleSmall,
                    color = if (isPast) OverdueColor else Color.White,
                    modifier = Modifier.padding(top = SpacingTokens.sm),
                )
            }
            items(day.items, key = { it.node.id }) { bar ->
                val statusColor = if (bar.overdue) OverdueColor else colorForStatus(bar.status)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B), shape = RoundedCornerShape(CornerTokens.sm))
                        .border(1.dp, statusColor, shape = RoundedCornerShape(CornerTokens.sm))
                        .clickable { onOpen(bar.node) }
                        .padding(SpacingTokens.md),
                ) {
                    Text(
                        text = if (bar.recurring) "↻ ${bar.node.title}" else bar.node.title,
                        style = AidosTypography.baseTypography.titleSmall,
                        color = Color.White,
                    )
                    Text(
                        text = buildString {
                            append(bar.status.name)
                            if (bar.overdue) append(" • OVERDUE")
                            bar.goalTitle?.let { append(" • ").append(it) }
                        },
                        style = AidosTypography.baseTypography.labelSmall,
                        color = statusColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun UnscheduledList(nodes: List<IntentNodeRecord>, onOpen: (IntentNodeRecord) -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = SpacingTokens.md)
            .heightIn(max = 180.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Text(
            text = "Unscheduled (${nodes.size})",
            style = AidosTypography.baseTypography.titleSmall,
            color = ColorTokens.StatusStale,
        )
        nodes.forEach { node ->
            Text(
                text = node.title,
                style = AidosTypography.baseTypography.labelMedium,
                color = Color.White,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onOpen(node) }
                    .padding(vertical = SpacingTokens.xs),
            )
        }
    }
}

/** Sequence mode: the original dependency-depth view, unchanged apart from the shared open handler. */
@Composable
private fun SequenceList(
    state: TimelineGanttUiState,
    isCompact: Boolean,
    onOpen: (IntentNodeRecord) -> Unit,
) {
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        contentPadding = PaddingValues(vertical = SpacingTokens.xs),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(state.bars, key = { it.node.id }) { bar ->
            val statusColor = colorForStatus(bar.status)
            val isSelected = state.selectedNodeId == bar.node.id
            if (isCompact) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF1E293B), shape = RoundedCornerShape(CornerTokens.sm))
                        .border(
                            width = if (isSelected) 2.dp else 1.dp,
                            color = statusColor,
                            shape = RoundedCornerShape(CornerTokens.sm),
                        )
                        .clickable { onOpen(bar.node) }
                        .padding(SpacingTokens.md),
                ) {
                    Text(
                        text = "Phase ${bar.startSlot + 1} • ${bar.node.title}",
                        style = AidosTypography.baseTypography.titleSmall,
                        color = Color.White,
                    )
                    Text(
                        text = "${bar.status.name} • ${bar.durationSlots} step(s) • ${bar.dependencyCount} prereq(s)",
                        style = AidosTypography.baseTypography.labelSmall,
                        color = statusColor,
                    )
                }
            } else {
                val total = state.totalSlots.toFloat().coerceAtLeast(1f)
                val offsetWeight = (bar.startSlot / total).coerceIn(0f, 0.85f)
                val barWeight = (bar.durationSlots / total).coerceIn(0.1f, 1f - offsetWeight)
                val trailingWeight = (1f - offsetWeight - barWeight).coerceAtLeast(0f)

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF0F172A), shape = RoundedCornerShape(CornerTokens.sm))
                        .clickable { onOpen(bar.node) }
                        .padding(SpacingTokens.sm),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = bar.node.title,
                        style = AidosTypography.baseTypography.labelMedium,
                        color = Color.White,
                        modifier = Modifier.width(180.dp),
                    )
                    Row(modifier = Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                        if (offsetWeight > 0f) Spacer(modifier = Modifier.weight(offsetWeight))
                        Box(
                            modifier = Modifier
                                .weight(barWeight)
                                .height(24.dp)
                                .background(statusColor.copy(alpha = 0.35f), shape = RoundedCornerShape(CornerTokens.xs))
                                .border(1.dp, statusColor, shape = RoundedCornerShape(CornerTokens.xs))
                                .padding(horizontal = SpacingTokens.xs),
                            contentAlignment = Alignment.CenterStart,
                        ) {
                            Text(
                                text = "T+${bar.startSlot} (${bar.durationSlots}s)",
                                style = AidosTypography.baseTypography.labelSmall,
                                color = Color.White,
                            )
                        }
                        if (trailingWeight > 0f) Spacer(modifier = Modifier.weight(trailingWeight))
                    }
                }
            }
        }
    }
}
