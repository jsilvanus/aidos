package fi.italeino.aidos.engine.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aidos.tcr.mcp.McpAdoptionFilter as TcrAdoptionFilter
import dev.aidos.tcr.mcp.McpServerUiModel as TcrServer
import dev.aidos.tcr.mcp.McpServersPane as TcrMcpServersPane
import dev.aidos.tcr.mcp.McpToolUiModel as TcrTool
import dev.aidos.tcr.mcp.McpTransportFilter as TcrTransportFilter
import dev.aidos.tcr.mcp.McpUiState as TcrState
import dev.aidos.tcr.mcp.ParsedMcpServerConfig as TcrParsedConfig

/**
 * MCP Server & Tool Adoption Management Pane (RFC-0031, RFC-0054): binds the host's MCP state to the
 * TCR `McpServersPane`, which owns all rendering (D46). The models are structurally identical, so
 * this only maps between the host's and the TCR's types.
 */
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
    TcrMcpServersPane(
        state = state.toTcr(),
        onAddServer = { onAddServer(it.toHost()) },
        onDeleteServer = onDeleteServer,
        onSelectServer = onSelectServer,
        onToggleAdoption = onToggleAdoption,
        onAdoptAll = onAdoptAll,
        onUnadoptAll = onUnadoptAll,
        onRefreshCatalog = onRefreshCatalog,
        onSearchQueryChange = onSearchQueryChange,
        onTransportFilterChange = { onTransportFilterChange(McpTransportFilter.valueOf(it.name)) },
        onAdoptionFilterChange = { onAdoptionFilterChange(McpAdoptionFilter.valueOf(it.name)) },
        modifier = modifier,
    )
}

private fun McpUiState.toTcr() = TcrState(
    servers = servers.map { it.toTcr() },
    selectedServerId = selectedServerId,
    selectedCatalog = selectedCatalog.map { it.toTcr() },
    isLoadingCatalog = isLoadingCatalog,
    searchQuery = searchQuery,
    transportFilter = TcrTransportFilter.valueOf(transportFilter.name),
    adoptionFilter = TcrAdoptionFilter.valueOf(adoptionFilter.name),
    message = message,
)

private fun McpServerUiModel.toTcr() = TcrServer(
    id = id, name = name, transport = transport, command = command, args = args, endpointUrl = endpointUrl,
    packageName = packageName, serviceClass = serviceClass, signingCertSha256 = signingCertSha256,
    profiles = profiles, secretRefs = secretRefs, status = status, autoRestart = autoRestart,
    toolCount = toolCount, adoptedCount = adoptedCount,
)

private fun McpToolUiModel.toTcr() = TcrTool(
    name = name, serverId = serverId, description = description, inputSchemaJson = inputSchemaJson,
    isAdopted = isAdopted, descriptorHash = descriptorHash, adoptedAt = adoptedAt,
)

private fun TcrParsedConfig.toHost() = ParsedMcpServerConfig(
    name = name, transport = transport, command = command, args = args, endpointUrl = endpointUrl,
    packageName = packageName, serviceClass = serviceClass, signingCertSha256 = signingCertSha256,
    envOrHeaders = envOrHeaders, profiles = profiles,
)
