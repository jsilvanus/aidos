package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Android Agents Screen (RFC-0103, Phase D / D49).
 * Renders the shared [AgentsPane] from :engineui.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(viewModel: AgentsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Agent Runtime") })
        }
    ) { innerPadding ->
        AgentsPane(
            state = state,
            onAppFilterSelected = viewModel::selectAppFilter,
            onStatusFilterSelected = viewModel::selectStatusFilter,
            onCancelRun = viewModel::cancelRun,
            onInspectRun = viewModel::selectRunDetail,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
