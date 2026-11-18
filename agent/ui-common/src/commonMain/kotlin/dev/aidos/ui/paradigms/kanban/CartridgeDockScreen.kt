package dev.aidos.ui.paradigms.kanban

import dev.aidos.ui.layout.FormFactor

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceController

@Composable
@Suppress("UNUSED_PARAMETER")
fun CartridgeDockScreen(
    presenter: CartridgeDockPresenter,
    onTaskClick: (String) -> Unit,
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
            text = "Modular Cartridge Dock (Kanban Matrix)",
            style = AidosTypography.baseTypography.headlineSmall,
            color = Color.White,
            modifier = Modifier.padding(bottom = SpacingTokens.md),
        )

        Row(
            modifier = Modifier
                .fillMaxSize()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(SpacingTokens.base),
        ) {
            state.columns.forEach { column ->
                Column(
                    modifier = Modifier
                        .width(280.dp)
                        .fillMaxHeight()
                        .background(Color(0xFF0F172A))
                        .padding(SpacingTokens.sm),
                    verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                ) {
                    Text(
                        text = "${column.title} (${column.tasks.size})",
                        style = AidosTypography.baseTypography.titleSmall,
                        color = Color(0xFF94A3B8),
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
                    ) {
                        column.tasks.forEach { (task, status) ->
                            TaskCartridgeView(
                                node = task,
                                status = status,
                                onClick = { onTaskClick(task.id) },
                            )
                        }
                    }
                }
            }
        }
    }
}
