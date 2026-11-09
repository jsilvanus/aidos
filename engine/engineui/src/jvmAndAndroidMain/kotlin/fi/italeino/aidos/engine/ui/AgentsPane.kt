package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import dev.aidos.tcr.agent.*
import dev.aidos.tcr.theme.TcrColors

enum class AgentFilterTab(val label: String) {
    ALL("All"),
    ACTIVE("Active"),
    AWAITING_TOOLS("Waiting on Tools"),
    COMPLETED("Completed"),
    FAILED("Failed / Cancelled")
}

enum class AgentViewLayout(val label: String) {
    HIERARCHY("🌳 Subagent Hierarchy"),
    FLAT("📋 Flat Feed")
}

enum class PlacementFilterOption(val label: String, val placement: AgentPlacement?) {
    ALL("All Placements", null),
    LOCAL("🟢 Local (Device)", AgentPlacement.LOCAL),
    CLUSTER("🔵 Cluster (Peer)", AgentPlacement.CLUSTER),
    REMOTE("🟣 Remote (Cloud)", AgentPlacement.REMOTE)
}

data class AgentOverviewUiState(
    val stats: AgentRuntimeOverviewStats = AgentRuntimeOverviewStats(),
    val runs: List<AgentRunUiModel> = emptyList(),
    val selectedAppFilter: String? = null,
    val selectedStatusFilter: AgentFilterTab = AgentFilterTab.ALL,
    val selectedPlacementFilter: AgentPlacement? = null,
    val selectedViewLayout: AgentViewLayout = AgentViewLayout.HIERARCHY,
    val searchQuery: String = "",
    val isEngineRunning: Boolean = true,
    val selectedRunId: String? = null,
)

/**
 * Shared Stateless Compose UI for Agent Runtime Control & Telemetry.
 * Consumed by both DesktopApp and Android MainActivity.
 */
@Composable
fun AgentsPane(
    state: AgentOverviewUiState,
    onAppFilterSelected: (String?) -> Unit,
    onStatusFilterSelected: (AgentFilterTab) -> Unit,
    onPlacementFilterSelected: (AgentPlacement?) -> Unit = {},
    onSearchQueryChanged: (String) -> Unit = {},
    onResetFilters: () -> Unit = {},
    onViewLayoutSelected: (AgentViewLayout) -> Unit = {},
    onCancelRun: (String) -> Unit,
    onInspectRun: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val selectedRun = state.selectedRunId?.let { id -> state.runs.firstOrNull { it.runId == id } }

    val filteredRuns = state.runs.filter { run ->
        val appMatches = state.selectedAppFilter == null || run.ownerApp == state.selectedAppFilter
        val statusMatches = when (state.selectedStatusFilter) {
            AgentFilterTab.ALL -> true
            AgentFilterTab.ACTIVE -> run.status == AgentRunStatus.RUNNING || run.status == AgentRunStatus.TOOL_CALLS
            AgentFilterTab.AWAITING_TOOLS -> run.status == AgentRunStatus.TOOL_CALLS
            AgentFilterTab.COMPLETED -> run.status == AgentRunStatus.COMPLETED
            AgentFilterTab.FAILED -> run.status == AgentRunStatus.FAILED || run.status == AgentRunStatus.CANCELLED
        }
        val placementMatches = state.selectedPlacementFilter == null || run.placement == state.selectedPlacementFilter
        val searchMatches = state.searchQuery.isBlank() || run.matchesSearch(state.searchQuery)
        appMatches && statusMatches && placementMatches && searchMatches
    }

    val availableApps = remember(state.runs) {
        state.runs.map { it.ownerApp }.distinct().sorted()
    }

    val isFilterActive = state.selectedAppFilter != null ||
        state.selectedStatusFilter != AgentFilterTab.ALL ||
        state.selectedPlacementFilter != null ||
        state.searchQuery.isNotBlank()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // 1. Overview Telemetry Banner
        item {
            AgentStatsBanner(stats = state.stats)
        }

        // 2. Search Field
        item {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChanged,
                placeholder = { Text("Search by run ID, prompt, tool, or brief...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = "Search", modifier = Modifier.size(18.dp)) },
                trailingIcon = {
                    if (state.searchQuery.isNotBlank()) {
                        IconButton(onClick = { onSearchQueryChanged("") }) {
                            Icon(Icons.Default.Close, contentDescription = "Clear search", modifier = Modifier.size(16.dp))
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = Color.LightGray.copy(alpha = 0.5f),
                )
            )
        }

        // 3. Filter Bar: Apps
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Initiating App",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    item {
                        FilterChip(
                            selected = state.selectedAppFilter == null,
                            onClick = { onAppFilterSelected(null) },
                            label = { Text("All Apps (${state.runs.size})", fontSize = 11.sp) }
                        )
                    }
                    items(availableApps) { app ->
                        val count = state.runs.count { it.ownerApp == app }
                        FilterChip(
                            selected = state.selectedAppFilter == app,
                            onClick = { onAppFilterSelected(app) },
                            label = { Text("$app ($count)", fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // 4. Filter Bar: Model Placement (RFC-0104)
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    text = "Model Placement (RFC-0104)",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.Gray
                )
                LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    items(PlacementFilterOption.entries) { option ->
                        FilterChip(
                            selected = state.selectedPlacementFilter == option.placement,
                            onClick = { onPlacementFilterSelected(option.placement) },
                            label = { Text(option.label, fontSize = 11.sp) }
                        )
                    }
                }
            }
        }

        // 5. Filter Bar: Execution Status & Topology Layout Tabs
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                // Status Filter
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "Execution Status",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        items(AgentFilterTab.entries) { tab ->
                            FilterChip(
                                selected = state.selectedStatusFilter == tab,
                                onClick = { onStatusFilterSelected(tab) },
                                label = { Text(tab.label, fontSize = 11.sp) }
                            )
                        }
                    }
                }

                // Topology Layout Toggle
                Column(verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                    Text(
                        text = "View Layout",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        AgentViewLayout.entries.forEach { layout ->
                            FilterChip(
                                selected = state.selectedViewLayout == layout,
                                onClick = { onViewLayoutSelected(layout) },
                                label = { Text(layout.label, fontSize = 11.sp) }
                            )
                        }
                    }
                }
            }
        }

        // 6. Active Filter Reset Bar (when filtering)
        if (isFilterActive) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f), RoundedCornerShape(6.dp))
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Showing ${filteredRuns.size} of ${state.runs.size} agent runs (Filtered)",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    TextButton(
                        onClick = onResetFilters,
                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                    ) {
                        Text("Reset Filters", fontSize = 11.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        // 7. Runs Feed Header
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Agent Runs (${filteredRuns.size})",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF111827)
                )
                if (state.selectedViewLayout == AgentViewLayout.HIERARCHY) {
                    val driverCount = filteredRuns.count { it.role == AgentRoleUi.DRIVER }
                    val workerCount = filteredRuns.count { it.role == AgentRoleUi.WORKER }
                    Text(
                        text = "$driverCount drivers · $workerCount delegated workers",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                }
            }
        }

        // 8. Empty State or Runs List
        if (filteredRuns.isEmpty()) {
            item {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(text = "🤖", fontSize = 28.sp)
                        Text(
                            text = if (state.runs.isEmpty()) "No agent runs recorded yet" else "No agent runs match the selected filters or search",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Gray
                        )
                        if (isFilterActive) {
                            Button(
                                onClick = onResetFilters,
                                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                            ) {
                                Text("Reset All Filters", fontSize = 12.sp)
                            }
                        } else {
                            Text(
                                text = "Connected apps starting turns via /v1/agent will appear here in real-time.",
                                fontSize = 11.sp,
                                color = Color.LightGray
                            )
                        }
                    }
                }
            }
        } else if (state.selectedViewLayout == AgentViewLayout.HIERARCHY) {
            // Topology Tree View: Driver Runs with nested Subagent Workers
            val drivers = filteredRuns.filter { it.role == AgentRoleUi.DRIVER }
            val allWorkers = filteredRuns.filter { it.role == AgentRoleUi.WORKER }
            val attachedWorkerIds = mutableSetOf<String>()

            drivers.forEach { driver ->
                item(key = driver.runId) {
                    AgentRunCard(
                        run = driver,
                        onCancel = onCancelRun,
                        onInspect = onInspectRun
                    )
                }

                // Subagent Workers under this Driver
                val childWorkers = allWorkers.filter { w ->
                    val pSession = w.parentSessionId
                    w.parentRunId == driver.runId ||
                    (pSession != null && driver.runId.contains(pSession.take(8))) ||
                    pSession == driver.runId
                }

                childWorkers.forEach { worker ->
                    attachedWorkerIds.add(worker.runId)
                    item(key = "tree_${driver.runId}_${worker.runId}") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 22.dp),
                            verticalAlignment = Alignment.Top
                        ) {
                            Text(
                                text = "└── ",
                                fontSize = 14.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color.Gray,
                                modifier = Modifier.padding(top = 10.dp)
                            )
                            Box(modifier = Modifier.weight(1f)) {
                                AgentRunCard(
                                    run = worker,
                                    onCancel = onCancelRun,
                                    onInspect = onInspectRun
                                )
                            }
                        }
                    }
                }
            }

            // Standalone or unattached workers
            val orphanWorkers = allWorkers.filterNot { attachedWorkerIds.contains(it.runId) }
            if (orphanWorkers.isNotEmpty()) {
                item {
                    Text(
                        text = "Independent / Unparented Workers",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.Gray,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                items(orphanWorkers, key = { it.runId }) { worker ->
                    AgentRunCard(
                        run = worker,
                        onCancel = onCancelRun,
                        onInspect = onInspectRun
                    )
                }
            }
        } else {
            // Flat List View
            items(filteredRuns, key = { it.runId }) { run ->
                AgentRunCard(
                    run = run,
                    onCancel = onCancelRun,
                    onInspect = onInspectRun
                )
            }
        }
    }

    // Detail Inspection Dialog
    if (selectedRun != null) {
        AgentInspectionDialog(
            run = selectedRun,
            onDismiss = { onInspectRun(null) },
            onCancelRun = onCancelRun,
            onInspectRun = onInspectRun
        )
    }
}

private fun AgentRunUiModel.matchesSearch(query: String): Boolean {
    val q = query.trim()
    if (q.isEmpty()) return true
    return runId.contains(q, ignoreCase = true) ||
        ownerApp.contains(q, ignoreCase = true) ||
        (modelId?.contains(q, ignoreCase = true) == true) ||
        promptPreview.contains(q, ignoreCase = true) ||
        replyText.contains(q, ignoreCase = true) ||
        (workerBrief?.contains(q, ignoreCase = true) == true) ||
        executedTools.any { it.toolName.contains(q, ignoreCase = true) || it.argumentsJson.contains(q, ignoreCase = true) } ||
        tasks.any { it.description.contains(q, ignoreCase = true) || (it.toolName?.contains(q, ignoreCase = true) == true) }
}

private fun formatDuration(startedAt: Long?, finishedAt: Long?): String {
    if (startedAt == null) return "Unknown"
    val end = finishedAt ?: System.currentTimeMillis()
    val millis = (end - startedAt).coerceAtLeast(0)
    val seconds = millis / 1000
    return when {
        seconds < 1 -> "${millis}ms"
        seconds < 60 -> "${seconds}s"
        else -> "${seconds / 60}m ${seconds % 60}s"
    }
}

@Composable
private fun AgentInspectionDialog(
    run: AgentRunUiModel,
    onDismiss: () -> Unit,
    onCancelRun: (String) -> Unit,
    onInspectRun: ((String) -> Unit)? = null
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header with Status Chip
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Agent Run Telemetry & Execution Graph",
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            val statusBg = when (run.status) {
                                AgentRunStatus.RUNNING -> TcrColors.ElectricBlue.copy(alpha = 0.15f)
                                AgentRunStatus.TOOL_CALLS -> TcrColors.AmberWarning.copy(alpha = 0.15f)
                                AgentRunStatus.COMPLETED -> TcrColors.EmeraldGreen.copy(alpha = 0.15f)
                                AgentRunStatus.FAILED, AgentRunStatus.CANCELLED -> TcrColors.EmberRed.copy(alpha = 0.15f)
                            }
                            val statusFg = when (run.status) {
                                AgentRunStatus.RUNNING -> TcrColors.ElectricBlue
                                AgentRunStatus.TOOL_CALLS -> TcrColors.AmberWarning
                                AgentRunStatus.COMPLETED -> TcrColors.EmeraldGreen
                                AgentRunStatus.FAILED, AgentRunStatus.CANCELLED -> TcrColors.EmberRed
                            }
                            Box(
                                modifier = Modifier
                                    .background(statusBg, RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                Text(
                                    text = run.status.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = statusFg
                                )
                            }
                        }
                        Text(
                            text = "Run ID: ${run.runId} · Owner: ${run.ownerApp}",
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color.Gray
                        )
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                HorizontalDivider()

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // Quick Orchestration Tree Navigation Jump Buttons
                    val parentRunId = run.parentRunId
                    if (run.role == AgentRoleUi.WORKER && parentRunId != null) {
                        item {
                            OutlinedButton(
                                onClick = { onInspectRun?.invoke(parentRunId) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("⬆ Jump to Parent Driver ($parentRunId)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    val awaitingChildRunId = run.awaitingChildRunId
                    if (run.isParkedOnChild && awaitingChildRunId != null) {
                        item {
                            FilledTonalButton(
                                onClick = { onInspectRun?.invoke(awaitingChildRunId) },
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("⬇ Jump to Delegated Worker ($awaitingChildRunId)", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                            }
                        }
                    }

                    // Execution Cadence & Timings Card
                    item {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(8.dp),
                            colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAFB))
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("Execution Lifecycle", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color.Gray)
                                    Text("Duration: ${formatDuration(run.startedAt, run.finishedAt)}", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                                }
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("Step Progress", fontSize = 10.sp, color = Color.DarkGray)
                                    Text("${run.steps} / ${run.maxSteps} steps", fontSize = 10.sp, fontWeight = FontWeight.Bold)
                                }
                                LinearProgressIndicator(
                                    progress = { (run.steps.toFloat() / run.maxSteps.coerceAtLeast(1)).coerceIn(0f, 1f) },
                                    modifier = Modifier.fillMaxWidth().height(4.dp),
                                    color = when (run.status) {
                                        AgentRunStatus.COMPLETED -> TcrColors.EmeraldGreen
                                        AgentRunStatus.FAILED, AgentRunStatus.CANCELLED -> TcrColors.EmberRed
                                        else -> TcrColors.ElectricBlue
                                    },
                                    trackColor = Color.LightGray.copy(alpha = 0.3f),
                                )
                            }
                        }
                    }

                    // 1. Role & Subagent Hierarchy Section
                    item {
                        Text(text = "Orchestration Topology (RFC-0011)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        AgentRoleBadge(
                            role = run.role,
                            parentSessionId = run.parentSessionId,
                            workerBrief = run.workerBrief
                        )
                    }

                    // 2. Model Placement Section
                    item {
                        Text(text = "Model Placement & Limits (RFC-0104)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        AgentPlacementBadge(
                            modelId = run.modelId,
                            placement = run.placement,
                            locality = run.locality
                        )
                    }

                    // 3. Execution Graph DAG (RFC-0019)
                    if (run.tasks.isNotEmpty()) {
                        item {
                            Text(text = "Execution Graph (RFC-0019 DAG & Edges)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            AgentTaskGraphView(
                                tasks = run.tasks,
                                workerSpawns = run.workerSpawns,
                                onInspectRun = onInspectRun
                            )
                        }
                    }

                    // 4. Delegated Budget & Spend Ceilings (RFC-0028)
                    val budget = run.budget
                    if (budget != null) {
                        item {
                            Text(text = "Delegated Budget & Quota (RFC-0028)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            AgentBudgetMeter(
                                budget = budget,
                                currentSteps = run.steps
                            )
                        }
                    }

                    // 5. Worker Outcome Commit (RFC-0011)
                    val workerOutcome = run.workerOutcome
                    if (workerOutcome != null) {
                        item {
                            Text(text = "Worker Git Commit Outcome (RFC-0011)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            WorkerOutcomeCard(outcome = workerOutcome)
                        }
                    }

                    // 6. Outside Connections Section
                    item {
                        Text(text = "Network Egress Telemetry (RFC-0042)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        AgentEgressSummary(egressSummary = run.egressSummary)
                    }

                    // 7. Prompt Section
                    item {
                        Text(text = "User / Peer Prompt", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color.Black.copy(alpha = 0.04f), RoundedCornerShape(6.dp))
                                .padding(10.dp)
                        ) {
                            Text(text = run.promptPreview, fontSize = 12.sp, color = Color(0xFF1F2937))
                        }
                    }

                    // 8. Tool Calls Trace
                    if (run.executedTools.isNotEmpty()) {
                        item {
                            Text(text = "Executed Tool Calls (${run.executedTools.size})", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                        items(run.executedTools) { tool ->
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(6.dp),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFFF9FAFB))
                            ) {
                                Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        text = "Tool: ${tool.toolName}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace,
                                        color = TcrColors.ElectricBlue
                                    )
                                    Text(
                                        text = "Arguments: ${tool.argumentsJson}",
                                        fontSize = 10.sp,
                                        fontFamily = FontFamily.Monospace,
                                        color = Color.DarkGray
                                    )
                                    Text(
                                        text = "Result: ${tool.resultText}",
                                        fontSize = 10.sp,
                                        color = Color.Gray
                                    )
                                }
                            }
                        }
                    }

                    // 9. Final Reply
                    if (run.replyText.isNotBlank()) {
                        item {
                            Text(text = "Agent Final Output", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(TcrColors.EmeraldGreen.copy(alpha = 0.05f), RoundedCornerShape(6.dp))
                                    .border(1.dp, TcrColors.EmeraldGreen.copy(alpha = 0.3f), RoundedCornerShape(6.dp))
                                    .padding(10.dp)
                            ) {
                                Text(text = run.replyText, fontSize = 12.sp, color = Color(0xFF065F46))
                            }
                        }
                    }
                }

                // Footer Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    if (!run.status.isTerminal) {
                        Button(
                            onClick = {
                                onCancelRun(run.runId)
                                onDismiss()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = TcrColors.EmberRed)
                        ) {
                            Text("Cancel Run", fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}
