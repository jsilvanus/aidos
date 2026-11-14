package dev.aidos.ui.paradigms.outliner

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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

@Composable
fun CausalOutlinerScreen(
    presenter: CausalOutlinerPresenter,
    onDrillDown: (String) -> Unit,
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
            text = "Causal Outliner & Action Horizon",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        if (state.isLoading && state.visibleRows.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = ColorTokens.ActorAgent)
            }
        } else {
            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
                contentPadding = PaddingValues(vertical = SpacingTokens.xs),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(state.visibleRows, key = { it.node.id }) { row ->
                    OutlinerNodeRow(
                        item = row,
                        onToggleExpand = { nodeId -> presenter.send(CausalOutlinerIntent.ToggleExpand(nodeId)) },
                        onNodeClick = { nodeId ->
                            presenter.send(CausalOutlinerIntent.SelectNode(nodeId))
                            onDrillDown(nodeId)
                        },
                        onCausalJump = { prereqId -> presenter.send(CausalOutlinerIntent.JumpToPrerequisite(prereqId)) },
                    )
                }
            }
        }
    }
}
