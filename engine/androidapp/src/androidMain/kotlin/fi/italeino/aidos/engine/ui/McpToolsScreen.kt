package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Android MCP Tools Screen (RFC-0031, RFC-0054).
 * Renders the shared [McpServersPane] from :engineui.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpToolsScreen(viewModel: McpToolsViewModel = viewModel()) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("MCP Servers & Tools") })
        }
    ) { innerPadding ->
        McpServersPane(
            state = state,
            onAddServer = viewModel::addServer,
            onDeleteServer = viewModel::deleteServer,
            onSelectServer = viewModel::selectServer,
            onToggleAdoption = viewModel::toggleAdoption,
            onAdoptAll = viewModel::adoptAll,
            onUnadoptAll = viewModel::unadoptAll,
            onRefreshCatalog = viewModel::refreshCatalog,
            onSearchQueryChange = viewModel::search,
            onTransportFilterChange = viewModel::setTransportFilter,
            onAdoptionFilterChange = viewModel::setAdoptionFilter,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
