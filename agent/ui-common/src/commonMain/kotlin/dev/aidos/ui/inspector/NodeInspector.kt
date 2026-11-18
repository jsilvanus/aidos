package dev.aidos.ui.inspector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.UpdateIntentNodeRequest
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.tcr.a2ui.A2uiDensity
import dev.aidos.ui.generative.GenerativeCards
import dev.aidos.ui.layout.FormFactor
import dev.aidos.ui.theme.AidosTypography
import dev.aidos.ui.theme.ColorTokens
import dev.aidos.ui.theme.SpacingTokens
import dev.aidos.ui.theme.colorForStatus
import dev.aidos.ui.workspace.GraphSnapshot
import dev.aidos.ui.workspace.WorkspaceController

/**
 * The one node inspector for every view (RFC-0124, UI-21). On desktop it is a 360 dp panel for the
 * caller to place at the end of its row; on handhelds it is a bottom sheet of its own.
 * Every change goes through [WorkspaceController], so the graph store refreshes after it.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeInspector(
    presenter: NodeInspectorPresenter,
    controller: WorkspaceController,
    formFactor: FormFactor,
    onClose: () -> Unit,
) {
    val snapshot by presenter.snapshot.collectAsState(initial = null)
    val body: @Composable () -> Unit = {
        InspectorBody(snapshot, presenter.nodeId, controller, onClose)
    }
    if (formFactor == FormFactor.DESKTOP_DOCKED) {
        Surface(
            modifier = Modifier.width(360.dp).fillMaxHeight(),
            color = ColorTokens.SurfaceCardDark,
        ) { body() }
    } else {
        ModalBottomSheet(onDismissRequest = onClose) { body() }
    }
}

@Composable
private fun InspectorBody(
    snapshot: GraphSnapshot?,
    nodeId: String,
    controller: WorkspaceController,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(SpacingTokens.base),
        verticalArrangement = Arrangement.spacedBy(SpacingTokens.sm),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Node",
                style = AidosTypography.baseTypography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose) {
                Icon(Icons.Default.Close, contentDescription = "Close inspector")
            }
        }
        val state = snapshot?.let { buildInspectorState(it, nodeId) }
        if (snapshot == null) {
            CircularProgressIndicator()
        } else if (state == null) {
            Text("This node is no longer in the graph.")
        } else {
            InspectorSections(snapshot, state, controller, onClose)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun InspectorSections(
    snapshot: GraphSnapshot,
    state: NodeInspectorState,
    controller: WorkspaceController,
    onClose: () -> Unit,
) {
    val node = state.node
    val nodeId = node.id
    val projectId = node.projectId
    val byId = remember(snapshot) { snapshot.nodes.associateBy { it.id } }
    val dependencyIds = remember(snapshot, nodeId) {
        snapshot.edges.filter { it.fromNodeId == nodeId && it.edgeKind == IntentEdgeKind.DEPENDS_ON }.map { it.toNodeId }
    }
    val unmetIds = remember(state) { state.unmetDependencies.map { it.id }.toSet() }

    // Header: type, actor and status.
    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs), verticalAlignment = Alignment.CenterVertically) {
        AssistChip(onClick = {}, label = { Text(node.type.label()) })
        AssistChip(onClick = {}, label = { Text(node.actorKind.label()) })
        Text(
            text = state.status.label(),
            style = AidosTypography.baseTypography.labelLarge,
            color = colorForStatus(state.status),
            fontWeight = FontWeight.SemiBold,
        )
    }

    SavedTextField(
        value = node.title,
        label = "Title",
        onSave = { controller.updateNode(UpdateIntentNodeRequest(projectId, nodeId, title = it)) },
    )
    SavedTextField(
        value = node.description.orEmpty(),
        label = "Description",
        singleLine = false,
        onSave = {
            val request = if (it.isBlank()) {
                UpdateIntentNodeRequest(projectId, nodeId, clearDescription = true)
            } else {
                UpdateIntentNodeRequest(projectId, nodeId, description = it)
            }
            controller.updateNode(request)
        },
    )
    SavedTextField(
        value = node.priority.toString(),
        label = "Priority",
        keyboardType = KeyboardType.Number,
        validate = { if (it.toIntOrNull() == null) "Enter a whole number." else null },
        onSave = { controller.updateNode(UpdateIntentNodeRequest(projectId, nodeId, priority = it.toInt())) },
    )

    // Dispatch: who starts runs, and where they may run.
    SectionTitle("Dispatch")
    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
        listOf(
            NodeDispatchPolicy.INHERIT to "Inherit",
            NodeDispatchPolicy.MANUAL_START to "Manual",
            NodeDispatchPolicy.AUTO_WHEN_READY to "Auto",
        ).forEach { (policy, text) ->
            FilterChip(
                selected = node.dispatchPolicy == policy,
                onClick = { controller.updateNode(UpdateIntentNodeRequest(projectId, nodeId, dispatchPolicy = policy)) },
                label = { Text(text) },
            )
        }
    }
    state.effectivePolicy?.let {
        Text("Effective policy: ${it.name.lowercase().replace('_', ' ')}", style = AidosTypography.baseTypography.bodySmall)
    }
    EnumPicker(
        label = "Target",
        current = node.targetProfile,
        options = listOf<Pair<IntentTargetProfile?, String>>(
            null to "Not set",
            IntentTargetProfile.MOBILE to "Mobile",
            IntentTargetProfile.DESKTOP to "Desktop",
            IntentTargetProfile.HEADLESS_SERVER to "Headless server",
            IntentTargetProfile.ANY to "Any",
        ),
        onPick = { profile ->
            controller.updateNode(
                if (profile == null) UpdateIntentNodeRequest(projectId, nodeId, clearTargetProfile = true)
                else UpdateIntentNodeRequest(projectId, nodeId, targetProfile = profile),
            )
        },
    )
    EnumPicker(
        label = "Min locality",
        current = node.minLocality,
        options = listOf<Pair<IntentMinLocality?, String>>(
            null to "Not set",
            IntentMinLocality.LOCAL_ONLY to "Local only",
            IntentMinLocality.TRUSTED_CLUSTER to "Trusted cluster",
            IntentMinLocality.ANY to "Any",
        ),
        onPick = { locality ->
            controller.updateNode(
                if (locality == null) UpdateIntentNodeRequest(projectId, nodeId, clearMinLocality = true)
                else UpdateIntentNodeRequest(projectId, nodeId, minLocality = locality),
            )
        },
    )
    SavedTextField(
        value = node.estimatedSteps?.toString().orEmpty(),
        label = "Estimated steps",
        keyboardType = KeyboardType.Number,
        validate = { if (it.isBlank() || it.toIntOrNull() != null) null else "Enter a whole number." },
        onSave = {
            val request = if (it.isBlank()) {
                UpdateIntentNodeRequest(projectId, nodeId, clearEstimatedSteps = true)
            } else {
                UpdateIntentNodeRequest(projectId, nodeId, estimatedSteps = it.toInt())
            }
            controller.updateNode(request)
        },
    )
    SavedTextField(
        value = node.dueAt.orEmpty(),
        label = "Due (ISO date)",
        validate = { if (it.isBlank() || DUE_PATTERN.matches(it)) null else "Use a date such as 2026-12-31." },
        onSave = {
            val request = if (it.isBlank()) {
                UpdateIntentNodeRequest(projectId, nodeId, clearDueAt = true)
            } else {
                UpdateIntentNodeRequest(projectId, nodeId, dueAt = it)
            }
            controller.updateNode(request)
        },
    )

    // Scope: the paths this node may touch.
    SectionTitle("Scope paths")
    node.scopePaths.forEach { path ->
        InputChip(
            selected = false,
            onClick = {
                controller.updateNode(UpdateIntentNodeRequest(projectId, nodeId, scopePaths = node.scopePaths - path))
            },
            label = { Text(path) },
            trailingIcon = { Icon(Icons.Default.Close, contentDescription = "Remove $path") },
        )
    }
    AddLineField(
        label = "Add scope path",
        buttonText = "Add",
        onAdd = { raw ->
            val path = raw.trim()
            if (path.isNotEmpty() && path !in node.scopePaths) {
                controller.updateNode(UpdateIntentNodeRequest(projectId, nodeId, scopePaths = node.scopePaths + path))
            }
        },
    )

    // Dependencies: what this node waits for.
    SectionTitle("Depends on")
    dependencyIds.mapNotNull { byId[it] }.forEach { dep ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = dep.title + if (dep.id in unmetIds) " (not done)" else "",
                style = AidosTypography.baseTypography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { controller.removeEdge(nodeId, dep.id, IntentEdgeKind.DEPENDS_ON) }) {
                Text("Remove")
            }
        }
    }
    DependencyPicker(
        snapshot = snapshot,
        nodeId = nodeId,
        excludedIds = dependencyIds.toSet(),
        onPick = { other -> controller.addEdge(nodeId, other, IntentEdgeKind.DEPENDS_ON) },
    )

    // Agent cards the runtime stored for this node (RFC-0124 generative layer, UI-51).
    val cards = snapshot.manifests[nodeId].orEmpty()
    if (cards.isNotEmpty()) {
        SectionTitle("Agent cards")
        GenerativeCards(records = cards, density = A2uiDensity.REGULAR)
    }

    // Acceptance criteria and constraints.
    SectionTitle("Acceptance criteria")
    if (state.criteria.isEmpty()) Text("None yet.", style = AidosTypography.baseTypography.bodySmall)
    state.criteria.forEach { Text("• ${it.title}", style = AidosTypography.baseTypography.bodyMedium) }
    AddLineField(
        label = "New criterion",
        buttonText = "Add criterion",
        onAdd = { raw ->
            val title = raw.trim()
            if (title.isNotEmpty()) {
                controller.createNode(
                    CreateIntentNodeRequest(
                        projectId = projectId,
                        title = title,
                        type = IntentNodeType.ACCEPTANCE_CRITERION,
                        parentId = nodeId,
                    ),
                )
            }
        },
    )
    if (state.constraints.isNotEmpty()) {
        SectionTitle("Constraints")
        state.constraints.forEach { Text("• ${it.title}", style = AidosTypography.baseTypography.bodyMedium) }
    }
    if (state.dependents.isNotEmpty()) {
        SectionTitle("Needed by")
        state.dependents.forEach { Text("• ${it.title}", style = AidosTypography.baseTypography.bodyMedium) }
    }
    if (state.activeRunIds.isNotEmpty()) {
        Text("Active runs: ${state.activeRunIds.size}", style = AidosTypography.baseTypography.bodySmall)
    }

    // Actions. A disabled action says why, in one line.
    Spacer(Modifier.height(SpacingTokens.xs))
    SectionTitle("Actions")
    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
        Button(enabled = state.canStart, onClick = { controller.startNodeRun(nodeId) }) { Text("Start") }
        Button(enabled = state.canComplete, onClick = { controller.completeHumanNode(nodeId) }) { Text("Complete") }
        Button(enabled = state.canConfirm, onClick = { controller.confirmImplementation(nodeId) }) { Text("Confirm") }
    }
    listOfNotNull(
        state.startBlockedReason?.let { "Start: $it" },
        state.completeBlockedReason?.takeIf { node.actorKind == IntentActorKind.HUMAN }?.let { "Complete: $it" },
        state.confirmBlockedReason?.let { "Confirm: $it" },
    ).forEach { Text(it, style = AidosTypography.baseTypography.bodySmall) }

    var showArchive by remember(nodeId) { mutableStateOf(false) }
    Row(horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
        OutlinedButton(onClick = {
            controller.createNode(
                CreateIntentNodeRequest(projectId = projectId, title = "New task", type = IntentNodeType.ATOMIC_TASK, parentId = nodeId),
            )
        }) { Text("Add child task") }
        OutlinedButton(onClick = { showArchive = true }) { Text("Archive") }
    }
    if (showArchive) {
        AlertDialog(
            onDismissRequest = { showArchive = false },
            title = { Text("Archive \"${node.title}\"?") },
            text = { Text("It leaves the graph, and its links stop counting.") },
            confirmButton = {
                TextButton(onClick = {
                    showArchive = false
                    controller.archiveNode(nodeId)
                    onClose()
                }) { Text("Archive") }
            },
            dismissButton = { TextButton(onClick = { showArchive = false }) { Text("Cancel") } },
        )
    }
}

private val DUE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}(T.*)?""")

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = AidosTypography.baseTypography.labelLarge,
        color = ColorTokens.StatusStale,
        modifier = Modifier.padding(top = SpacingTokens.sm),
    )
}

/**
 * A field that is saved when the person presses Done or leaves it. An unchanged value or an
 * invalid one saves nothing.
 */
@Composable
private fun SavedTextField(
    value: String,
    label: String,
    onSave: (String) -> Unit,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    validate: (String) -> String? = { null },
) {
    var text by remember(value) { mutableStateOf(value) }
    var focused by remember { mutableStateOf(false) }
    val error = validate(text)
    val commit = {
        if (text != value && error == null) onSave(text)
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it },
        label = { Text(label) },
        singleLine = singleLine,
        isError = error != null,
        supportingText = { if (error != null) Text(error) },
        keyboardOptions = KeyboardOptions(
            keyboardType = keyboardType,
            imeAction = if (singleLine) ImeAction.Done else ImeAction.Default,
        ),
        keyboardActions = KeyboardActions(onDone = { commit() }),
        modifier = Modifier.fillMaxWidth().onFocusChanged { state ->
            if (focused && !state.isFocused) commit()
            focused = state.isFocused
        },
    )
}

@Composable
private fun <T> EnumPicker(
    label: String,
    current: T?,
    options: List<Pair<T?, String>>,
    onPick: (T?) -> Unit,
) {
    var open by remember { mutableStateOf(false) }
    val currentText = options.firstOrNull { it.first == current }?.second ?: "Not set"
    Box {
        OutlinedButton(onClick = { open = true }) { Text("$label: $currentText") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { (value, text) ->
                DropdownMenuItem(
                    text = { Text(text) },
                    onClick = {
                        open = false
                        onPick(value)
                    },
                )
            }
        }
    }
}

/** A single text field with a button that hands the trimmed text to [onAdd] and clears itself. */
@Composable
private fun AddLineField(label: String, buttonText: String, onAdd: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(SpacingTokens.xs)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text(label) },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = {
            onAdd(text)
            text = ""
        }) { Text(buttonText) }
    }
}

/** Searchable list of other active nodes; picking one adds a DEPENDS_ON link from this node. */
@Composable
private fun DependencyPicker(
    snapshot: GraphSnapshot,
    nodeId: String,
    excludedIds: Set<String>,
    onPick: (String) -> Unit,
) {
    var query by remember { mutableStateOf("") }
    val matches = snapshot.nodes
        .filter { it.id != nodeId && it.lifecycle == IntentLifecycle.ACTIVE && it.id !in excludedIds }
        .filter { query.isBlank() || it.title.contains(query.trim(), ignoreCase = true) }
        .take(6)
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        label = { Text("Add dependency (search)") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
    )
    matches.forEach { candidate ->
        TextButton(onClick = {
            onPick(candidate.id)
            query = ""
        }) { Text("+ ${candidate.title}") }
    }
}

private fun IntentNodeType.label(): String = when (this) {
    IntentNodeType.GROUP -> "Group"
    IntentNodeType.GOAL -> "Goal"
    IntentNodeType.SUB_GOAL -> "Sub-goal"
    IntentNodeType.ATOMIC_TASK -> "Task"
    IntentNodeType.CONSTRAINT -> "Constraint"
    IntentNodeType.ACCEPTANCE_CRITERION -> "Criterion"
}

private fun IntentActorKind.label(): String = when (this) {
    IntentActorKind.HUMAN -> "Human"
    IntentActorKind.AGENT -> "Agent"
    IntentActorKind.HYBRID -> "Hybrid"
}

private fun DerivedStatus.label(): String = name.lowercase().replace('_', ' ').replaceFirstChar { it.uppercase() }
