package fi.italeino.aidos.engine.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.rememberDialogState
import fi.italeino.aidos.engine.ui.*
import kotlinx.coroutines.CoroutineScope
import java.awt.Desktop
import java.net.URI

/** Everything the window binds to, created once per app run. */
class DesktopModels(val host: DesktopEngineHost, val prefs: DesktopPreferences, val scope: CoroutineScope) {
    val status = DesktopStatusModel(host, scope)
    val apps = DesktopAppsModel(host, scope)
    val models = DesktopModelsModel(host, prefs, scope)
}

/**
 * One-window layout: Home (status) above Connected Apps on the left, the Models tabs (or a model's
 * detail) on the right, Settings in a dialog. All panes are the shared :engineui ones.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopApp(models: DesktopModels) {
    var showSettings by remember { mutableStateOf(false) }
    var selectedModelId by remember { mutableStateOf<String?>(null) }
    val errorMessage by models.models.errorMessage.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(errorMessage) {
        errorMessage?.let {
            snackbarHostState.showSnackbar(message = it, actionLabel = "Dismiss", duration = SnackbarDuration.Long)
            models.models.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Aidos Engine · debug") },
                actions = {
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            Column(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
                StatusColumn(models, Modifier.weight(0.55f))
                HorizontalDivider()
                AppsColumn(models, Modifier.weight(0.45f))
            }
            VerticalDivider()
            Box(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
                val modelId = selectedModelId
                if (modelId == null) {
                    ModelsColumn(models, onModelSelected = { selectedModelId = it })
                } else {
                    DetailColumn(models, modelId, onBack = {
                        selectedModelId = null
                        models.models.refresh()
                    })
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(models.prefs, onClose = { showSettings = false })
    }
}

@Composable
private fun StatusColumn(models: DesktopModels, modifier: Modifier) {
    val state by models.status.state.collectAsState()
    val lastError by models.host.lastError.collectAsState()
    StatusPane(
        state = state,
        engineControl = {
            Column {
                EngineControlCard(
                    isEngineRunning = state.isEngineRunning,
                    engineStateName = state.engineStateName,
                    hint = if (state.isEngineRunning) "Click to turn off" else "Click to turn on",
                    modifier = Modifier.clickable { models.status.toggleEngine() },
                )
                lastError?.let {
                    Text("Start failed: $it", fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                }
            }
        },
        modifier = modifier,
    )
}

@Composable
private fun AppsColumn(models: DesktopModels, modifier: Modifier) {
    val approvals by models.apps.approvals.collectAsState()
    val isRunning by models.host.isRunning.collectAsState()
    var simulatedName by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
        Text("Connected Apps", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 12.dp))
        // No Binder on Windows: this stands in for an app's handshake (see DesktopAppsModel).
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        ) {
            OutlinedTextField(
                value = simulatedName,
                onValueChange = { simulatedName = it },
                label = { Text("App name") },
                singleLine = true,
                enabled = isRunning,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = {
                    models.apps.simulateHandshake(simulatedName)
                    simulatedName = ""
                },
                enabled = isRunning,
            ) { Text("Simulate handshake") }
        }
        ConnectedAppsPane(
            approvals = approvals,
            isEngineRunning = isRunning,
            onApprove = models.apps::approve,
            onDeny = models.apps::deny,
            onRevoke = models.apps::revoke,
            onUndoDeny = models.apps::undoDeny,
            notRunningText = "The Engine is not running. Start it above to manage app access.",
        )
    }
}

@Composable
private fun ModelsColumn(models: DesktopModels, onModelSelected: (String) -> Unit) {
    val state by models.models.state.collectAsState()
    val actions = remember(models, onModelSelected) {
        ModelsPaneActions(
            onModelSelected = onModelSelected,
            onDeleteModel = models.models::deleteModel,
            onSearch = models.models::searchRemote,
            onInstallSuggestion = models.models::installSuggestion,
            onDismissSuggestion = { models.models.dismissSuggestion(it) },
        )
    }
    Column {
        Text("Models", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
        ModelsTabs(state = state, actions = actions)
    }
}

@Composable
private fun DetailColumn(models: DesktopModels, modelId: String, onBack: () -> Unit) {
    val detail = remember(modelId) { DesktopModelDetailModel(modelId, models.host, models.scope) }
    DisposableEffect(detail) { onDispose { detail.dispose() } }

    val state by detail.state.collectAsState()
    val loading by detail.loading.collectAsState()
    val engineState by models.host.state.collectAsState()
    val prefs by models.prefs.data.collectAsState()

    Column {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            Text(state.model?.name ?: modelId, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        ModelDetailContent(
            state = state,
            loadingState = loading,
            engineState = engineState,
            preferredContext = prefs.preferredContextLength.coerceIn(512, 32768),
            actions = ModelDetailActions(
                onPreferredContextChange = models.prefs::setPreferredContextLength,
                onOpenUrl = ::openInBrowser,
                onStartDownload = detail::startDownload,
                onCancelDownload = detail::cancelDownload,
                onDelete = detail::delete,
                onEmbeddingInputChange = detail::setEmbeddingInput,
                onTestEmbeddings = detail::testEmbeddings,
                onToggleLoad = detail::toggleLoad,
                // Test Chat is out of scope for the first desktop version.
                onTestChat = null,
            ),
        )
    }
}

@Composable
private fun SettingsDialog(prefs: DesktopPreferences, onClose: () -> Unit) {
    val data by prefs.data.collectAsState()
    var state by remember {
        mutableStateOf(
            SettingsState(
                hfTokenStatus = HfTokenStatus(
                    isConfigured = !prefs.data.value.hfToken.isNullOrBlank(),
                    lastValidatedMs = prefs.data.value.hfTokenValidatedMs,
                )
            )
        )
    }
    DialogWindow(
        onCloseRequest = onClose,
        title = "Settings",
        state = rememberDialogState(size = DpSize(520.dp, 560.dp)),
    ) {
        Surface(modifier = Modifier.fillMaxSize()) {
            SettingsContent(
                state = state,
                dismissedSuggestionCount = data.dismissedSuggestions.size,
                onStateChange = { state = it },
                onSaveToken = prefs::saveHfToken,
                onClearToken = prefs::clearHfToken,
                onRestoreSuggestions = prefs::restoreSuggestions,
                tokenStorageNote = "Debug build: stored unencrypted in ${DesktopPaths.stateDir}.",
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            )
        }
    }
}

private fun openInBrowser(url: String) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url))
    }
}
