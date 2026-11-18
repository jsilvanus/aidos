package dev.aidos.ui.frontdoors.inbox

import dev.aidos.ui.layout.FormFactor

import androidx.compose.foundation.background
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController

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

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(ColorTokens.SurfaceDark)
            .padding(SpacingTokens.base)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(bottom = SpacingTokens.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "Action Inbox",
                style = AidosTypography.baseTypography.headlineSmall,
                color = Color.White,
            )

            if (state.pendingApprovalsCount > 0) {
                Text(
                    text = "${state.pendingApprovalsCount} pending",
                    style = AidosTypography.baseTypography.labelMedium,
                    color = ColorTokens.StatusReview,
                )
            }
        }

        if (state.isLoading && state.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ColorTokens.ActorAgent)
            }
        } else if (state.items.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "No pending action items. You're completely unblocked!",
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
                items(state.items, key = { it.node.id }) { item ->
                    ActionItemCard(
                        item = item,
                        onComplete = { nodeId ->
                            presenter.send(ActionInboxIntent.CompleteItem(item.node.projectId, nodeId))
                        },
                        onClick = { onItemClick(item) },
                    )
                }
            }
        }
    }
}
