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
import dev.aidos.api.RuntimeClient
import dev.aidos.ui.AidosUniversalApp
import dev.aidos.tcr.engine.BackgroundPane
import dev.aidos.tcr.engine.EngineControlCard
import dev.aidos.tcr.engine.LanSharingPane
import dev.aidos.tcr.engine.RemotePeersActions
import dev.aidos.tcr.engine.RemotePeersPane
import dev.aidos.tcr.engine.ShellGrantsPane
import dev.aidos.tcr.engine.SshTunnelsActions
import dev.aidos.tcr.engine.SshTunnelsPane
import fi.italeino.aidos.engine.ui.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.net.URI

enum class DesktopRightPaneTab {
    MODELS,
    AGENTS,
    TOOLS,
    SHARING,
    PEERS,
    SHELL,
    TUNNELS,
    BACKGROUND,
}

enum class DesktopViewMode {
    WORKSPACE,
    SPLIT,
    ENGINE,
}

/**
 * Everything the window binds to, created once per app run.
 *
 * [agentConnection] is the one Agent runtime this window uses (RFC-0115, D60): the running daemon when there is one,
 * else the app's own database-backed runtime. Every view, including Shell grants, shares it, so the app opens one
 * connection, not two writers on the same SQLite files.
 */
class DesktopModels(
    val host: DesktopEngineHost,
    val prefs: DesktopPreferences,
    val scope: CoroutineScope,
    val agentConnection: AgentConnection = AgentRuntimeAttach.connect(),
) {
    val runtimeClient: RuntimeClient get() = agentConnection.client

    val status = DesktopStatusModel(host, scope, status = { EngineAttach.statusControl(host) }, apps = { EngineAttach.appsControl(host) })
    val apps = DesktopAppsModel(host, scope, control = { EngineAttach.appsControl(host) })
    val models = DesktopModelsModel(host, prefs, scope, control = { EngineAttach.modelsControl(host) }, engineState = status.engineState)
    val agents = DesktopAgentsModel(host, scope, prefs, control = { EngineAttach.agentsControl(host) }, models = { EngineAttach.modelsControl(host) })
    val mcp = DesktopMcpModel(host, scope)
    val lan = DesktopLanSharingModel(scope) { EngineAttach.lanControl(host) }
    val peers = DesktopRemotePeersModel(scope, peers = { EngineAttach.peersControl(host) }, approvals = { EngineAttach.approvalsControl(host) })
    val shell = DesktopShellGrantsModel(scope) { agentConnection }
    val tunnels = DesktopTunnelsModel(scope) { EngineAttach.tunnelsControl(host) }
    val background = DesktopBackgroundModel(scope)
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
                    // Says which Agent runtime this window is using, so a person can tell the daemon from the app's own runtime.
                    Text(
                        text = if (models.agentConnection is AgentConnection.Attached) "Agent: daemon" else "Agent: in-app",
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 8.dp),
                    )
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
                Tab(
                    selected = rightPaneTab == DesktopRightPaneTab.SHARING,
                    onClick = { onSelectRightPaneTab(DesktopRightPaneTab.SHARING) },
                    text = { Text("Sharing") }
                )
                Tab(
                    selected = rightPaneTab == DesktopRightPaneTab.PEERS,
                    onClick = { onSelectRightPaneTab(DesktopRightPaneTab.PEERS) },
                    text = {
                        val waiting = models.peers.state.collectAsState().value.pendingApprovals.size
                        Text(if (waiting > 0) "Peers ($waiting)" else "Peers")
                    }
                )
                Tab(
                    selected = rightPaneTab == DesktopRightPaneTab.SHELL,
                    onClick = { onSelectRightPaneTab(DesktopRightPaneTab.SHELL); models.shell.refresh() },
                    text = { Text("Shell") }
                )
                Tab(
                    selected = rightPaneTab == DesktopRightPaneTab.TUNNELS,
                    onClick = { onSelectRightPaneTab(DesktopRightPaneTab.TUNNELS) },
                    text = { Text("Tunnels") }
                )
                Tab(
                    selected = rightPaneTab == DesktopRightPaneTab.BACKGROUND,
                    onClick = { onSelectRightPaneTab(DesktopRightPaneTab.BACKGROUND); models.background.refresh() },
                    text = { Text("Background") }
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
                    DesktopRightPaneTab.SHARING -> {
                        val lanState by models.lan.state.collectAsState()
                        LanSharingPane(
                            state = lanState,
                            onSetEnabled = models.lan::setEnabled,
                            onAddByCode = models.lan::addByCode,
                            onAddManually = models.lan::addManually,
                            onRevoke = models.lan::revoke,
                            onDismissPairing = models.lan::dismissPairing,
                            qrCode = { link -> QrCode(link) },
                        )
                    }
                    DesktopRightPaneTab.PEERS -> {
                        val peersState by models.peers.state.collectAsState()
                        val actions = remember(models) {
                            RemotePeersActions(
                                onSetHostsAgentLoops = models.peers::setHostsAgentLoops,
                                onSetPreferred = models.peers::setPreferred,
                                onSetRemoteAccess = models.peers::setRemoteAccess,
                                onAddHint = models.peers::addHint,
                                onRemoveHint = models.peers::removeHint,
                                onSetToolAllowlist = models.peers::setToolAllowlist,
                                onDecideToolApproval = models.peers::decideToolApproval,
                            )
                        }
                        RemotePeersPane(state = peersState, actions = actions)
                    }
                    DesktopRightPaneTab.BACKGROUND -> {
                        val bgState by models.background.state.collectAsState()
                        BackgroundPane(state = bgState, onSetEnabled = models.background::setEnabled)
                    }
                    DesktopRightPaneTab.TUNNELS -> {
                        val tunnelState by models.tunnels.state.collectAsState()
                        val actions = remember(models) {
                            SshTunnelsActions(
                                onCreate = models.tunnels::create,
                                onProbeHostKey = models.tunnels::probeHostKey,
                                onConfirmHostKey = models.tunnels::confirmHostKey,
                                onStart = models.tunnels::start,
                                onStop = models.tunnels::stop,
                                onRotateKey = models.tunnels::rotateKey,
                                onDelete = models.tunnels::delete,
                            )
                        }
                        SshTunnelsPane(state = tunnelState, actions = actions)
                    }
                    DesktopRightPaneTab.SHELL -> {
                        val shellState by models.shell.state.collectAsState()
                        ShellGrantsPane(
                            state = shellState,
                            onSelectProject = models.shell::selectProject,
                            onSelectSession = models.shell::selectSession,
                            onGrant = models.shell::grant,
                            onRevoke = models.shell::revoke,
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
                val notice by models.status.notice.collectAsState()
                notice?.let {
                    Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 6.dp))
                }
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
    val isRunning by models.status.isRunning.collectAsState()
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
        Text("Models", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(16.dp, 12.dp, 16.dp, 4.dp))
        ModelsTabs(state = state, actions = actions)
    }
}

@Composable
private fun DetailColumn(models: DesktopModels, modelId: String, onBack: () -> Unit) {
    val detail = remember(modelId) { DesktopModelDetailModel(modelId, models.scope, { EngineAttach.modelDetailControl(models.host) }, { EngineAttach.modelsControl(models.host) }) }
    DisposableEffect(detail) { onDispose { detail.dispose() } }

    val state by detail.state.collectAsState()
    val loading by detail.loading.collectAsState()
    val engineState by models.status.engineState.collectAsState()
    val prefs by models.prefs.data.collectAsState()
    val scope = rememberCoroutineScope()
    var pendingLicenseConfirm by remember { mutableStateOf(false) }

    if (pendingLicenseConfirm) {
        val licenseName = state.model?.licenseName.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingLicenseConfirm = false },
            title = { Text("Accept Model License") },
            text = {
                Text(
                    "Downloading and running ${state.model?.name ?: modelId} requires accepting its license terms: $licenseName",
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        pendingLicenseConfirm = false
                        scope.launch {
                            detail.acceptLicenseNow()
                            detail.startDownload()
                        }
                    },
                ) {
                    Text("Accept & Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingLicenseConfirm = false }) {
                    Text("Cancel")
                }
            },
        )
    }

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
                onStartDownload = {
                    if (detail.state.value.model?.licenseName.isNullOrBlank()) {
                        detail.startDownload()
                    } else {
                        scope.launch {
                            if (detail.isLicenseAccepted()) {
                                detail.startDownload()
                            } else {
                                pendingLicenseConfirm = true
                            }
                        }
                    }
                },
                onCancelDownload = detail::cancelDownload,
                onDelete = detail::delete,
                onEmbeddingInputChange = detail::setEmbeddingInput,
                onTestEmbeddings = detail::testEmbeddings,
                onToggleLoad = detail::toggleLoad,
                onTestChat = null,
                onRunSelfTest = detail::runSelfTest,
                onSelfTestTextChange = detail::setSelfTestText,
                onSelfTestFileChange = detail::setSelfTestFile,
                onRunSettingsChange = detail::setRunSettings,
                onReload = detail::reload,
            ),
        )
    }
}

@Composable
private fun SettingsDialog(prefs: DesktopPreferences, onClose: () -> Unit) {
    val state = rememberDialogState(size = DpSize(700.dp, 800.dp))
    DialogWindow(onCloseRequest = onClose, state = state, title = "Settings") {
        MaterialTheme {
            Surface(modifier = Modifier.fillMaxSize()) {
                val data by prefs.data.collectAsState()
                var ramNow by remember { mutableStateOf(DesktopDevice.memory()) }
                var settingsState by remember {
                    mutableStateOf(
                        SettingsState(
                            hfTokenStatus = HfTokenStatus(
                                isConfigured = !data.hfToken.isNullOrBlank(),
                                lastValidatedMs = data.hfTokenValidatedMs,
                            )
                        )
                    )
                }
                SelectionContainer {
                    SettingsContent(
                        state = settingsState,
                        dismissedSuggestionCount = data.dismissedSuggestions.size,
                        onStateChange = { settingsState = it },
                        onSaveToken = prefs::saveHfToken,
                        onClearToken = prefs::clearHfToken,
                        onRestoreSuggestions = prefs::restoreSuggestions,
                        ramBaseline = RamBaselineState(
                            baselineUsedMB = data.ramBaselineUsedMb,
                            currentUsedMB = ramNow.usedMB,
                            totalMB = ramNow.totalMB,
                        ),
                        onSetRamBaseline = prefs::setRamBaselineUsedMb,
                        onRefreshRamBaseline = { ramNow = DesktopDevice.memory() },
                        engineLocalityLimit = data.engineLocalityLimit,
                        onSetEngineLocalityLimit = prefs::setEngineLocalityLimit,
                        globalMainModelId = data.globalMainModelId,
                        globalDefaultRunModelId = data.globalDefaultRunModelId,
                        globalLocality = when (data.globalLocality.lowercase()) {
                            "local_only" -> dev.aidos.tcr.agent.AgentLocality.LOCAL_ONLY
                            "trusted_cluster" -> dev.aidos.tcr.agent.AgentLocality.TRUSTED_CLUSTER
                            else -> dev.aidos.tcr.agent.AgentLocality.ANY
                        },
                        onGlobalMainModelChange = prefs::setGlobalMainModelId,
                        onGlobalDefaultRunModelChange = prefs::setGlobalDefaultRunModelId,
                        onGlobalLocalityChange = { prefs.setGlobalLocality(it.name.lowercase()) },
                        onGlobalGuardianPolicyChange = { prefs.setGlobalGuardianPolicy(it.name) },
                        onGlobalDecisionModelChange = prefs::setGlobalDecisionModelId,
                        onGlobalGuardianLlmChange = prefs::setGlobalGuardianLlmModelId,
                        tokenStorageNote = "Debug build: stored unencrypted in ${DesktopPaths.stateDir}.",
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(16.dp),
                    )
                }
            }
        }
    }
}

private fun openInBrowser(url: String) {
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        Desktop.getDesktop().browse(URI(url))
    }
}
