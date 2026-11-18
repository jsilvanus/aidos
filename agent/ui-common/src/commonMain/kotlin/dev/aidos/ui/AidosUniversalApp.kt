package dev.aidos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.RuntimeClient
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.assistant.AssistantSheetOverlay
import dev.aidos.ui.assistant.SttBackend
import dev.aidos.ui.assistant.VoiceCaptureController
import dev.aidos.ui.assistant.VoiceCaptureState
import dev.aidos.ui.dex.DexStatePreserver
import dev.aidos.ui.frontdoors.gates.ApprovalGatePresenter
import dev.aidos.ui.frontdoors.gates.ApprovalGatesScreen
import dev.aidos.ui.frontdoors.inbox.ActionInboxPresenter
import dev.aidos.ui.frontdoors.inbox.ActionInboxScreen
import dev.aidos.ui.frontdoors.projects.ProjectHubPresenter
import dev.aidos.ui.frontdoors.projects.ProjectHubScreen
import dev.aidos.ui.inspector.NodeInspector
import dev.aidos.ui.inspector.NodeInspectorPresenter
import dev.aidos.ui.layout.NavigationDestination
import dev.aidos.ui.layout.ViewModeCatalog
import dev.aidos.ui.layout.ResponsiveScaffold
import dev.aidos.ui.palette.CommandItemKind
import dev.aidos.ui.palette.CommandPaletteModal
import dev.aidos.ui.palette.CommandPalettePresenter
import dev.aidos.ui.palette.PaletteItem
import dev.aidos.ui.paradigms.kanban.CartridgeDockPresenter
import dev.aidos.ui.paradigms.kanban.CartridgeDockScreen
import dev.aidos.ui.paradigms.livingdoc.LivingDocPresenter
import dev.aidos.ui.paradigms.livingdoc.LivingDocView
import dev.aidos.ui.paradigms.outliner.CausalOutlinerPresenter
import dev.aidos.ui.paradigms.outliner.CausalOutlinerScreen
import dev.aidos.ui.paradigms.pipeline.RelayPipelinePresenter
import dev.aidos.ui.paradigms.pipeline.RelayPipelineScreen
import dev.aidos.ui.paradigms.timeline.TimelineGanttPresenter
import dev.aidos.ui.paradigms.timeline.TimelineGanttScreen
import dev.aidos.ui.pills.AgentPillState
import dev.aidos.ui.pills.MultiAgentPillOverlay
import dev.aidos.ui.pills.PillRole
import dev.aidos.ui.spatial.canvas.InfiniteZuiCanvas
import dev.aidos.ui.spatial.canvas.SpatialCanvasPresenter
import dev.aidos.ui.spatial.radar.FocusRadarPresenter
import dev.aidos.ui.spatial.radar.RadialRadarScreen
import dev.aidos.ui.spatial.schematic.BreadboardSchematicPresenter
import dev.aidos.ui.spatial.schematic.BreadboardSchematicView
import dev.aidos.ui.spatial.schematic.TransitMetroMapView
import dev.aidos.ui.spatial.schematic.TransitMetroPresenter
import dev.aidos.ui.system.NativeProcessHealth
import dev.aidos.ui.system.NativeProcessRecoveryBar
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.CornerTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.workspace.WorkspaceBootstrap
import dev.aidos.ui.workspace.WorkspaceController
import dev.aidos.ui.workspace.WorkspaceKind
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Universal Adaptive Application Root (RFC-0106, RFC-0111, RFC-0112).
 *
 * Unifies the Frontdoors (Action Inbox, Approval Gates, Project Hub),
 * all 7 Intent Graph Paradigms (Relay River, Transit Metro, Cartridge Dock,
 * Radial Radar, Logic Breadboard, Causal Outliner, Mind-Map ZUI), Timeline/Gantt,
 * Living Doc, and System Overlays (MultiAgentPillOverlay, CommandPaletteModal,
 * AssistantSheet, NativeProcessRecoveryBar).
 */
@Composable
fun AidosUniversalApp(
    runtimeClient: RuntimeClient,
    initialDestinationId: String = "inbox",
    initialLifeFilterOnly: Boolean = false,
    initialProjectId: String? = null,
    initialAssistantOpen: Boolean = false,
    nativeProcessHealth: StateFlow<NativeProcessHealth>? = null,
    approvalGatePresenter: ApprovalGatePresenter? = null,
    voiceCaptureController: VoiceCaptureController? = null,
    onPinProjectLauncher: ((projectId: String, projectName: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    // RFC-0124 decision U5: a real project, never a magic id. Show nothing but progress until resolved.
    var resolvedProjectId by remember { mutableStateOf<String?>(null) }
    var resolveError by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(runtimeClient, initialProjectId, initialLifeFilterOnly) {
        try {
            resolvedProjectId = WorkspaceBootstrap.resolve(
                runtimeClient,
                initialProjectId,
                if (initialLifeFilterOnly) WorkspaceKind.LIFE else WorkspaceKind.WORKSPACE,
            )
            resolveError = null
        } catch (c: kotlinx.coroutines.CancellationException) {
            throw c
        } catch (t: Throwable) {
            resolveError = t.message ?: "Could not open the workspace"
        }
    }
    val projectId = resolvedProjectId
    if (projectId == null) {
        Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            val error = resolveError
            if (error == null) CircularProgressIndicator() else Text(error)
        }
        return
    }
    AidosUniversalAppContent(
        runtimeClient = runtimeClient,
        initialDestinationId = initialDestinationId,
        initialProjectId = projectId,
        initialAssistantOpen = initialAssistantOpen,
        nativeProcessHealth = nativeProcessHealth,
        approvalGatePresenter = approvalGatePresenter,
        voiceCaptureController = voiceCaptureController,
        onPinProjectLauncher = onPinProjectLauncher,
        modifier = modifier,
    )
}

@Composable
private fun AidosUniversalAppContent(
    runtimeClient: RuntimeClient,
    initialDestinationId: String,
    initialProjectId: String,
    initialAssistantOpen: Boolean,
    nativeProcessHealth: StateFlow<NativeProcessHealth>?,
    approvalGatePresenter: ApprovalGatePresenter?,
    voiceCaptureController: VoiceCaptureController?,
    onPinProjectLauncher: ((projectId: String, projectName: String) -> Unit)?,
    modifier: Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    val controller = remember(runtimeClient) {
        WorkspaceController(runtimeClient, coroutineScope, initialProjectId, initialDestinationId)
    }
    val workspaceState by controller.state.collectAsState()
    val selectedDestinationId = workspaceState.viewId
    val currentProjectId = workspaceState.projectId
    val currentStore by controller.currentStore.collectAsState()
    val selectedNodeId = workspaceState.selectedNodeId
    // RFC-0124 (UI-21): one inspector for every view, reading the current project's graph.
    val inspectorPresenter = remember(currentStore, selectedNodeId) {
        NodeInspectorPresenter(currentStore.snapshot, selectedNodeId.orEmpty())
    }
    val snackbarHost = remember { SnackbarHostState() }
    LaunchedEffect(workspaceState.message) {
        val message = workspaceState.message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        controller.clearMessage()
    }

    val inboxPresenter = remember {
        ActionInboxPresenter(
            controller = controller,
            projectCommands = runtimeClient.projects,
            scope = coroutineScope,
        )
    }

    val projectHubPresenter = remember {
        ProjectHubPresenter(
            projectCommands = runtimeClient.projects,
            scope = coroutineScope,
        )
    }

    // RFC-0124 amendment (UI-20): every graph view reads the current project's live store.
    val outlinerPresenter = remember(currentProjectId) {
        CausalOutlinerPresenter(controller.currentStore.value.snapshot, coroutineScope)
    }
    val pipelinePresenter = remember(currentProjectId) {
        RelayPipelinePresenter(controller.currentStore.value.snapshot, coroutineScope)
    }
    val metroPresenter = remember(currentProjectId) {
        TransitMetroPresenter(controller.currentStore.value.snapshot, coroutineScope)
    }
    val kanbanPresenter = remember(currentProjectId) {
        CartridgeDockPresenter(controller.currentStore.value.snapshot, coroutineScope)
    }
    val radarPresenter = remember(currentProjectId) {
        FocusRadarPresenter(controller.currentStore.value.snapshot, coroutineScope)
    }
    val breadboardPresenter = remember(currentProjectId) {
        BreadboardSchematicPresenter(controller.currentStore.value.snapshot, coroutineScope)
    }
    val canvasPresenter = remember(currentProjectId) {
        SpatialCanvasPresenter(controller.currentStore.value.snapshot, coroutineScope)
    }
    val timelinePresenter = remember(currentProjectId) {
        TimelineGanttPresenter(controller.currentStore.value.snapshot, coroutineScope)
    }
    val livingDocPresenter = remember(currentProjectId) {
        LivingDocPresenter(controller.currentStore.value.snapshot, controller, coroutineScope)
    }
    // RFC-0113 / RFC-0124: Gates reads every approval the person owes and decides through the same runtime
    // calls the CLI uses, so a person's decision is the only thing that lets a private message leave.
    // A presenter the host supplied is the host's to feed; the one made here polls the runtime itself.
    val gatePresenter = approvalGatePresenter ?: remember(runtimeClient, controller) {
        ApprovalGatePresenter(runtimeClient, controller, coroutineScope)
    }
    LaunchedEffect(gatePresenter) { gatePresenter.start() }

    val dexPreserver = remember { DexStatePreserver() }
    val fallbackHealthFlow = remember { MutableStateFlow(NativeProcessHealth.HEALTHY) }
    val nativeHealth by (nativeProcessHealth ?: fallbackHealthFlow).collectAsState()
    var isAssistantOpen by remember { mutableStateOf(initialAssistantOpen) }
    var assistantGhostText by remember { mutableStateOf("") }

    val commandPalettePresenter = remember {
        CommandPalettePresenter(
            allCommands = ViewModeCatalog.all.map { mode ->
                PaletteItem("nav-${mode.id}", mode.label, "Switch to ${mode.label}", CommandItemKind.COMMAND) {
                    controller.navigate(mode.id)
                }
            } + PaletteItem("sys-assistant", "Trigger Voice Assistant", "Record intent via Red Pill", CommandItemKind.COMMAND) {
                isAssistantOpen = true
            }
        )
    }

    val pills = remember(isAssistantOpen) {
        listOf(
            AgentPillState(
                id = "pill-red",
                role = PillRole.ORCHESTRATOR_RED,
                label = "Dictator Caret",
                xNormalized = 0.85f,
                yNormalized = 0.15f,
                isRadiatingWave = isAssistantOpen,
            ),
            AgentPillState(
                id = "pill-blue",
                role = PillRole.WORKER_CODE_BLUE,
                label = "Code Worker",
                xNormalized = 0.12f,
                yNormalized = 0.88f,
            ),
            AgentPillState(
                id = "pill-green",
                role = PillRole.VERIFIER_GREEN,
                label = "Verifier",
                xNormalized = 0.30f,
                yNormalized = 0.88f,
            ),
            AgentPillState(
                id = "pill-violet",
                role = PillRole.RESEARCH_VIOLET,
                label = "Life & R&D",
                xNormalized = 0.50f,
                yNormalized = 0.88f,
            ),
        )
    }

    // RFC-0124: which views are primary navigation depends on the form factor (ViewModeCatalog).
    fun destinationsFor(formFactor: dev.aidos.ui.layout.FormFactor) =
        ViewModeCatalog.primary(formFactor).map { NavigationDestination(it.id, it.label, { Text(it.icon) }) }

    ResponsiveScaffold(
        destinations = destinationsFor(dev.aidos.ui.layout.FormFactor.DESKTOP_DOCKED),
        destinationsFor = ::destinationsFor,
        selectedDestinationId = selectedDestinationId,
        onDestinationSelected = { id -> controller.navigate(id) },
        topBar = {
            NativeProcessRecoveryBar(health = nativeHealth)
        },
        overlayContent = {
            MultiAgentPillOverlay(pills = pills)
            CommandPaletteModal(presenter = commandPalettePresenter)
            val voiceState by (voiceCaptureController?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(VoiceCaptureState()) }).collectAsState()
            val effectiveTranscript = voiceState.partialText.ifBlank { assistantGhostText }
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                SnackbarHost(hostState = snackbarHost)
            }
            AssistantSheetOverlay(
                isOpen = isAssistantOpen,
                isListening = voiceState.isListening,
                streamedGhostText = effectiveTranscript,
                preferredBackend = voiceState.preferredBackend,
                activeBackend = voiceState.activeBackend,
                errorMessage = voiceState.errorMessage,
                onSelectBackend = { backend: SttBackend ->
                    voiceCaptureController?.selectBackend(backend)
                },
                onToggleListening = {
                    val ctrl = voiceCaptureController
                    if (ctrl != null) {
                        coroutineScope.launch {
                            if (voiceState.isListening) {
                                val text = ctrl.stopListening()
                                if (!text.isNullOrBlank()) {
                                    assistantGhostText = text
                                }
                            } else {
                                ctrl.startListening()
                            }
                        }
                    }
                },
                onTranscriptEdit = { edited ->
                    assistantGhostText = edited
                    voiceCaptureController?.updateTranscript(edited)
                },
                onProposeTask = { text ->
                    coroutineScope.launch {
                        runtimeClient.intents.createNode(
                            CreateIntentNodeRequest(
                                projectId = currentProjectId,
                                title = text,
                                type = IntentNodeType.ATOMIC_TASK,
                            )
                        )
                        controller.currentStore.value.refreshNow()
                        voiceCaptureController?.cancel()
                        isAssistantOpen = false
                        assistantGhostText = ""
                    }
                },
                onDismiss = {
                    voiceCaptureController?.cancel()
                    isAssistantOpen = false
                },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { formFactor ->
        LaunchedEffect(formFactor) {
            dexPreserver.captureSession(
                projectId = currentProjectId,
                nodeId = null,
                documentText = livingDocPresenter.state.value.text,
                viewport = canvasPresenter.state.value.viewport,
                formFactor = formFactor,
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B1120))
                    .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
                contentPadding = PaddingValues(horizontal = SpacingTokens.xs),
            ) {
                items(ViewModeCatalog.offered(formFactor), key = { it.id }) { mode ->
                    val isSelected = selectedDestinationId == mode.id
                    Box(
                        modifier = Modifier
                            .background(
                                if (isSelected) ColorTokens.ActorAgent.copy(alpha = 0.25f) else Color(0xFF1E293B),
                                shape = RoundedCornerShape(CornerTokens.pill),
                            )
                            .border(
                                width = 1.dp,
                                color = if (isSelected) ColorTokens.ActorAgent else Color(0xFF334155),
                                shape = RoundedCornerShape(CornerTokens.pill),
                            )
                            .clickable { controller.navigate(mode.id) }
                            .padding(horizontal = SpacingTokens.sm, vertical = 4.dp),
                    ) {
                        Text(
                            text = mode.label,
                            style = AidosTypography.baseTypography.labelSmall,
                            color = if (isSelected) Color.White else ColorTokens.StatusStale,
                        )
                    }
                }
            }

            Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
            Box(modifier = Modifier.weight(1f).fillMaxHeight()) {
                when (selectedDestinationId) {
                    "inbox" -> ActionInboxScreen(
                        presenter = inboxPresenter,
                        controller = controller,
                        formFactor = formFactor,
                        onItemClick = { item ->
                            controller.switchProject(item.node.projectId)
                            controller.navigate("outliner", item.node.id)
                        },
                    )
                    "outliner" -> CausalOutlinerScreen(
                        presenter = outlinerPresenter,
                        controller = controller,
                        formFactor = formFactor,
                        onDrillDown = { controller.navigate("livingdoc") },
                    )
                    "pipeline" -> RelayPipelineScreen(presenter = pipelinePresenter, controller = controller, formFactor = formFactor)
                    "metro" -> TransitMetroMapView(presenter = metroPresenter, controller = controller, formFactor = formFactor)
                    "kanban" -> CartridgeDockScreen(
                        presenter = kanbanPresenter,
                        controller = controller,
                        formFactor = formFactor,
                    )
                    "radar" -> RadialRadarScreen(presenter = radarPresenter, controller = controller, formFactor = formFactor)
                    "breadboard" -> BreadboardSchematicView(presenter = breadboardPresenter, controller = controller, formFactor = formFactor)
                    "canvas" -> InfiniteZuiCanvas(presenter = canvasPresenter, controller = controller, formFactor = formFactor)
                    "timeline" -> TimelineGanttScreen(presenter = timelinePresenter, formFactor = formFactor, controller = controller)
                    "livingdoc" -> LivingDocView(
                        presenter = livingDocPresenter,
                        controller = controller,
                        formFactor = formFactor,
                        voiceCaptureController = voiceCaptureController,
                    )
                    "gates" -> ApprovalGatesScreen(presenter = gatePresenter, controller = controller, formFactor = formFactor)
                    "projects" -> ProjectHubScreen(
                        presenter = projectHubPresenter,
                        controller = controller,
                        formFactor = formFactor,
                        onProjectClick = { projId ->
                            controller.switchProject(projId)
                            controller.navigate("inbox")
                        },
                        onPinProjectClick = onPinProjectLauncher,
                    )
                }
            }
            // Desktop: a 360 dp panel at the end of the row. Handhelds: a bottom sheet (it draws no space here).
            if (workspaceState.inspectorOpen && selectedNodeId != null) {
                NodeInspector(
                    presenter = inspectorPresenter,
                    controller = controller,
                    formFactor = formFactor,
                    onClose = { controller.closeInspector() },
                )
            }
            }
        }
    }
}
