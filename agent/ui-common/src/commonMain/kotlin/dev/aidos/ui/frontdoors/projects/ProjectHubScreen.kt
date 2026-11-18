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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
    ) {
        Text(
            text = "Workspaces & Projects",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

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
                    val isSelected = item.summary.id == state.activeProjectId

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1E293B), shape = RoundedCornerShape(CornerTokens.md))
                            .border(
                                width = if (isSelected) 2.dp else 1.dp,
                                color = if (isSelected) ColorTokens.ActorAgent else Color(0x3394A3B8),
                                shape = RoundedCornerShape(CornerTokens.md),
                            )
                            .clickable {
                                presenter.send(ProjectHubIntent.SelectProject(item.summary.id))
                                onProjectClick(item.summary.id)
                            }
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
                        }
                    }
                }
            }
        }
    }
}
