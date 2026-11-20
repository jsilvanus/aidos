package dev.aidos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.RuntimeClient
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.assistant.AssistantSheetOverlay
import dev.aidos.ui.assistant.ChatModelOption
import dev.aidos.ui.assistant.IntentCreationChatPanel
import dev.aidos.ui.assistant.IntentCreationChatState
import dev.aidos.ui.assistant.SttBackend
import dev.aidos.ui.assistant.VoiceCaptureController
import dev.aidos.ui.assistant.VoiceCaptureState
import dev.aidos.ui.dex.DexStatePreserver
import dev.aidos.ui.frontdoors.gates.ApprovalGatePresenter
import dev.aidos.ui.frontdoors.gates.ApprovalGatesScreen
import dev.aidos.ui.frontdoors.gates.toUiModel
import dev.aidos.ui.frontdoors.inbox.ActionInboxPresenter
import dev.aidos.ui.frontdoors.inbox.ActionInboxScreen
import dev.aidos.ui.frontdoors.projects.ProjectHubPresenter
import dev.aidos.ui.frontdoors.projects.ProjectHubScreen
import dev.aidos.ui.layout.NavigationDestination
import dev.aidos.ui.layout.ViewModeCatalog
import dev.aidos.ui.layout.ResponsiveScaffold
import dev.aidos.ui.palette.CommandItemKind
import dev.aidos.ui.palette.CommandPaletteModal
import dev.aidos.ui.palette.CommandPalettePresenter
import dev.aidos.ui.palette.PaletteItem
import dev.aidos.ui.paradigms.kanban.CartridgeDockPresenter
import dev.aidos.ui.paradigms.kanban.CartridgeDockScreen
import dev.aidos.ui.paradigms.livingdoc.LivingDocIntent
import dev.aidos.ui.paradigms.livingdoc.LivingDocPresenter
import dev.aidos.ui.paradigms.livingdoc.LivingDocView
import dev.aidos.ui.paradigms.outliner.CausalOutlinerPresenter
import dev.aidos.ui.paradigms.outliner.CausalOutlinerScreen
import dev.aidos.ui.paradigms.pipeline.RelayPipelinePresenter
import dev.aidos.ui.paradigms.pipeline.RelayPipelineScreen
import dev.aidos.ui.paradigms.timeline.TimelineGanttPresenter
import dev.aidos.ui.paradigms.timeline.TimelineGanttScreen
import dev.aidos.tcr.pill.AgentPillState
import dev.aidos.tcr.pill.MultiAgentPillOverlay
import dev.aidos.tcr.pill.PillRole
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
import kotlinx.coroutines.delay
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
    initialProjectId: String = "default-workspace",
    initialAssistantOpen: Boolean = false,
    nativeProcessHealth: StateFlow<NativeProcessHealth>? = null,
    approvalGatePresenter: ApprovalGatePresenter? = null,
    voiceCaptureController: VoiceCaptureController? = null,
    onPinProjectLauncher: ((projectId: String, projectName: String) -> Unit)? = null,
    availableModels: List<ChatModelOption> = emptyList(),
    onSendChatPrompt: (suspend (prompt: String, modelId: String) -> String)? = null,
    selectedDestinationId: String? = null,
    onDestinationSelected: ((String) -> Unit)? = null,
    currentProjectId: String? = null,
    onProjectSelected: ((String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var internalDestinationId by rememberSaveable { mutableStateOf(initialDestinationId) }
    var internalProjectId by rememberSaveable { mutableStateOf(initialProjectId) }
    val activeDestinationId = selectedDestinationId ?: internalDestinationId
    val activeProjectId = currentProjectId ?: internalProjectId
    val setDestination: (String) -> Unit = { id ->
        internalDestinationId = id
        onDestinationSelected?.invoke(id)
    }
    val setProject: (String) -> Unit = { projId ->
        internalProjectId = projId
        onProjectSelected?.invoke(projId)
    }

    val inboxPresenter = remember {
        ActionInboxPresenter(
            intentCommands = runtimeClient.intents,
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

    val outlinerPresenter = remember(activeProjectId) {
        CausalOutlinerPresenter(activeProjectId, runtimeClient.intents, coroutineScope)
    }
    val pipelinePresenter = remember(activeProjectId) {
        RelayPipelinePresenter(activeProjectId, runtimeClient.intents, coroutineScope)
    }
    val metroPresenter = remember(activeProjectId) {
        TransitMetroPresenter(activeProjectId, runtimeClient.intents, coroutineScope)
    }
    val kanbanPresenter = remember(activeProjectId) {
        CartridgeDockPresenter(activeProjectId, runtimeClient.intents, coroutineScope)
    }
    val radarPresenter = remember(activeProjectId) {
        FocusRadarPresenter(activeProjectId, runtimeClient.intents, coroutineScope)
    }
    val breadboardPresenter = remember(activeProjectId) {
        BreadboardSchematicPresenter(activeProjectId, runtimeClient.intents, coroutineScope)
    }
    val canvasPresenter = remember(activeProjectId) {
        SpatialCanvasPresenter(activeProjectId, runtimeClient.intents, coroutineScope)
    }
    val timelinePresenter = remember(activeProjectId) {
        TimelineGanttPresenter(activeProjectId, runtimeClient.intents, coroutineScope)
    }
    val livingDocPresenter = remember(activeProjectId) {
        LivingDocPresenter(
            initialDocumentId = "doc-$activeProjectId",
            initialTitle = "Living Workspace Specification",
        )
    }

    fun refreshAllPresenters() {
        inboxPresenter.loadData()
        outlinerPresenter.loadGraph()
        pipelinePresenter.loadPipeline()
        metroPresenter.loadMetro()
        kanbanPresenter.loadDock()
        radarPresenter.loadRadar()
        breadboardPresenter.loadCircuit()
        canvasPresenter.loadGraph()
        timelinePresenter.loadTimeline()
        projectHubPresenter.loadProjects()
    }

    suspend fun createIntentNode(
        title: String,
        type: IntentNodeType,
        actorKind: IntentActorKind,
        description: String? = null,
    ) {
        runtimeClient.intents.createNode(
            CreateIntentNodeRequest(
                projectId = activeProjectId,
                title = title,
                type = type,
                actorKind = actorKind,
                description = description,
            )
        )
        refreshAllPresenters()
    }

    val launchCreateIntent: (String, IntentNodeType, IntentActorKind) -> Unit = { title, type, actorKind ->
        coroutineScope.launch {
            createIntentNode(title, type, actorKind)
        }
    }
    // RFC-0113: the Human Egress Gate Card is fed by the runtime's parked outbound messages and its
    // Approve / Deny go through the same effect-approval path the CLI uses, so a person's decision is
    // the only thing that lets a private project's message leave.
    val gatePresenter = approvalGatePresenter ?: remember {
        ApprovalGatePresenter(
            scope = coroutineScope,
            onEgressApproved = { runId -> runtimeClient.capabilities.approveEffect(runId, runId) },
            onEgressDenied = { runId, reason -> runtimeClient.capabilities.denyEffect(runId, runId, reason) },
        )
    }
    // A presenter the host supplied is the host's to feed; only the one made here follows the runtime.
    LaunchedEffect(runtimeClient, gatePresenter, approvalGatePresenter == null) {
        while (approvalGatePresenter == null) {
            val parked = runCatching { runtimeClient.capabilities.listParkedEgress() }.getOrNull()
            if (parked != null) {
                gatePresenter.syncEgressRequests(parked.map { it.toUiModel() })
            }
            delay(2_000)
        }
    }

    val dexPreserver = remember { DexStatePreserver() }
    val fallbackHealthFlow = remember { MutableStateFlow(NativeProcessHealth.HEALTHY) }
    val nativeHealth by (nativeProcessHealth ?: fallbackHealthFlow).collectAsState()
    var isAssistantOpen by remember { mutableStateOf(false) }
    var assistantGhostText by remember { mutableStateOf("") }
    val intentChatState = remember {
        IntentCreationChatState(
            initialOpen = initialAssistantOpen,
            initialModels = availableModels,
        )
    }
    LaunchedEffect(availableModels) {
        intentChatState.setModels(availableModels)
    }
    val chatUiState by intentChatState.state.collectAsState()

    val commandPalettePresenter = remember {
        CommandPalettePresenter(
            allCommands = ViewModeCatalog.all.map { mode ->
                PaletteItem("nav-${mode.id}", mode.label, "Switch to ${mode.label}", CommandItemKind.COMMAND) {
                    setDestination(mode.id)
                }
            } + PaletteItem("sys-assistant", "Toggle Orchestrator Intent Chat", "Open Red Pill Intent Chat", CommandItemKind.COMMAND) {
                intentChatState.toggleOpen()
            } + PaletteItem("sys-voice", "Trigger Voice Assistant", "Record intent via Red Pill Voice", CommandItemKind.COMMAND) {
                isAssistantOpen = true
            }
        )
    }

    val pills = remember(isAssistantOpen, chatUiState.isOpen) {
        listOf(
            AgentPillState(
                id = "pill-red",
                role = PillRole.ORCHESTRATOR_RED,
                label = "Orchestrator",
                xNormalized = 0.85f,
                yNormalized = 0.08f,
                isRadiatingWave = isAssistantOpen || chatUiState.isOpen,
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
        selectedDestinationId = activeDestinationId,
        onDestinationSelected = setDestination,
        topBar = {
            NativeProcessRecoveryBar(health = nativeHealth)
        },
        overlayContent = {
            MultiAgentPillOverlay(
                pills = pills,
                onPillClick = { pill ->
                    if (pill.role == PillRole.ORCHESTRATOR_RED) {
                        intentChatState.toggleOpen()
                    }
                },
            )
            CommandPaletteModal(presenter = commandPalettePresenter)
            IntentCreationChatPanel(
                uiState = chatUiState,
                onSelectModel = { intentChatState.selectModel(it) },
                onPromptChange = { intentChatState.updatePrompt(it) },
                onSelectType = { intentChatState.selectType(it) },
                onSelectActor = { intentChatState.selectActor(it) },
                onSendPrompt = {
                    coroutineScope.launch {
                        intentChatState.submitPrompt(
                            onCreateNode = ::createIntentNode,
                            onInference = onSendChatPrompt,
                            createDirectly = false,
                        )
                    }
                },
                onDirectCreate = {
                    coroutineScope.launch {
                        intentChatState.submitPrompt(
                            onCreateNode = ::createIntentNode,
                            createDirectly = true,
                        )
                    }
                },
                onCreateProposal = { messageId, proposalId ->
                    coroutineScope.launch {
                        intentChatState.createProposal(
                            messageId = messageId,
                            proposalId = proposalId,
                            onCreateNode = ::createIntentNode,
                        )
                    }
                },
                onDismiss = { intentChatState.setOpen(false) },
                onOpenVoiceAssistant = {
                    intentChatState.setOpen(false)
                    isAssistantOpen = true
                },
            )
            val voiceState by (voiceCaptureController?.state ?: remember { kotlinx.coroutines.flow.MutableStateFlow(VoiceCaptureState()) }).collectAsState()
            val effectiveTranscript = voiceState.partialText.ifBlank { assistantGhostText }
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
                        createIntentNode(
                            title = text,
                            type = IntentNodeType.ATOMIC_TASK,
                            actorKind = IntentActorKind.AGENT,
                        )
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
                projectId = activeProjectId,
                nodeId = null,
                documentText = livingDocPresenter.state.value.lines.joinToString("\n") { it.text },
                viewport = canvasPresenter.state.value.viewport,
                formFactor = formFactor,
            )
        }

        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF0B1120))
                    .padding(horizontal = SpacingTokens.sm, vertical = SpacingTokens.xs),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
            ) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs),
                    contentPadding = PaddingValues(horizontal = SpacingTokens.xs),
                ) {
                    items(ViewModeCatalog.offered(formFactor), key = { it.id }) { mode ->
                        val isSelected = activeDestinationId == mode.id
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
                                .clickable { setDestination(mode.id) }
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

                Box(
                    modifier = Modifier
                        .background(
                            color = if (chatUiState.isOpen) ColorTokens.PillOrchestratorRed.copy(alpha = 0.25f) else Color(0xFF1E293B),
                            shape = RoundedCornerShape(CornerTokens.pill),
                        )
                        .border(
                            width = 1.dp,
                            color = ColorTokens.PillOrchestratorRed,
                            shape = RoundedCornerShape(CornerTokens.pill),
                        )
                        .clickable { intentChatState.toggleOpen() }
                        .padding(horizontal = SpacingTokens.sm, vertical = 4.dp),
                ) {
                    Text(
                        text = if (chatUiState.isOpen) "🔴 Chat Open" else "🔴 Intent Chat",
                        style = AidosTypography.baseTypography.labelSmall,
                        color = if (chatUiState.isOpen) Color.White else ColorTokens.PillOrchestratorRed,
                    )
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (activeDestinationId) {
                    "inbox" -> ActionInboxScreen(
                        presenter = inboxPresenter,
                        onItemClick = { item ->
                            setProject(item.node.projectId)
                            setDestination("outliner")
                        },
                        onCreateIntent = launchCreateIntent,
                    )
                    "outliner" -> CausalOutlinerScreen(
                        presenter = outlinerPresenter,
                        onDrillDown = { setDestination("livingdoc") },
                        onCreateIntent = launchCreateIntent,
                    )
                    "pipeline" -> RelayPipelineScreen(
                        presenter = pipelinePresenter,
                        onCreateIntent = launchCreateIntent,
                    )
                    "metro" -> TransitMetroMapView(
                        presenter = metroPresenter,
                        onCreateIntent = launchCreateIntent,
                    )
                    "kanban" -> CartridgeDockScreen(
                        presenter = kanbanPresenter,
                        onTaskClick = { setDestination("outliner") },
                        onCreateIntent = launchCreateIntent,
                    )
                    "radar" -> RadialRadarScreen(
                        presenter = radarPresenter,
                        onCreateIntent = launchCreateIntent,
                    )
                    "breadboard" -> BreadboardSchematicView(
                        presenter = breadboardPresenter,
                        onCreateIntent = launchCreateIntent,
                    )
                    "canvas" -> InfiniteZuiCanvas(
                        presenter = canvasPresenter,
                        onCreateIntent = launchCreateIntent,
                    )
                    "timeline" -> TimelineGanttScreen(
                        presenter = timelinePresenter,
                        formFactor = formFactor,
                        onCreateIntent = launchCreateIntent,
                    )
                    "livingdoc" -> LivingDocView(
                        presenter = livingDocPresenter,
                        onCreateIntent = { title, type, actorKind ->
                            livingDocPresenter.send(LivingDocIntent.CommitVoiceText(title))
                            launchCreateIntent(title, type, actorKind)
                        },
                    )
                    "gates" -> ApprovalGatesScreen(presenter = gatePresenter)
                    "projects" -> ProjectHubScreen(
                        presenter = projectHubPresenter,
                        onProjectClick = { projId ->
                            setProject(projId)
                        },
                        onPinProjectClick = onPinProjectLauncher,
                    )
                }
            }
        }
    }
}
