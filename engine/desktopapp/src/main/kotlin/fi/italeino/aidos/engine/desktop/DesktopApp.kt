package fi.italeino.aidos.engine.desktop

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
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
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.CreateProjectRequest
import dev.aidos.api.ProjectLocation
import dev.aidos.api.RealRuntimeClient
import dev.aidos.api.RuntimeClient
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.AidosUniversalApp
import fi.italeino.aidos.engine.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

enum class DesktopRightPaneTab {
    MODELS,
    AGENTS,
    TOOLS,
}

enum class DesktopViewMode {
    WORKSPACE,
    SPLIT,
    ENGINE,
}

/** Everything the window binds to, created once per app run. */
class DesktopModels(
    val host: DesktopEngineHost,
    val prefs: DesktopPreferences,
    val scope: CoroutineScope,
    val runtimeClient: RuntimeClient = RealRuntimeClient(),
) {
    val status = DesktopStatusModel(host, scope)
    val apps = DesktopAppsModel(host, scope)
    val models = DesktopModelsModel(host, prefs, scope)
    val agents = DesktopAgentsModel(host, scope, prefs)
    val mcp = DesktopMcpModel(host, scope)

    init {
        scope.launch {
            try {
                runtimeClient.projects.create(
                    CreateProjectRequest(
                        name = "Universal Workspace",
                        description = "Causal Intent & Multi-Agent Project Hub",
                        location = ProjectLocation.RuntimeManaged("default-workspace"),
                    )
                )
                runtimeClient.intents.createNode(
                    CreateIntentNodeRequest(
                        projectId = "default-workspace",
                        title = "Explore Causal Outliner & Infinite Canvas",
                        type = IntentNodeType.GOAL,
                    )
                )
                runtimeClient.intents.createNode(
                    CreateIntentNodeRequest(
                        projectId = "default-workspace",
                        title = "Verify local Llama inference model handshake",
                        type = IntentNodeType.ATOMIC_TASK,
                    )
                )
            } catch (_: Exception) {
                // Ignore initialization collisions
            }
        }
    }
}

/**
 * Universal Desktop Application Root (RFC-0106, RFC-0111, RFC-0112, D48, D49).
 *
 * Unifies the Aidos Universal Workspace (Action Inbox, Outliner, Canvas, Living Doc, Projects)
 * with the Aidos Engine Host (Models, Providers, MCP Tools, Connected Apps).
 * Supports Workspace-only, Engine-only, and Side-by-Side Split views.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DesktopApp(models: DesktopModels) {
    var viewMode by remember { mutableStateOf(DesktopViewMode.SPLIT) }
    var showSettings by remember { mutableStateOf(false) }
    var showGroups by remember { mutableStateOf(false) }
    var rightPaneTab by remember { mutableStateOf(DesktopRightPaneTab.MODELS) }
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
                title = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = when (viewMode) {
                                DesktopViewMode.WORKSPACE -> "Aidos · Workspace"
                                DesktopViewMode.ENGINE -> "Aidos Engine · Runtime"
                                DesktopViewMode.SPLIT -> "Aidos · Workspace + Engine"
                            },
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(end = 8.dp),
                        )
                        FilterChip(
                            selected = viewMode == DesktopViewMode.WORKSPACE,
                            onClick = { viewMode = DesktopViewMode.WORKSPACE },
                            label = { Text("Workspace") },
                        )
                        FilterChip(
                            selected = viewMode == DesktopViewMode.SPLIT,
                            onClick = { viewMode = DesktopViewMode.SPLIT },
                            label = { Text("Split View") },
                        )
                        FilterChip(
                            selected = viewMode == DesktopViewMode.ENGINE,
                            onClick = { viewMode = DesktopViewMode.ENGINE },
                            label = { Text("Engine") },
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { showGroups = true }) {
                        Icon(Icons.Default.Share, contentDescription = "Device groups")
                    }
                    IconButton(onClick = { showSettings = true }) {
                        Icon(Icons.Default.Settings, contentDescription = "Settings")
                    }
                },
            )
        },
    ) { innerPadding ->
        when (viewMode) {
            DesktopViewMode.WORKSPACE -> {
                AidosUniversalApp(
                    runtimeClient = models.runtimeClient,
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            }
            DesktopViewMode.ENGINE -> {
                EngineHostView(
                    models = models,
                    selectedModelId = selectedModelId,
                    onSelectModelId = { selectedModelId = it },
                    rightPaneTab = rightPaneTab,
                    onSelectRightPaneTab = { rightPaneTab = it },
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                )
            }
            DesktopViewMode.SPLIT -> {
                Row(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                    Box(modifier = Modifier.weight(0.55f).fillMaxHeight()) {
                        AidosUniversalApp(
                            runtimeClient = models.runtimeClient,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                    VerticalDivider()
                    Box(modifier = Modifier.weight(0.45f).fillMaxHeight()) {
                        EngineHostView(
                            models = models,
                            selectedModelId = selectedModelId,
                            onSelectModelId = { selectedModelId = it },
                            rightPaneTab = rightPaneTab,
                            onSelectRightPaneTab = { rightPaneTab = it },
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
            }
        }
    }

    if (showSettings) {
        SettingsDialog(models.prefs, onClose = { showSettings = false })
    }
    if (showGroups) {
        GroupsDialog(models, onClose = { showGroups = false })
    }
}

@Composable
fun EngineHostView(
    models: DesktopModels,
    selectedModelId: String?,
    onSelectModelId: (String?) -> Unit,
    rightPaneTab: DesktopRightPaneTab,
    onSelectRightPaneTab: (DesktopRightPaneTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier = modifier) {
        Column(modifier = Modifier.weight(0.4f).fillMaxHeight()) {
            StatusColumn(models, Modifier.weight(0.55f))
            HorizontalDivider()
            AppsColumn(models, Modifier.weight(0.45f))
        }
        VerticalDivider()
        Column(modifier = Modifier.weight(0.6f).fillMaxHeight()) {
            val agentStats by models.agents.state.collectAsState()
            val mcpState by models.mcp.state.collectAsState()
            TabRow(selectedTabIndex = rightPaneTab.ordinal) {
                Tab(
                    selected = rightPaneTab == DesktopRightPaneTab.MODELS,
                    onClick = { onSelectRightPaneTab(DesktopRightPaneTab.MODELS) },
                    text = { Text("Models & Providers") }
                )
                Tab(
                    selected = rightPaneTab == DesktopRightPaneTab.AGENTS,
                    onClick = { onSelectRightPaneTab(DesktopRightPaneTab.AGENTS) },
                    text = {
                        val active = agentStats.stats.activeRuns
                        Text(if (active > 0) "Agents ($active)" else "Agents")
                    }
                )
                Tab(
                    selected = rightPaneTab == DesktopRightPaneTab.TOOLS,
                    onClick = { onSelectRightPaneTab(DesktopRightPaneTab.TOOLS) },
                    text = {
                        val serverCount = mcpState.servers.size
                        Text(if (serverCount > 0) "Tools ($serverCount)" else "Tools")
                    }
                )
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (rightPaneTab) {
                    DesktopRightPaneTab.MODELS -> {
                        val modelId = selectedModelId
                        if (modelId == null) {
                            ModelsColumn(models, onModelSelected = { onSelectModelId(it) })
                        } else {
                            DetailColumn(models, modelId, onBack = {
                                onSelectModelId(null)
                                models.models.refresh()
                            })
                        }
                    }
                    DesktopRightPaneTab.AGENTS -> {
                        AgentsPane(
                            state = agentStats,
                            onAppFilterSelected = { models.agents.selectAppFilter(it) },
                            onStatusFilterSelected = { models.agents.selectStatusFilter(it) },
                            onPlacementFilterSelected = { models.agents.selectPlacementFilter(it) },
                            onSearchQueryChanged = { models.agents.search(it) },
                            onResetFilters = { models.agents.resetFilters() },
                            onViewLayoutSelected = { models.agents.selectViewLayout(it) },
                            onCancelRun = { models.agents.cancelRun(it) },
                            onInspectRun = { models.agents.selectRunDetail(it) },
                            onMainModelChange = { models.agents.setMainModel(it) },
                            onDefaultRunModelChange = { models.agents.setDefaultRunModel(it) },
                            onProjectLocalityChange = { models.agents.setProjectLocality(it) },
                            onProjectGuardianPolicyChange = { models.agents.setProjectGuardianPolicy(it) },
                            onDecisionModelChange = { models.agents.setDecisionModel(it) },
                            onGuardianLlmChange = { models.agents.setGuardianLlm(it) },
                            onRunGuardianPolicyOverride = { models.agents.setRunGuardianPolicyOverride(it) },
                            onRunModelOverride = { models.agents.setRunModelOverride(it) },
                            onRunLocalityOverride = { models.agents.setRunLocalityOverride(it) },
                        )
                    }
                    DesktopRightPaneTab.TOOLS -> {
                        McpServersPane(
                            state = mcpState,
                            onAddServer = { models.mcp.addServer(it) },
                            onDeleteServer = { models.mcp.deleteServer(it) },
                            onSelectServer = { models.mcp.selectServer(it) },
                            onToggleAdoption = { serverId, toolName, shouldAdopt ->
                                models.mcp.toggleAdoption(serverId, toolName, shouldAdopt)
                            },
                            onAdoptAll = { models.mcp.adoptAll(it) },
                            onUnadoptAll = { models.mcp.unadoptAll(it) },
                            onRefreshCatalog = { models.mcp.refreshCatalog(it) },
                            onSearchQueryChange = { models.mcp.search(it) },
                            onTransportFilterChange = { models.mcp.setTransportFilter(it) },
                            onAdoptionFilterChange = { models.mcp.setAdoptionFilter(it) },
                        )
                    }
                }
            }
        }
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
            onSetAgentAllowed = models.apps::setAgentAllowed,
            onSetLocalityLimit = models.apps::setLocalityLimit,
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
        ModelsPane(state = state, actions = actions)
    }
}

@Composable
private fun DetailColumn(models: DesktopModels, modelId: String, onBack: () -> Unit) {
    val detail by models.models.detailFor(modelId).collectAsState()
    val currentDetail = detail
    val runSettings = models.prefs.runSettingsFor(modelId)
    val (deviceTotalRamBytes, deviceFreeRamBytes) = remember {
        DesktopDevice.totalMemoryBytes() to (DesktopDevice.totalMemoryBytes() - DesktopDevice.memory().usedMB * 1024L * 1024L)
    }
    val memoryBudget = remember { DesktopDevice.memory() }
    val freeStorageGb = remember { DesktopDevice.freeStorageGb() }

    val modelActions = remember(models, modelId) {
        ModelDetailActions(
            onLoad = { models.models.load(modelId) },
            onUnload = { models.models.unload(modelId) },
            onDelete = {
                models.models.deleteModel(modelId)
                onBack()
            },
            onSaveRunSettings = { next -> models.prefs.setRunSettings(modelId, next) },
            onResetRunSettings = { models.prefs.setRunSettings(modelId, dev.aidos.modelruntime.ModelRunSettings.DEFAULT) },
        )
    }

    if (currentDetail != null) {
        ModelDetailPane(
            detail = currentDetail,
            runSettings = runSettings,
            deviceTotalRamBytes = deviceTotalRamBytes,
            deviceFreeRamBytes = deviceFreeRamBytes,
            memoryBudget = memoryBudget,
            freeStorageGb = freeStorageGb,
            actions = modelActions,
            onBack = onBack,
        )
    } else {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("Model not found")
                Spacer(modifier = Modifier.height(8.dp))
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        }
    }
}

@Composable
private fun SettingsDialog(prefs: DesktopPreferences, onClose: () -> Unit) {
    val state = rememberDialogState(size = DpSize(700.dp, 800.dp))
    DialogWindow(onCloseRequest = onClose, state = state, title = "Settings") {
        AidosEngineTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                val data by prefs.data.collectAsState()
                SettingsContent(
                    data = data,
                    onSaveHfToken = prefs::saveHfToken,
                    onClearHfToken = prefs::clearHfToken,
                    onRestoreSuggestions = prefs::restoreSuggestions,
                    onSetPreferredContextLength = prefs::setPreferredContextLength,
                    onSetServerPort = prefs::setServerPort,
                    onSetAutoStartEngine = prefs::setAutoStartEngine,
                    onSetLocalityLimit = { prefs.setEngineLocalityLimit(it?.wire) },
                    onSetGlobalMainModel = prefs::setGlobalMainModelId,
                    onSetGlobalDefaultRunModel = prefs::setGlobalDefaultRunModelId,
                    onSetGlobalLocality = prefs::setGlobalLocality,
                    onSetGlobalGuardianPolicy = prefs::setGlobalGuardianPolicy,
                    onSetGlobalDecisionModel = prefs::setGlobalDecisionModelId,
                    onSetGlobalGuardianLlm = prefs::setGlobalGuardianLlmModelId,
                    onSetProjectMainModel = prefs::setProjectMainModelId,
                    onSetProjectDefaultRunModel = prefs::setProjectDefaultRunModelId,
                    onSetProjectAllowMainModelRunOverride = prefs::setProjectAllowMainModelRunOverride,
                    onSetProjectEnforcedLocality = prefs::setProjectEnforcedLocality,
                    onSetProjectGuardianPolicy = prefs::setProjectGuardianPolicy,
                    onSetProjectDecisionModel = prefs::setProjectDecisionModelId,
                    onSetProjectGuardianLlm = prefs::setProjectGuardianLlmModelId,
                    onSetRunGuardianPolicyOverride = prefs::setRunGuardianPolicyOverride,
                    onSetRunModelOverride = prefs::setRunModelOverride,
                    onSetRunLocalityOverride = prefs::setRunLocalityOverride,
                    onOpenDiscoveryFolder = {
                        try {
                            Desktop.getDesktop().open(DesktopPaths.discoveryFile.parentFile)
                        } catch (_: Exception) {
                        }
                    },
                    onOpenModelsFolder = {
                        try {
                            Desktop.getDesktop().open(DesktopPaths.modelsDir)
                        } catch (_: Exception) {
                        }
                    },
                    onOpenUrl = { url ->
                        try {
                            Desktop.getDesktop().browse(URI(url))
                        } catch (_: Exception) {
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun GroupsDialog(models: DesktopModels, onClose: () -> Unit) {
    val state = rememberDialogState(size = DpSize(700.dp, 800.dp))
    DialogWindow(onCloseRequest = onClose, state = state, title = "Device Groups (RFC-0105)") {
        AidosEngineTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                val groupSharingEnabled by models.host.groupSharingEnabled.collectAsState()
                GroupsContent(
                    groupSharingEnabled = groupSharingEnabled,
                    onToggleGroupSharing = { enabled ->
                        models.prefs.setGroupSharingEnabled(enabled)
                        models.host.setGroupSharingEnabled(enabled)
                    },
                )
            }
        }
    }
}
