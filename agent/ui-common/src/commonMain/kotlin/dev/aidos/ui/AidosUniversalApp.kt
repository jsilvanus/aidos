package dev.aidos.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.RuntimeClient
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.assistant.AssistantSheetOverlay
import dev.aidos.ui.dex.DexStatePreserver
import dev.aidos.ui.frontdoors.inbox.ActionInboxPresenter
import dev.aidos.ui.frontdoors.inbox.ActionInboxScreen
import dev.aidos.ui.frontdoors.projects.ProjectHubPresenter
import dev.aidos.ui.frontdoors.projects.ProjectHubScreen
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.layout.NavigationDestination
import dev.aidos.ui.layout.ResponsiveScaffold
import dev.aidos.ui.palette.CommandItemKind
import dev.aidos.ui.palette.CommandPaletteIntent
import dev.aidos.ui.palette.CommandPaletteModal
import dev.aidos.ui.palette.CommandPalettePresenter
import dev.aidos.ui.palette.PaletteItem
import dev.aidos.ui.paradigms.livingdoc.LivingDocPresenter
import dev.aidos.ui.paradigms.livingdoc.LivingDocView
import dev.aidos.ui.paradigms.outliner.CausalOutlinerPresenter
import dev.aidos.ui.paradigms.outliner.CausalOutlinerScreen
import dev.aidos.ui.pills.AgentPillState
import dev.aidos.ui.pills.MultiAgentPillOverlay
import dev.aidos.ui.pills.PillRole
import dev.aidos.ui.spatial.canvas.InfiniteZuiCanvas
import dev.aidos.ui.spatial.canvas.SpatialCanvasPresenter
import dev.aidos.ui.system.NativeProcessHealth
import dev.aidos.ui.system.NativeProcessRecoveryBar
import kotlinx.coroutines.launch

/**
 * Universal Adaptive Application Root (RFC-0106, RFC-0111, RFC-0112).
 *
 * Unifies the Frontdoors (Action Inbox, Approval Gates, Project Hub),
 * Paradigms (Causal Outliner, Relay Pipeline, Cartridge Dock, Living Doc),
 * Spatial Surfaces (Infinite ZUI Canvas, Radar, Metro Map),
 * and System Overlays (MultiAgentPillOverlay, CommandPaletteModal, AssistantSheet, NativeProcessRecoveryBar).
 */
@Composable
fun AidosUniversalApp(
    runtimeClient: RuntimeClient,
    initialDestinationId: String = "inbox",
    initialLifeFilterOnly: Boolean = false,
    initialProjectId: String = "default-workspace",
    onPinProjectLauncher: ((projectId: String, projectName: String) -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val coroutineScope = rememberCoroutineScope()
    var selectedDestinationId by remember { mutableStateOf(initialDestinationId) }
    var currentProjectId by remember { mutableStateOf(initialProjectId) }

    // Presenters
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
        CausalOutlinerPresenter(
            projectId = currentProjectId,
            intentCommands = runtimeClient.intents,
            scope = coroutineScope,
        )
    }

    val canvasPresenter = remember(currentProjectId) {
        SpatialCanvasPresenter(
            projectId = currentProjectId,
            intentCommands = runtimeClient.intents,
            scope = coroutineScope,
        )
    }

    val livingDocPresenter = remember(currentProjectId) {
        LivingDocPresenter(
            initialDocumentId = "doc-$currentProjectId",
            initialTitle = "Living Workspace Specification",
        )
    }

    // System integrations
    val dexPreserver = remember { DexStatePreserver() }
    var nativeHealth by remember { mutableStateOf(NativeProcessHealth.HEALTHY) }
    var isAssistantOpen by remember { mutableStateOf(false) }
    var assistantGhostText by remember { mutableStateOf("") }

    // Command palette
    val commandPalettePresenter = remember {
        CommandPalettePresenter(
            allCommands = listOf(
                PaletteItem("nav-inbox", "Action Inbox", "Go to unblocked tasks", CommandItemKind.COMMAND) {
                    selectedDestinationId = "inbox"
                },
                PaletteItem("nav-outliner", "Causal Outliner", "View intent hierarchy", CommandItemKind.COMMAND) {
                    selectedDestinationId = "outliner"
                },
                PaletteItem("nav-canvas", "2D Infinite Canvas", "Explore spatial ZUI", CommandItemKind.COMMAND) {
                    selectedDestinationId = "canvas"
                },
                PaletteItem("nav-doc", "Living Document", "Voice-docked spec editor", CommandItemKind.COMMAND) {
                    selectedDestinationId = "livingdoc"
                },
                PaletteItem("nav-hub", "Workspaces Hub", "Switch between Life and Code", CommandItemKind.COMMAND) {
                    selectedDestinationId = "projects"
                },
                PaletteItem("sys-assistant", "Trigger Voice Assistant", "Record intent via Red Pill", CommandItemKind.COMMAND) {
                    isAssistantOpen = true
                },
            )
        )
    }

    // Multi-Agent Floating Pills
    val pills = remember {
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
            )
        )
    }

    val destinations = listOf(
        NavigationDestination("inbox", "Inbox", { Text("📥") }),
        NavigationDestination("outliner", "Outliner", { Text("🌳") }),
        NavigationDestination("canvas", "Canvas", { Text("🌌") }),
        NavigationDestination("livingdoc", "Document", { Text("📝") }),
        NavigationDestination("projects", "Projects", { Text("💼") }),
    )

    ResponsiveScaffold(
        destinations = destinations,
        selectedDestinationId = selectedDestinationId,
        onDestinationSelected = { id ->
            selectedDestinationId = id
        },
        topBar = {
            NativeProcessRecoveryBar(health = nativeHealth)
        },
        overlayContent = {
            MultiAgentPillOverlay(pills = pills)
            CommandPaletteModal(presenter = commandPalettePresenter)
            AssistantSheetOverlay(
                isOpen = isAssistantOpen,
                isListening = isAssistantOpen,
                streamedGhostText = assistantGhostText,
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
                    }
                },
                onDismiss = { isAssistantOpen = false },
            )
        },
        modifier = modifier.fillMaxSize(),
    ) { formFactor ->
        // Preserve active layout state for DeX hot-docking
        LaunchedEffect(formFactor) {
            dexPreserver.captureSession(
                projectId = currentProjectId,
                nodeId = null,
                documentText = livingDocPresenter.state.value.lines.joinToString("\n") { it.text },
                viewport = canvasPresenter.state.value.viewport,
                formFactor = formFactor,
            )
        }

        Box(modifier = Modifier.fillMaxSize()) {
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
                    onDrillDown = {
                        selectedDestinationId = "livingdoc"
                    },
                )
                "canvas" -> InfiniteZuiCanvas(
                    presenter = canvasPresenter,
                )
                "livingdoc" -> LivingDocView(
                    presenter = livingDocPresenter,
                )
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
