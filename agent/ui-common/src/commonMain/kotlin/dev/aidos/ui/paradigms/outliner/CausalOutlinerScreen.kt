package dev.aidos.ui.paradigms.outliner

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController
import kotlinx.coroutines.launch

/**
 * RFC-0111 Design 6. Three sections in one list: Action Horizon, Intent Tree, Verification Ledger.
 * Desktop keys: j/k move, Enter opens the inspector, Space toggles, Tab indents an open child field.
 * [onDrillDown] is kept for the app root; a row tap now opens the shared Node Inspector instead.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun CausalOutlinerScreen(
    presenter: CausalOutlinerPresenter,
    onDrillDown: (String) -> Unit,
    controller: WorkspaceController,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()
    val workspace by controller.state.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    val entries = buildEntries(state)

    LaunchedEffect(state.jumpRequest) {
        val request = state.jumpRequest ?: return@LaunchedEffect
        val index = jumpIndex(entries, request.nodeId)
        if (index >= 0) listState.animateScrollToItem(index)
    }
    // Requested only once the list is composed; before that the focus target is not attached.
    LaunchedEffect(state.isLoading) {
        if (!state.isLoading) focusRequester.requestFocus()
    }

    val onKey: (KeyEvent) -> Boolean = { event ->
        handleOutlinerKey(event, state, presenter, controller) { id ->
            val index = jumpIndex(entries, id)
            if (index >= 0) scope.launch { listState.animateScrollToItem(index) }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = "Causal Outliner & Action Horizon",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )
        workspace.message?.let { message ->
            Text(
                text = message,
                style = AidosTypography.baseTypography.labelSmall,
                color = Color(0xFF7DD3FC),
                modifier = Modifier.padding(bottom = SpacingTokens.xs).clickable { controller.clearMessage() },
            )
        }

        if (state.isLoading && state.visibleRows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ColorTokens.ActorAgent)
            }
        } else {
            LazyColumn(
                state = listState,
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
                contentPadding = PaddingValues(vertical = SpacingTokens.xs),
                modifier = Modifier
                    .fillMaxSize()
                    .focusRequester(focusRequester)
                    .focusable()
                    .onPreviewKeyEvent(onKey),
            ) {
                items(entries, key = { it.key }) { entry ->
                    OutlinerEntryItem(entry, state, presenter, controller)
                }
            }
        }
    }
}

@Composable
private fun OutlinerEntryItem(
    entry: OutlinerEntry,
    state: CausalOutlinerUiState,
    presenter: CausalOutlinerPresenter,
    controller: WorkspaceController,
) {
    when (entry) {
        is OutlinerEntry.Header -> SectionHeader(entry) {
            presenter.send(CausalOutlinerIntent.ToggleSection(entry.section))
        }
        is OutlinerEntry.Row -> OutlinerNodeRow(
            item = entry.item,
            isSelected = state.focusedNodeId == entry.item.node.id,
            onToggleExpand = { id -> presenter.send(CausalOutlinerIntent.ToggleExpand(id)) },
            onNodeClick = { id ->
                presenter.send(CausalOutlinerIntent.SelectNode(id))
                controller.openInspector(id)
            },
            onCausalJump = { id -> presenter.send(CausalOutlinerIntent.JumpToPrerequisite(id)) },
            onAddChild = { id -> presenter.send(CausalOutlinerIntent.StartChild(id)) },
            onComplete = { id -> controller.completeHumanNode(id) },
            onStart = { id -> controller.startNodeRun(id) },
        )
        is OutlinerEntry.Draft -> {
            val draft = entry.draft
            ChildDraftField(
                draft = draft,
                depth = entry.depth,
                parentTitle = entry.parentTitle,
                onText = { text -> presenter.send(CausalOutlinerIntent.DraftText(text)) },
                onSubmit = { text ->
                    val title = text.trim()
                    if (title.isNotEmpty()) {
                        controller.createNode(
                            CreateIntentNodeRequest(
                                projectId = controller.state.value.projectId,
                                title = title,
                                type = draft.type,
                                parentId = draft.parentId,
                            )
                        )
                    }
                    presenter.send(CausalOutlinerIntent.CancelDraft)
                },
                onCancel = { presenter.send(CausalOutlinerIntent.CancelDraft) },
            )
        }
        is OutlinerEntry.Empty -> Text(
            text = "Nothing here yet.",
            style = AidosTypography.baseTypography.labelSmall,
            color = Color(0xFF64748B),
            modifier = Modifier.padding(start = SpacingTokens.base, bottom = SpacingTokens.xs),
        )
    }
}

@Composable
private fun SectionHeader(entry: OutlinerEntry.Header, onToggle: () -> Unit) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .padding(top = SpacingTokens.sm, bottom = SpacingTokens.xs),
    ) {
        Text(
            text = if (entry.collapsed) "▶ " else "▼ ",
            style = AidosTypography.baseTypography.labelSmall,
            color = Color(0xFF94A3B8),
        )
        Text(
            text = entry.section.title,
            style = AidosTypography.baseTypography.titleSmall,
            color = Color.White,
        )
        Text(
            text = "  ${entry.count}",
            style = AidosTypography.baseTypography.labelSmall,
            color = Color(0xFF94A3B8),
        )
    }
}

/**
 * Keyboard handling for the list. While a child field is open only Tab and Escape are taken here,
 * so the letters typed into the field are not read as navigation.
 */
private fun handleOutlinerKey(
    event: KeyEvent,
    state: CausalOutlinerUiState,
    presenter: CausalOutlinerPresenter,
    controller: WorkspaceController,
    scrollTo: (String) -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val draftOpen = state.draft != null
    if (draftOpen) {
        return when (event.key) {
            Key.Tab -> {
                presenter.send(CausalOutlinerIntent.IndentDraft)
                true
            }
            Key.Escape -> {
                presenter.send(CausalOutlinerIntent.CancelDraft)
                true
            }
            else -> false
        }
    }
    val rows = state.visibleRows
    val focused = state.focusedNodeId
    return when (event.key) {
        Key.J, Key.K -> {
            if (rows.isEmpty()) return true
            val current = rows.indexOfFirst { it.node.id == focused }
            val delta = if (event.key == Key.J) 1 else -1
            val next = when {
                current < 0 -> if (delta > 0) 0 else rows.lastIndex
                else -> (current + delta).coerceIn(0, rows.lastIndex)
            }
            val id = rows[next].node.id
            presenter.send(CausalOutlinerIntent.SelectNode(id))
            scrollTo(id)
            true
        }
        Key.Enter, Key.NumPadEnter -> {
            if (focused == null) return false
            controller.openInspector(focused)
            true
        }
        Key.Spacebar -> {
            val row = rows.firstOrNull { it.node.id == focused }
            if (row == null || !row.hasChildren) return false
            presenter.send(CausalOutlinerIntent.ToggleExpand(row.node.id))
            true
        }
        else -> false
    }
}
