package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

/**
 * Models screen (RFC-0103, Phase D). The tabs themselves are the shared [ModelsTabs]; this binds
 * them to [ModelsViewModel] and adds the app bar and error snackbar.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelsScreen(
    onModelSelected: (String) -> Unit,
    viewModel: ModelsViewModel = viewModel(),
) {
    val errorMessage by viewModel.errorMessage.collectAsStateWithLifecycle()
    val engineState by viewModel.engineState.collectAsStateWithLifecycle()
    val localModels by viewModel.localModels.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val cookbookModels by viewModel.cookbookModels.collectAsState()
    val isSearching by viewModel.isSearching.collectAsState()
    val suggestions by viewModel.suggestions.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(
                message = it,
                actionLabel = "Dismiss",
                duration = SnackbarDuration.Long
            )
            viewModel.clearError()
        }
    }

    val actions = remember(viewModel, onModelSelected) {
        ModelsPaneActions(
            onModelSelected = onModelSelected,
            onDeleteModel = viewModel::deleteModel,
            onSearch = viewModel::searchRemote,
            onInstallSuggestion = viewModel::installSuggestion,
            onDismissSuggestion = { viewModel.dismissSuggestion(it) },
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = { TopAppBar(title = { Text("Models") }) }
    ) { innerPadding ->
        ModelsTabs(
            state = ModelsPaneState(
                engineState = engineState,
                localModels = localModels,
                isLoading = isLoading,
                cookbookModels = cookbookModels,
                isSearching = isSearching,
                suggestions = suggestions,
            ),
            actions = actions,
            modifier = Modifier.padding(innerPadding),
        )
    }
}
