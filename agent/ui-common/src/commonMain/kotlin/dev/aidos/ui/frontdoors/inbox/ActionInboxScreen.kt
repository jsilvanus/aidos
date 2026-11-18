package dev.aidos.ui.frontdoors.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController

/**
 * Action Inbox (RFC-0124, UI-30). Taps open the item in the Node Inspector of its own project; the
 * app root shows the inspector panel. [onItemClick] is no longer called and is kept so the app root compiles.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ActionInboxScreen(
    presenter: ActionInboxPresenter,
    onItemClick: (ActionItemUiModel) -> Unit,
    controller: WorkspaceController,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()
    var draft by remember { mutableStateOf("") }
    // Completing has no Undo: the runtime cannot reopen a DONE node (checked in IntentCommands, UI-30), so confirm first.
    var confirmTarget by remember { mutableStateOf<ActionItemUiModel?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = SpacingTokens.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Action Inbox",
                style = AidosTypography.baseTypography.headlineSmall,
                color = Color.White,
            )
            if (state.gatesCount > 0) {
                Text(
                    text = "${state.gatesCount} approvals waiting in Gates",
                    style = AidosTypography.baseTypography.labelMedium,
                    color = ColorTokens.StatusReview,
                    modifier = Modifier.clickable { controller.navigate("gates") },
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = SpacingTokens.sm),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
        ) {
            FilterChip(
                selected = !state.currentProjectOnly,
                onClick = { presenter.send(ActionInboxIntent.SetCurrentProjectOnly(false)) },
                label = { Text("All projects") },
            )
            FilterChip(
                selected = state.currentProjectOnly,
                onClick = { presenter.send(ActionInboxIntent.SetCurrentProjectOnly(true)) },
                label = { Text("This project") },
            )
        }

        OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            placeholder = { Text("Add a task for you…") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = SpacingTokens.md),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = SpacingTokens.md),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(
                enabled = draft.isNotBlank(),
                onClick = {
                    presenter.send(ActionInboxIntent.CreateItem(draft))
                    draft = ""
                },
            ) { Text("Add") }
        }

        val visible = state.visibleItems
        if (state.isLoading && state.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ColorTokens.ActorAgent)
            }
        } else if (visible.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Nothing needs you right now.",
                    style = AidosTypography.baseTypography.bodyMedium,
                    color = Color(0xFF94A3B8),
                )
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                contentPadding = PaddingValues(vertical = SpacingTokens.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(visible, key = { it.node.id }) { item ->
                    ActionItemCard(
                        item = item,
                        onComplete = { _ -> confirmTarget = item },
                        onClick = {
                            controller.switchProject(item.node.projectId)
                            controller.openInspector(item.node.id)
                        },
                    )
                }
            }
        }
    }

    confirmTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { confirmTarget = null },
            title = { Text("Mark as done?") },
            text = { Text(target.node.title) },
            confirmButton = {
                TextButton(onClick = {
                    presenter.send(ActionInboxIntent.CompleteItem(target.node.projectId, target.node.id))
                    confirmTarget = null
                }) { Text("Mark done") }
            },
            dismissButton = {
                TextButton(onClick = { confirmTarget = null }) { Text("Cancel") }
            },
        )
    }
}
