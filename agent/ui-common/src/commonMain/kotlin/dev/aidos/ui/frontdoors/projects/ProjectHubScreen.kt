package dev.aidos.ui.frontdoors.projects

import dev.aidos.ui.layout.FormFactor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController
import kotlinx.coroutines.delay
import kotlinx.datetime.Clock

private val PROJECT_TYPES = listOf(
    "generic" to "Generic",
    "personal" to "Personal",
    "code" to "Code (git repository)",
)

/**
 * Projects list and creation (UI-34). Real projects from `projects.list()`, refreshed on open and
 * every 30 s by the presenter. The card counts come from the project's store only if it is already
 * running; other cards show a dash.
 */
@Composable
@Suppress("UNUSED_PARAMETER")
fun ProjectHubScreen(
    presenter: ProjectHubPresenter,
    onProjectClick: (String) -> Unit,
    onPinProjectClick: ((projectId: String, projectName: String) -> Unit)? = null,
    controller: WorkspaceController,
    formFactor: FormFactor,
    modifier: Modifier = Modifier,
) {
    val state by presenter.state.collectAsState()
    val workspace by controller.state.collectAsState()

    // Counts are read from running stores; this tick re-reads them every 5 s.
    val countTick by produceState(0) {
        while (true) {
            delay(5_000)
            value += 1
        }
    }

    LaunchedEffect(Unit) { presenter.send(ProjectHubIntent.Refresh) }
    LaunchedEffect(state.createdProjectId) {
        val createdId = state.createdProjectId ?: return@LaunchedEffect
        presenter.send(ProjectHubIntent.ConsumeCreated)
        onProjectClick(createdId)
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Workspaces & Projects",
                style = AidosTypography.baseTypography.headlineSmall,
                color = Color.White,
            )
            Button(onClick = { presenter.send(ProjectHubIntent.OpenNewProject) }) {
                Text("New project")
            }
        }

        state.errorMessage?.let { message ->
            Text(
                text = message,
                style = AidosTypography.baseTypography.bodySmall,
                color = Color(0xFFF87171),
                modifier = Modifier.padding(vertical = SpacingTokens.sm),
            )
        }

        if (state.isLoading && state.projects.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ColorTokens.ActorAgent)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.md),
                contentPadding = PaddingValues(vertical = SpacingTokens.sm),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.projects, key = { it.summary.id }) { item ->
                    val isCurrent = item.summary.id == workspace.projectId

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B), shape = RoundedCornerShape(CornerTokens.md))
                            .border(
                                width = if (isCurrent) 2.dp else 1.dp,
                                color = if (isCurrent) ColorTokens.ActorAgent else Color(0x3394A3B8),
                                shape = RoundedCornerShape(CornerTokens.md),
                            )
                            .clickable { onProjectClick(item.summary.id) }
                            .padding(SpacingTokens.base)
                    ) {
                        Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text(
                                    text = item.summary.name,
                                    style = AidosTypography.baseTypography.titleMedium,
                                    color = Color.White,
                                )
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                ) {
                                    ProjectTypeBadge(domain = item.domain)
                                    if (onPinProjectClick != null) {
                                        Text(
                                            text = "📌 Pin",
                                            style = AidosTypography.baseTypography.labelSmall,
                                            color = ColorTokens.ActorAgent,
                                            modifier = Modifier
                                                .background(Color(0x2238BDF8), shape = RoundedCornerShape(CornerTokens.sm))
                                                .clickable {
                                                    onPinProjectClick(item.summary.id, item.summary.name)
                                                }
                                                .padding(horizontal = 6.dp, vertical = 2.dp),
                                        )
                                    }
                                }
                            }

                            if (item.summary.description.isNotBlank()) {
                                Text(
                                    text = item.summary.description,
                                    style = AidosTypography.baseTypography.bodySmall,
                                    color = Color(0xFF94A3B8),
                                )
                            }

                            key(countTick) {
                                val counts = controller.storeIfStarted(item.summary.id)?.snapshot?.value
                                    ?.let { ProjectHubPresenter.countsFor(it) }
                                val countText = counts?.let { "${it.openTasks} open tasks · ${it.needsMe} need you" } ?: "–"
                                Text(
                                    text = countText,
                                    style = AidosTypography.baseTypography.labelSmall,
                                    color = Color(0xFFCBD5E1),
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Text(
                                    text = "Last active " + formatLastActive(item.summary.lastActiveAt, Clock.System.now()),
                                    style = AidosTypography.baseTypography.labelSmall,
                                    color = Color(0xFF94A3B8),
                                )
                                if (isCurrent) {
                                    Text(
                                        text = "Current",
                                        style = AidosTypography.baseTypography.labelSmall,
                                        color = ColorTokens.ActorAgent,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    state.newProject?.let { draft ->
        NewProjectDialog(
            draft = draft,
            creating = state.creating,
            error = state.createError,
            onChange = { presenter.send(ProjectHubIntent.EditNewProject(it)) },
            onSubmit = { presenter.send(ProjectHubIntent.SubmitNewProject) },
            onDismiss = { presenter.send(ProjectHubIntent.CloseNewProject) },
        )
    }
}

@Composable
private fun NewProjectDialog(
    draft: NewProjectDraft,
    creating: Boolean,
    error: String?,
    onChange: (NewProjectDraft) -> Unit,
    onSubmit: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = { if (!creating) onDismiss() },
        title = { Text("New project") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm)) {
                OutlinedTextField(
                    value = draft.name,
                    onValueChange = { onChange(draft.copy(name = it)) },
                    label = { Text("Name (required)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = draft.description,
                    onValueChange = { onChange(draft.copy(description = it)) },
                    label = { Text("Description") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("Type", style = AidosTypography.baseTypography.labelLarge)
                PROJECT_TYPES.forEach { (value, label) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChange(draft.copy(type = value)) },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = draft.type == value, onClick = { onChange(draft.copy(type = value)) })
                        Text(label)
                    }
                }
                error?.let {
                    Text(
                        text = it,
                        style = AidosTypography.baseTypography.bodySmall,
                        color = Color(0xFFF87171),
                    )
                }
            }
        },
        confirmButton = {
            Button(onClick = onSubmit, enabled = !creating) {
                Text(if (creating) "Creating…" else "Create")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss, enabled = !creating) { Text("Cancel") }
        },
    )
}
