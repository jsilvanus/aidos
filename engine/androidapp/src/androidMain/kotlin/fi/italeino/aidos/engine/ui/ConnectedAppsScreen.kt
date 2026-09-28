package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Connected Apps screen (RFC-0103, Phase D).
 *
 * Lists every app that has attempted the Engine handshake, grouped by the user's decision, with
 * the actions that decision allows. Data comes from the persisted AppApprovalStore; the list
 * itself is the shared [ConnectedAppsPane].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectedAppsScreen(viewModel: ConnectedAppsViewModel = viewModel()) {
    val approvals by viewModel.approvals.collectAsState()
    val isEngineRunning by viewModel.isEngineRunning.collectAsState()

    LaunchedEffect(Unit) { viewModel.refresh() }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Connected Apps") })
        }
    ) { innerPadding ->
        ConnectedAppsPane(
            approvals = approvals,
            isEngineRunning = isEngineRunning,
            onApprove = viewModel::approveApp,
            onDeny = viewModel::denyApp,
            onRevoke = viewModel::revokeApproval,
            onUndoDeny = viewModel::undoDenyApp,
            modifier = Modifier
                .padding(innerPadding)
                .padding(16.dp),
        )
    }
}
