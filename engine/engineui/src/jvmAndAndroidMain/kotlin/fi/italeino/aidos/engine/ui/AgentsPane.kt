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

data class AgentOverviewUiState(
    val stats: AgentRuntimeOverviewStats = AgentRuntimeOverviewStats(),
    val runs: List<AgentRunUiModel> = emptyList(),
    val selectedAppFilter: String? = null,
    val selectedStatusFilter: AgentFilterTab = AgentFilterTab.ALL,
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
        appMatches && statusMatches
    }

    val availableApps = remember(state.runs) {
        state.runs.map { it.ownerApp }.distinct().sorted()
    }

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

        // 2. Filter Bar: Apps
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

        // 3. Filter Bar: Execution Status Tabs
        item {
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
        }

        // 4. Runs Feed Header
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
            }
        }

        // 5. Empty State or Runs List
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
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(text = "🤖", fontSize = 28.sp)
                        Text(
                            text = if (state.runs.isEmpty()) "No agent runs recorded yet" else "No agent runs match the selected filters",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.Gray
                        )
                        Text(
                            text = "Connected apps starting turns via /v1/agent will appear here in real-time.",
                            fontSize = 11.sp,
                            color = Color.LightGray
                        )
                    }
                }
            }
        } else {
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
            onCancelRun = onCancelRun
        )
    }
}

@Composable
private fun AgentInspectionDialog(
    run: AgentRunUiModel,
    onDismiss: () -> Unit,
    onCancelRun: (String) -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text(
                            text = "Agent Run Telemetry",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "Run ID: ${run.runId}",
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
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Model Placement Section
                    item {
                        Text(text = "Model Placement & Limits", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        AgentPlacementBadge(
                            modelId = run.modelId,
                            placement = run.placement,
                            locality = run.locality
                        )
                    }

                    // Outside Connections Section
                    item {
                        Text(text = "Network Egress Telemetry (RFC-0042)", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(4.dp))
                        AgentEgressSummary(egressSummary = run.egressSummary)
                    }

                    // Prompt Section
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

                    // Tool Calls Trace
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

                    // Final Reply
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
