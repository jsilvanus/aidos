package dev.aidos.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.runtime.setValue
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
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedDestinationId by remember { mutableStateOf(initialDestinationId) }
    var currentProjectId by remember { mutableStateOf(initialProjectId) }

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

    val outlinerPresenter = remember(currentProjectId) {
        CausalOutlinerPresenter(currentProjectId, runtimeClient.intents, coroutineScope)
    }
    val pipelinePresenter = remember(currentProjectId) {
        RelayPipelinePresenter(currentProjectId, runtimeClient.intents, coroutineScope)
    }
    val metroPresenter = remember(currentProjectId) {
        TransitMetroPresenter(currentProjectId, runtimeClient.intents, coroutineScope)
    }
    val kanbanPresenter = remember(currentProjectId) {
        CartridgeDockPresenter(currentProjectId, runtimeClient.intents, coroutineScope)
    }
    val radarPresenter = remember(currentProjectId) {
        FocusRadarPresenter(currentProjectId, runtimeClient.intents, coroutineScope)
    }
    val breadboardPresenter = remember(currentProjectId) {
        BreadboardSchematicPresenter(currentProjectId, runtimeClient.intents, coroutineScope)
    }
    val canvasPresenter = remember(currentProjectId) {
        SpatialCanvasPresenter(currentProjectId, runtimeClient.intents, coroutineScope)
    }
    val timelinePresenter = remember(currentProjectId) {
        TimelineGanttPresenter(currentProjectId, runtimeClient.intents, coroutineScope)
    }
    val livingDocPresenter = remember(currentProjectId) {
        LivingDocPresenter(
            initialDocumentId = "doc-$currentProjectId",
            initialTitle = "Living Workspace Specification",
        )
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
    var isAssistantOpen by remember { mutableStateOf(initialAssistantOpen) }
    var assistantGhostText by remember { mutableStateOf("") }

    val commandPalettePresenter = remember {
        CommandPalettePresenter(
            allCommands = ViewModeCatalog.all.map { mode ->
                PaletteItem("nav-${mode.id}", mode.label, "Switch to ${mode.label}", CommandItemKind.COMMAND) {
                    selectedDestinationId = mode.id
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
        onDestinationSelected = { id -> selectedDestinationId = id },
        topBar = {
            NativeProcessRecoveryBar(health = nativeHealth)
        },
        overlayContent = {
            MultiAgentPillOverlay(pills = pills)
            CommandPaletteModal(presenter = commandPalettePresenter)
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
                        runtimeClient.intents.createNode(
                            CreateIntentNodeRequest(
                                projectId = currentProjectId,
                                title = text,
                                type = IntentNodeType.ATOMIC_TASK,
                            )
                        )
                        inboxPresenter.loadData()
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
                documentText = livingDocPresenter.state.value.lines.joinToString("\n") { it.text },
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
                            .clickable { selectedDestinationId = mode.id }
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

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                when (selectedDestinationId) {
                    "inbox" -> ActionInboxScreen(
                        presenter = inboxPresenter,
                        onItemClick = { item ->
                            currentProjectId = item.node.projectId
                            selectedDestinationId = "outliner"
                        },
                    )
                    "outliner" -> CausalOutlinerScreen(
                        presenter = outlinerPresenter,
                        onDrillDown = { selectedDestinationId = "livingdoc" },
                    )
                    "pipeline" -> RelayPipelineScreen(presenter = pipelinePresenter)
                    "metro" -> TransitMetroMapView(presenter = metroPresenter)
                    "kanban" -> CartridgeDockScreen(
                        presenter = kanbanPresenter,
                        onTaskClick = { selectedDestinationId = "outliner" },
                    )
                    "radar" -> RadialRadarScreen(presenter = radarPresenter)
                    "breadboard" -> BreadboardSchematicView(presenter = breadboardPresenter)
                    "canvas" -> InfiniteZuiCanvas(presenter = canvasPresenter)
                    "timeline" -> TimelineGanttScreen(presenter = timelinePresenter, formFactor = formFactor)
                    "livingdoc" -> LivingDocView(presenter = livingDocPresenter)
                    "gates" -> ApprovalGatesScreen(presenter = gatePresenter)
                    "projects" -> ProjectHubScreen(
                        presenter = projectHubPresenter,
                        onProjectClick = { projId ->
                            currentProjectId = projId
                            selectedDestinationId = "inbox"
                        },
                        onPinProjectClick = onPinProjectLauncher,
                    )
                }
            }
        }
    }
}
