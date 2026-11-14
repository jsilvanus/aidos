package fi.italeino.aidos.engine.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Security
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

private val StdioColor = Color(0xFF10B981) // Emerald Green
private val HttpColor = Color(0xFF6366F1)  // Indigo
private val BinderColor = Color(0xFF3B82F6) // Electric Blue
private val AdoptedColor = Color(0xFF22C55E) // Green
private val UnadoptedColor = Color(0xFF94A3B8) // Slate Gray

/**
 * MCP Server & Tool Adoption Management Pane (RFC-0031, RFC-0054).
 *
 * Allows users to:
 * 1. Inspect registered MCP servers (stdio & streamable http)
 * 2. Add servers via interactive form or by pasting standard Claude/Cursor/Antigravity JSON
 * 3. Inspect server tool catalogs and selectively adopt/unadopt tools with descriptor-hash tracking
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun McpServersPane(
    state: McpUiState,
    onAddServer: (ParsedMcpServerConfig) -> Unit,
    onDeleteServer: (serverId: String) -> Unit,
    onSelectServer: (serverId: String?) -> Unit,
    onToggleAdoption: (serverId: String, toolName: String, shouldAdopt: Boolean) -> Unit,
    onAdoptAll: (serverId: String) -> Unit,
    onUnadoptAll: (serverId: String) -> Unit,
    onRefreshCatalog: (serverId: String) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onTransportFilterChange: (McpTransportFilter) -> Unit,
    onAdoptionFilterChange: (McpAdoptionFilter) -> Unit,
    modifier: Modifier = Modifier,
) {
    var showAddDialog by remember { mutableStateOf(false) }

    val filteredServers = remember(state.servers, state.searchQuery, state.transportFilter) {
        state.servers.filter { server ->
            val matchesSearch = state.searchQuery.isBlank() ||
                server.name.contains(state.searchQuery, ignoreCase = true) ||
                (server.command?.contains(state.searchQuery, ignoreCase = true) == true) ||
                (server.endpointUrl?.contains(state.searchQuery, ignoreCase = true) == true) ||
                (server.packageName?.contains(state.searchQuery, ignoreCase = true) == true) ||
                (server.serviceClass?.contains(state.searchQuery, ignoreCase = true) == true)

            val matchesTransport = when (state.transportFilter) {
                McpTransportFilter.ALL -> true
                McpTransportFilter.STDIO -> server.transport.equals("stdio", ignoreCase = true)
                McpTransportFilter.HTTP -> server.transport.equals("http", ignoreCase = true)
                McpTransportFilter.BINDER -> server.transport.equals("binder", ignoreCase = true)
            }

            matchesSearch && matchesTransport
        }
    }

    val selectedServer = remember(state.servers, state.selectedServerId) {
        state.servers.find { it.id == state.selectedServerId }
    }

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        // ── Top Summary & Action Bar ───────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column {
                Text(
                    text = "MCP Servers & Tools",
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = "Manage external tool providers and adopt approved operations (RFC-0031)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Button(
                onClick = { showAddDialog = true },
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(8.dp),
            ) {
                Icon(Icons.Default.Add, contentDescription = "Add MCP Server", modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Add MCP Server", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Metrics Row ────────────────────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            val totalServers = state.servers.size
            val stdioCount = state.servers.count { it.transport.equals("stdio", ignoreCase = true) }
            val httpCount = state.servers.count { it.transport.equals("http", ignoreCase = true) }
            val binderCount = state.servers.count { it.transport.equals("binder", ignoreCase = true) }
            val totalAdopted = state.servers.sumOf { it.adoptedCount }

            McpMetricCard(title = "Total Servers", value = totalServers.toString(), subtitle = "Registered", modifier = Modifier.weight(1f))
            McpMetricCard(title = "stdio (Local)", value = stdioCount.toString(), subtitle = "Subprocess", color = StdioColor, modifier = Modifier.weight(1f))
            McpMetricCard(title = "HTTP (Remote)", value = httpCount.toString(), subtitle = "Streamable SSE", color = HttpColor, modifier = Modifier.weight(1f))
            if (binderCount > 0) {
                McpMetricCard(title = "Binder (App)", value = binderCount.toString(), subtitle = "Android IPC", color = BinderColor, modifier = Modifier.weight(1f))
            }
            McpMetricCard(title = "Adopted Tools", value = totalAdopted.toString(), subtitle = "Active in LLM", color = AdoptedColor, modifier = Modifier.weight(1f))
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Search & Filter Controls ───────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = state.searchQuery,
                onValueChange = onSearchQueryChange,
                placeholder = { Text("Search servers or tools...", fontSize = 12.sp) },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(16.dp)) },
                modifier = Modifier.weight(1f).height(48.dp),
                singleLine = true,
                shape = RoundedCornerShape(8.dp),
            )

            // Transport Filter Chips
            FilterChip(
                selected = state.transportFilter == McpTransportFilter.ALL,
                onClick = { onTransportFilterChange(McpTransportFilter.ALL) },
                label = { Text("All", fontSize = 11.sp) },
                shape = RoundedCornerShape(8.dp),
            )
            FilterChip(
                selected = state.transportFilter == McpTransportFilter.STDIO,
                onClick = { onTransportFilterChange(McpTransportFilter.STDIO) },
                label = { Text("stdio", fontSize = 11.sp) },
                shape = RoundedCornerShape(8.dp),
            )
            FilterChip(
                selected = state.transportFilter == McpTransportFilter.HTTP,
                onClick = { onTransportFilterChange(McpTransportFilter.HTTP) },
                label = { Text("HTTP", fontSize = 11.sp) },
                shape = RoundedCornerShape(8.dp),
            )
            FilterChip(
                selected = state.transportFilter == McpTransportFilter.BINDER,
                onClick = { onTransportFilterChange(McpTransportFilter.BINDER) },
                label = { Text("Binder", fontSize = 11.sp) },
                shape = RoundedCornerShape(8.dp),
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        // ── Main Content Area ──────────────────────────────────────────────────
        if (selectedServer != null) {
            // Detailed Catalog & Adoption View for Selected Server
            McpToolCatalogView(
                server = selectedServer,
                catalog = state.selectedCatalog,
                isLoading = state.isLoadingCatalog,
                adoptionFilter = state.adoptionFilter,
                onBack = { onSelectServer(null) },
                onToggleAdoption = { toolName, shouldAdopt -> onToggleAdoption(selectedServer.id, toolName, shouldAdopt) },
                onAdoptAll = { onAdoptAll(selectedServer.id) },
                onUnadoptAll = { onUnadoptAll(selectedServer.id) },
                onRefreshCatalog = { onRefreshCatalog(selectedServer.id) },
                onAdoptionFilterChange = onAdoptionFilterChange,
            )
        } else {
            // Server List View
            if (filteredServers.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = if (state.servers.isEmpty()) "No MCP servers configured yet." else "No servers match search filter.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 14.sp,
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedButton(
                            onClick = { showAddDialog = true },
                            shape = RoundedCornerShape(8.dp),
                        ) {
                            Text("Configure First MCP Server")
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(filteredServers, key = { it.id }) { server ->
                        McpServerCard(
                            server = server,
                            onInspectTools = { onSelectServer(server.id) },
                            onDelete = { onDeleteServer(server.id) },
                        )
                    }
                }
            }
        }
    }

    if (showAddDialog) {
        AddMcpServerDialog(
            onDismiss = { showAddDialog = false },
            onAdd = { config ->
                onAddServer(config)
                showAddDialog = false
            },
        )
    }
}

// ============================================================================
// Server Card Component
// ============================================================================

@Composable
private fun McpServerCard(
    server: McpServerUiModel,
    onInspectTools: () -> Unit,
    onDelete: () -> Unit,
) {
    val isStdio = server.transport.equals("stdio", ignoreCase = true)
    val isBinder = server.transport.equals("binder", ignoreCase = true)
    val transportColor = when {
        isBinder -> BinderColor
        isStdio -> StdioColor
        else -> HttpColor
    }
    val transportLabel = when {
        isBinder -> "BINDER IPC"
        isStdio -> "STDIO"
        else -> "HTTP SSE"
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = server.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .background(transportColor.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                            .border(1.dp, transportColor, shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = transportLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = transportColor,
                            fontFamily = FontFamily.Monospace,
                        )
                    }

                    Spacer(modifier = Modifier.width(6.dp))
                    server.profiles.forEach { profile ->
                        Box(
                            modifier = Modifier
                                .background(MaterialTheme.colorScheme.outline.copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp))
                                .padding(horizontal = 5.dp, vertical = 2.dp),
                        ) {
                            Text(text = profile, fontSize = 9.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Spacer(modifier = Modifier.width(4.dp))
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onDelete, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete Server", tint = Color(0xFFEF4444), modifier = Modifier.size(16.dp))
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Command / Endpoint / Package Preview in Monospace
            SelectionContainer {
                Text(
                    text = when {
                        isBinder -> "${server.packageName.orEmpty()} / ${server.serviceClass?.substringAfterLast('.').orEmpty()}"
                        isStdio -> "${server.command.orEmpty()} ${server.args.joinToString(" ")}"
                        else -> server.endpointUrl.orEmpty()
                    },
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Adoption status tally
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Security, contentDescription = null, tint = AdoptedColor, modifier = Modifier.size(14.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "${server.adoptedCount} of ${server.toolCount} tools adopted",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                OutlinedButton(
                    onClick = onInspectTools,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    Text("Inspect & Adopt Tools", fontSize = 12.sp)
                }
            }
        }
    }
}

// ============================================================================
// Tool Catalog & Adoption View
// ============================================================================

@Composable
private fun McpToolCatalogView(
    server: McpServerUiModel,
    catalog: List<McpToolUiModel>,
    isLoading: Boolean,
    adoptionFilter: McpAdoptionFilter,
    onBack: () -> Unit,
    onToggleAdoption: (toolName: String, shouldAdopt: Boolean) -> Unit,
    onAdoptAll: () -> Unit,
    onUnadoptAll: () -> Unit,
    onRefreshCatalog: () -> Unit,
    onAdoptionFilterChange: (McpAdoptionFilter) -> Unit,
) {
    val filteredTools = remember(catalog, adoptionFilter) {
        when (adoptionFilter) {
            McpAdoptionFilter.ALL -> catalog
            McpAdoptionFilter.ADOPTED -> catalog.filter { it.isAdopted }
            McpAdoptionFilter.AVAILABLE -> catalog.filter { !it.isAdopted }
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Sub-header navigation
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = onBack,
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                ) {
                    Text("← Back to Servers", fontSize = 12.sp)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "${server.name} Tools Catalog",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                )
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onRefreshCatalog, enabled = !isLoading) {
                    if (isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Default.Refresh, contentDescription = "Refresh Catalog", modifier = Modifier.size(18.dp))
                    }
                }
                Spacer(modifier = Modifier.width(8.dp))
                OutlinedButton(onClick = onAdoptAll, shape = RoundedCornerShape(6.dp)) {
                    Text("Adopt All", fontSize = 11.sp)
                }
                Spacer(modifier = Modifier.width(6.dp))
                OutlinedButton(onClick = onUnadoptAll, shape = RoundedCornerShape(6.dp)) {
                    Text("Unadopt All", fontSize = 11.sp)
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        // Adoption Filter Chips
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = adoptionFilter == McpAdoptionFilter.ALL,
                onClick = { onAdoptionFilterChange(McpAdoptionFilter.ALL) },
                label = { Text("All (${catalog.size})", fontSize = 11.sp) },
                shape = RoundedCornerShape(8.dp),
            )
            FilterChip(
                selected = adoptionFilter == McpAdoptionFilter.ADOPTED,
                onClick = { onAdoptionFilterChange(McpAdoptionFilter.ADOPTED) },
                label = { Text("Adopted (${catalog.count { it.isAdopted }})", fontSize = 11.sp) },
                shape = RoundedCornerShape(8.dp),
            )
            FilterChip(
                selected = adoptionFilter == McpAdoptionFilter.AVAILABLE,
                onClick = { onAdoptionFilterChange(McpAdoptionFilter.AVAILABLE) },
                label = { Text("Available (${catalog.count { !it.isAdopted }})", fontSize = 11.sp) },
                shape = RoundedCornerShape(8.dp),
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (filteredTools.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxWidth().weight(1f),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = if (isLoading) "Querying MCP server tool catalog..." else "No tools found in catalog.",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(filteredTools, key = { it.name }) { tool ->
                    McpToolAdoptionCard(
                        tool = tool,
                        serverId = server.name,
                        onToggle = { onToggleAdoption(tool.name, !tool.isAdopted) },
                    )
                }
            }
        }
    }
}

@Composable
private fun McpToolAdoptionCard(
    tool: McpToolUiModel,
    serverId: String,
    onToggle: () -> Unit,
) {
    var expandedSchema by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (tool.isAdopted) {
                AdoptedColor.copy(alpha = 0.05f)
            } else {
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
            }
        ),
        border = if (tool.isAdopted) {
            CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(AdoptedColor.copy(alpha = 0.5f)))
        } else {
            CardDefaults.outlinedCardBorder()
        },
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = tool.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            fontFamily = FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        if (tool.isAdopted) {
                            Box(
                                modifier = Modifier
                                    .background(AdoptedColor.copy(alpha = 0.15f), shape = RoundedCornerShape(4.dp))
                                    .border(1.dp, AdoptedColor, shape = RoundedCornerShape(4.dp))
                                    .padding(horizontal = 6.dp, vertical = 2.dp),
                            ) {
                                Text(
                                    text = "ADOPTED",
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AdoptedColor,
                                    fontFamily = FontFamily.Monospace,
                                )
                            }
                        }
                    }

                    val desc = tool.description
                    if (!desc.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = desc,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }

                Button(
                    onClick = onToggle,
                    colors = if (tool.isAdopted) {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    } else {
                        ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    },
                    shape = RoundedCornerShape(6.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                ) {
                    if (tool.isAdopted) {
                        Text("Unadopt", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    } else {
                        Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(14.dp))
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("Adopt Tool", fontSize = 11.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Sandbox fencing and hash preview
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .background(Color(0xFF3B82F6).copy(alpha = 0.1f), shape = RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    ) {
                        Text(
                            text = "<tool_descriptor source=\"mcp:$serverId\" operation=\"${tool.name}\">",
                            fontSize = 10.sp,
                            fontFamily = FontFamily.Monospace,
                            color = Color(0xFF2563EB),
                        )
                    }
                }

                tool.descriptorHash?.let { hash ->
                    Text(
                        text = "hash: ${hash.take(12)}…",
                        fontSize = 10.sp,
                        fontFamily = FontFamily.Monospace,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            val schema = tool.inputSchemaJson
            // Input Schema dropdown
            if (!schema.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.clickable { expandedSchema = !expandedSchema },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        if (expandedSchema) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if (expandedSchema) "Hide Schema Parameters" else "View Schema Parameters",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (expandedSchema) {
                    Spacer(modifier = Modifier.height(4.dp))
                    SelectionContainer {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(Color(0xFF1E293B), shape = RoundedCornerShape(6.dp))
                                .padding(8.dp),
                        ) {
                            Text(
                                text = schema,
                                fontSize = 11.sp,
                                fontFamily = FontFamily.Monospace,
                                color = Color(0xFFE2E8F0),
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Add MCP Server Dialog (Form & Paste JSON Tabs)
// ============================================================================

@Composable
private fun AddMcpServerDialog(
    onDismiss: () -> Unit,
    onAdd: (ParsedMcpServerConfig) -> Unit,
) {
    var selectedTab by remember { mutableStateOf(0) } // 0 = Interactive Form, 1 = Paste JSON

    // Interactive Form States
    var name by remember { mutableStateOf("") }
    var transport by remember { mutableStateOf("stdio") } // "stdio", "http", or "binder"
    var command by remember { mutableStateOf("") }
    var argsText by remember { mutableStateOf("") }
    var endpointUrl by remember { mutableStateOf("") }
    var binderPackage by remember { mutableStateOf("") }
    var binderService by remember { mutableStateOf("") }
    var binderCertsText by remember { mutableStateOf("") }
    var envSecretsText by remember { mutableStateOf("") }
    var allowDesktop by remember { mutableStateOf(true) }
    var allowMobile by remember { mutableStateOf(false) }

    // Paste JSON States
    var pastedJson by remember { mutableStateOf("") }
    var parseError by remember { mutableStateOf<String?>(null) }
    var parsedPreview by remember { mutableStateOf<List<ParsedMcpServerConfig>>(emptyList()) }

    LaunchedEffect(pastedJson) {
        if (pastedJson.isNotBlank()) {
            when (val result = McpConfigJsonParser.parse(pastedJson)) {
                is McpJsonParseResult.Success -> {
                    parsedPreview = result.servers
                    parseError = null
                }
                is McpJsonParseResult.Error -> {
                    parsedPreview = emptyList()
                    parseError = result.message
                }
            }
        } else {
            parsedPreview = emptyList()
            parseError = null
        }
    }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(0.95f).heightIn(max = 680.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Add MCP Server",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close")
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                TabRow(selectedTabIndex = selectedTab) {
                    Tab(
                        selected = selectedTab == 0,
                        onClick = { selectedTab = 0 },
                        text = { Text("Interactive Form") },
                    )
                    Tab(
                        selected = selectedTab == 1,
                        onClick = { selectedTab = 1 },
                        text = { Text("Paste JSON") },
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                if (selectedTab == 0) {
                    // ── Interactive Form ───────────────────────────────────────
                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("Server Name / ID") },
                            placeholder = { Text("e.g. filesystem, github, weather") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                        )

                        Text("Transport Protocol", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = transport == "stdio",
                                onClick = {
                                    transport = "stdio"
                                    allowDesktop = true
                                },
                                label = { Text("stdio") },
                            )
                            FilterChip(
                                selected = transport == "http",
                                onClick = {
                                    transport = "http"
                                    allowMobile = true
                                },
                                label = { Text("HTTP (SSE)") },
                            )
                            FilterChip(
                                selected = transport == "binder",
                                onClick = {
                                    transport = "binder"
                                    allowDesktop = false
                                    allowMobile = true
                                },
                                label = { Text("Binder (Android)") },
                            )
                        }

                        if (transport == "stdio") {
                            OutlinedTextField(
                                value = command,
                                onValueChange = { command = it },
                                label = { Text("Executable Command") },
                                placeholder = { Text("e.g. npx, uvx, python, docker") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )

                            OutlinedTextField(
                                value = argsText,
                                onValueChange = { argsText = it },
                                label = { Text("Arguments (space-separated or JSON array)") },
                                placeholder = { Text("e.g. -y @modelcontextprotocol/server-filesystem C:\\data") },
                                modifier = Modifier.fillMaxWidth(),
                            )

                            OutlinedTextField(
                                value = envSecretsText,
                                onValueChange = { envSecretsText = it },
                                label = { Text("Environment Secrets (KEY=VALUE, one per line)") },
                                placeholder = { Text("API_KEY=sk-...\nGITHUB_TOKEN=ghp_...") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                            )
                        } else if (transport == "http") {
                            OutlinedTextField(
                                value = endpointUrl,
                                onValueChange = { endpointUrl = it },
                                label = { Text("Endpoint URL (HTTPS required)") },
                                placeholder = { Text("https://mcp.example.com/sse") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )

                            OutlinedTextField(
                                value = envSecretsText,
                                onValueChange = { envSecretsText = it },
                                label = { Text("Authorization Headers (HEADER=TOKEN, one per line)") },
                                placeholder = { Text("Authorization=Bearer sk-...") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                            )
                        } else {
                            OutlinedTextField(
                                value = binderPackage,
                                onValueChange = { binderPackage = it },
                                label = { Text("Android Package Name") },
                                placeholder = { Text("com.agenticmessenger.mcp.host") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )

                            OutlinedTextField(
                                value = binderService,
                                onValueChange = { binderService = it },
                                label = { Text("Exported Service Class") },
                                placeholder = { Text("com.agenticmessenger.mcp.android.AmpMcpBinderService") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                            )

                            OutlinedTextField(
                                value = binderCertsText,
                                onValueChange = { binderCertsText = it },
                                label = { Text("Signing Cert SHA-256 Pins (comma or newline separated)") },
                                placeholder = { Text("64-char hex SHA-256 digest(s)") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                            )
                        }

                        Text("Supported Platform Profiles", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(
                                checked = allowDesktop,
                                onCheckedChange = { allowDesktop = it },
                                enabled = transport != "binder",
                            )
                            Text("DESKTOP", fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(16.dp))
                            Checkbox(
                                checked = allowMobile,
                                onCheckedChange = { allowMobile = it },
                                enabled = transport != "binder" && transport != "stdio",
                            )
                            Text("MOBILE", fontSize = 12.sp)
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    val parsedBinderCerts = binderCertsText
                        .split(',', '\n', ';')
                        .map { it.trim().lowercase() }
                        .filter { it.length == 64 && it.all { c -> c in '0'..'9' || c in 'a'..'f' } }
                        .toSet()

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                val argsList = if (argsText.trim().startsWith("[")) {
                                    try {
                                        kotlinx.serialization.json.Json.decodeFromString<List<String>>(argsText)
                                    } catch (_: Exception) {
                                        argsText.split(" ").filter { it.isNotBlank() }
                                    }
                                } else {
                                    argsText.split(" ").filter { it.isNotBlank() }
                                }

                                val secrets = envSecretsText.lines()
                                    .map { it.trim() }
                                    .filter { it.contains("=") }
                                    .associate { line ->
                                        val idx = line.indexOf('=')
                                        line.substring(0, idx).trim() to line.substring(idx + 1).trim()
                                    }

                                val profiles = if (transport == "binder") {
                                    setOf("MOBILE")
                                } else {
                                    buildSet {
                                        if (allowDesktop) add("DESKTOP")
                                        if (allowMobile) add("MOBILE")
                                    }
                                }

                                onAdd(
                                    ParsedMcpServerConfig(
                                        name = name.trim(),
                                        transport = transport,
                                        command = if (transport == "stdio") command.trim() else null,
                                        args = argsList,
                                        endpointUrl = if (transport == "http") endpointUrl.trim() else null,
                                        packageName = if (transport == "binder") binderPackage.trim() else null,
                                        serviceClass = if (transport == "binder") binderService.trim() else null,
                                        signingCertSha256 = if (transport == "binder") parsedBinderCerts else emptySet(),
                                        envOrHeaders = secrets,
                                        profiles = profiles,
                                    )
                                )
                            },
                            enabled = name.isNotBlank() && (
                                (transport == "stdio" && command.isNotBlank()) ||
                                    (transport == "http" && endpointUrl.isNotBlank()) ||
                                    (transport == "binder" && binderPackage.isNotBlank() && binderService.isNotBlank() && parsedBinderCerts.isNotEmpty())
                                ),
                        ) {
                            Text("Add Server")
                        }
                    }
                } else {
                    // ── Paste JSON Mode ────────────────────────────────────────
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        Text(
                            text = "Paste standard Claude Desktop, Cursor, or Antigravity MCP server JSON:",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(modifier = Modifier.height(6.dp))

                        OutlinedTextField(
                            value = pastedJson,
                            onValueChange = { pastedJson = it },
                            placeholder = {
                                Text(
                                    text = "{\n  \"mcpServers\": {\n    \"filesystem\": {\n      \"command\": \"npx\",\n      \"args\": [\"-y\", \"@modelcontextprotocol/server-filesystem\", \"/path\"]\n    }\n  }\n}",
                                    fontSize = 11.sp,
                                    fontFamily = FontFamily.Monospace,
                                )
                            },
                            modifier = Modifier.fillMaxWidth().height(160.dp),
                            textStyle = androidx.compose.ui.text.TextStyle(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        if (parseError != null) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFFEF2F2), shape = RoundedCornerShape(6.dp))
                                    .padding(8.dp),
                            ) {
                                Text(text = parseError.orEmpty(), fontSize = 11.sp, color = Color(0xFFDC2626))
                            }
                        } else if (parsedPreview.isNotEmpty()) {
                            Text("Detected Server Configurations (${parsedPreview.size}):", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                parsedPreview.forEach { s ->
                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f), shape = RoundedCornerShape(6.dp))
                                            .padding(8.dp),
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(s.name, fontWeight = FontWeight.Bold, fontSize = 12.sp)
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text("[${s.transport.uppercase()}]", fontSize = 10.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.primary)
                                            }
                                            Text(
                                                when (s.transport) {
                                                    "stdio" -> "${s.command} ${s.args.joinToString(" ")}"
                                                    "binder" -> "${s.packageName.orEmpty()}/${s.serviceClass.orEmpty()}"
                                                    else -> s.endpointUrl.orEmpty()
                                                },
                                                fontSize = 11.sp,
                                                fontFamily = FontFamily.Monospace,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                    ) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                parsedPreview.forEach { onAdd(it) }
                            },
                            enabled = parsedPreview.isNotEmpty(),
                        ) {
                            Text(if (parsedPreview.size > 1) "Import All (${parsedPreview.size})" else "Import Server")
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// Top Metric Card
// ============================================================================

@Composable
private fun McpMetricCard(
    title: String,
    value: String,
    subtitle: String,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)),
        border = CardDefaults.outlinedCardBorder(),
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(text = title, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = color)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
