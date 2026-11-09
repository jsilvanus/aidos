package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.ui.McpAdoptionFilter
import fi.italeino.aidos.engine.ui.McpServerUiModel
import fi.italeino.aidos.engine.ui.McpToolUiModel
import fi.italeino.aidos.engine.ui.McpTransportFilter
import fi.italeino.aidos.engine.ui.McpUiState
import fi.italeino.aidos.engine.ui.ParsedMcpServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Presenter model for MCP servers & tool adoption in the Desktop App (RFC-0031).
 */
class DesktopMcpModel(
    @Suppress("UNUSED_PARAMETER") private val host: DesktopEngineHost,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(McpUiState(servers = initialServers()))
    val state: StateFlow<McpUiState> = _state.asStateFlow()

    fun addServer(config: ParsedMcpServerConfig) {
        val serverId = config.name.lowercase().replace(" ", "-")
        val sampleTools = generateCatalogForServer(serverId, config.name, config.transport)
        val adoptedTools = sampleTools.filter { it.isAdopted }

        val newServer = McpServerUiModel(
            id = serverId,
            name = config.name,
            transport = config.transport,
            command = config.command,
            args = config.args,
            endpointUrl = config.endpointUrl,
            profiles = config.profiles,
            secretRefs = config.envOrHeaders.mapValues { "vault-ref-${it.key.lowercase()}" },
            status = "Configured",
            autoRestart = true,
            toolCount = sampleTools.size,
            adoptedCount = adoptedTools.size,
        )

        _state.update { curr ->
            curr.copy(servers = curr.servers.filter { it.id != serverId } + newServer)
        }
    }

    fun deleteServer(serverId: String) {
        _state.update { curr ->
            val updatedServers = curr.servers.filter { it.id != serverId }
            curr.copy(
                servers = updatedServers,
                selectedServerId = if (curr.selectedServerId == serverId) null else curr.selectedServerId,
                selectedCatalog = if (curr.selectedServerId == serverId) emptyList() else curr.selectedCatalog,
            )
        }
    }

    fun selectServer(serverId: String?) {
        if (serverId == null) {
            _state.update { it.copy(selectedServerId = null, selectedCatalog = emptyList()) }
            return
        }

        val server = _state.value.servers.find { it.id == serverId } ?: return
        _state.update { it.copy(selectedServerId = serverId, isLoadingCatalog = true) }

        scope.launch {
            val catalog = generateCatalogForServer(serverId, server.name, server.transport)
            _state.update { it.copy(selectedCatalog = catalog, isLoadingCatalog = false) }
        }
    }

    fun toggleAdoption(serverId: String, toolName: String, shouldAdopt: Boolean) {
        _state.update { curr ->
            val updatedCatalog = curr.selectedCatalog.map { tool ->
                if (tool.name == toolName && tool.serverId == serverId) {
                    tool.copy(
                        isAdopted = shouldAdopt,
                        adoptedAt = if (shouldAdopt) java.time.Instant.now().toString() else null,
                    )
                } else tool
            }

            val adoptedCount = updatedCatalog.count { it.isAdopted }
            val updatedServers = curr.servers.map { s ->
                if (s.id == serverId) s.copy(adoptedCount = adoptedCount, toolCount = updatedCatalog.size) else s
            }

            curr.copy(servers = updatedServers, selectedCatalog = updatedCatalog)
        }
    }

    fun adoptAll(serverId: String) {
        _state.update { curr ->
            val updatedCatalog = curr.selectedCatalog.map {
                if (it.serverId == serverId) it.copy(isAdopted = true, adoptedAt = java.time.Instant.now().toString()) else it
            }
            val updatedServers = curr.servers.map { s ->
                if (s.id == serverId) s.copy(adoptedCount = updatedCatalog.size, toolCount = updatedCatalog.size) else s
            }
            curr.copy(servers = updatedServers, selectedCatalog = updatedCatalog)
        }
    }

    fun unadoptAll(serverId: String) {
        _state.update { curr ->
            val updatedCatalog = curr.selectedCatalog.map {
                if (it.serverId == serverId) it.copy(isAdopted = false, adoptedAt = null) else it
            }
            val updatedServers = curr.servers.map { s ->
                if (s.id == serverId) s.copy(adoptedCount = 0, toolCount = updatedCatalog.size) else s
            }
            curr.copy(servers = updatedServers, selectedCatalog = updatedCatalog)
        }
    }

    fun refreshCatalog(serverId: String) {
        selectServer(serverId)
    }

    fun search(query: String) {
        _state.update { it.copy(searchQuery = query) }
    }

    fun setTransportFilter(filter: McpTransportFilter) {
        _state.update { it.copy(transportFilter = filter) }
    }

    fun setAdoptionFilter(filter: McpAdoptionFilter) {
        _state.update { it.copy(adoptionFilter = filter) }
    }

    private fun initialServers(): List<McpServerUiModel> {
        return listOf(
            McpServerUiModel(
                id = "filesystem",
                name = "filesystem",
                transport = "stdio",
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-filesystem", "C:\\Users\\jsilv\\Code"),
                profiles = setOf("DESKTOP"),
                secretRefs = emptyMap(),
                status = "Ready",
                toolCount = 4,
                adoptedCount = 3,
            ),
            McpServerUiModel(
                id = "remote-docs",
                name = "remote-docs",
                transport = "http",
                endpointUrl = "https://mcp.knowledge.internal/sse",
                profiles = setOf("DESKTOP", "MOBILE"),
                secretRefs = mapOf("Authorization" to "vault-docs-token"),
                status = "Connected",
                toolCount = 2,
                adoptedCount = 2,
            ),
        )
    }

    private fun generateCatalogForServer(serverId: String, name: String, transport: String): List<McpToolUiModel> {
        return when (serverId.lowercase()) {
            "filesystem" -> listOf(
                McpToolUiModel(
                    name = "read_file",
                    serverId = name,
                    description = "Read the contents of a file at the specified path within allowed directories.",
                    inputSchemaJson = """{"type":"object","properties":{"path":{"type":"string","description":"Absolute path of the file"}},"required":["path"]}""",
                    isAdopted = true,
                    descriptorHash = computeHash("$name:read_file:v1"),
                ),
                McpToolUiModel(
                    name = "write_file",
                    serverId = name,
                    description = "Create or overwrite a file with UTF-8 text content.",
                    inputSchemaJson = """{"type":"object","properties":{"path":{"type":"string"},"content":{"type":"string"}},"required":["path","content"]}""",
                    isAdopted = true,
                    descriptorHash = computeHash("$name:write_file:v1"),
                ),
                McpToolUiModel(
                    name = "list_directory",
                    serverId = name,
                    description = "List entries contained inside a folder.",
                    inputSchemaJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
                    isAdopted = true,
                    descriptorHash = computeHash("$name:list_directory:v1"),
                ),
                McpToolUiModel(
                    name = "delete_file",
                    serverId = name,
                    description = "Permanently remove a file from disk.",
                    inputSchemaJson = """{"type":"object","properties":{"path":{"type":"string"}},"required":["path"]}""",
                    isAdopted = false,
                    descriptorHash = computeHash("$name:delete_file:v1"),
                ),
            )
            "remote-docs" -> listOf(
                McpToolUiModel(
                    name = "search_knowledge_base",
                    serverId = name,
                    description = "Perform semantic vector search across engineering documentation.",
                    inputSchemaJson = """{"type":"object","properties":{"query":{"type":"string"},"topK":{"type":"integer"}},"required":["query"]}""",
                    isAdopted = true,
                    descriptorHash = computeHash("$name:search_knowledge_base:v1"),
                ),
                McpToolUiModel(
                    name = "get_document_by_id",
                    serverId = name,
                    description = "Fetch full markdown document by its unique content ID.",
                    inputSchemaJson = """{"type":"object","properties":{"docId":{"type":"string"}},"required":["docId"]}""",
                    isAdopted = true,
                    descriptorHash = computeHash("$name:get_document_by_id:v1"),
                ),
            )
            else -> listOf(
                McpToolUiModel(
                    name = "execute_operation",
                    serverId = name,
                    description = "Custom tool provided by $name ($transport).",
                    inputSchemaJson = """{"type":"object","properties":{"input":{"type":"string"}},"required":["input"]}""",
                    isAdopted = false,
                    descriptorHash = computeHash("$name:execute_operation:v1"),
                )
            )
        }
    }

    private fun computeHash(input: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(input.toByteArray())
        return digest.take(16).joinToString("") { "%02x".format(it) }
    }
}
