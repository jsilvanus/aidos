package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Android Agents Screen (RFC-0103, Phase D / D49).
 * Renders the shared [AgentsPane] from :engineui.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    onBack: (() -> Unit)? = null,
    viewModel: AgentsViewModel = viewModel(),
) {
    val state by viewModel.state.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agent Runtime") },
                navigationIcon = {
                    if (onBack != null) {
                        IconButton(onClick = onBack) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                        }
                    }
                },
            )
        }
    ) { innerPadding ->
        AgentsPane(
            state = state,
            onAppFilterSelected = viewModel::selectAppFilter,
            onStatusFilterSelected = viewModel::selectStatusFilter,
            onPlacementFilterSelected = viewModel::selectPlacementFilter,
            onSearchQueryChanged = viewModel::search,
            onResetFilters = viewModel::resetFilters,
            onViewLayoutSelected = viewModel::selectViewLayout,
            onCancelRun = viewModel::cancelRun,
            onInspectRun = viewModel::selectRunDetail,
            onMainModelChange = viewModel::setMainModel,
            onDefaultRunModelChange = viewModel::setDefaultRunModel,
            onProjectLocalityChange = viewModel::setProjectLocality,
            onProjectGuardianPolicyChange = viewModel::setProjectGuardianPolicy,
            onDecisionModelChange = viewModel::setDecisionModel,
            onGuardianLlmChange = viewModel::setGuardianLlm,
            onRunGuardianPolicyOverride = viewModel::setRunGuardianPolicyOverride,
            onRunModelOverride = viewModel::setRunModelOverride,
            onRunLocalityOverride = viewModel::setRunLocalityOverride,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
