package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * Android ViewModel managing MCP servers & tool adoption for [McpServersPane].
 */
class McpToolsViewModel : ViewModel() {
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

        viewModelScope.launch {
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
            McpServerUiModel(
                id = "weather-service",
                name = "weather-service",
                transport = "http",
                endpointUrl = "https://mcp.weather.internal/sse",
                profiles = setOf("DESKTOP", "MOBILE"),
                secretRefs = emptyMap(),
                status = "Ready",
                toolCount = 2,
                adoptedCount = 1,
            ),
        )
    }

    private fun generateCatalogForServer(serverId: String, name: String, transport: String): List<McpToolUiModel> {
        return when (serverId.lowercase()) {
            "remote-docs" -> listOf(
                McpToolUiModel(
                    name = "search_knowledge_base",
                    serverId = name,
                    description = "Perform semantic vector search across engineering documentation.",
                    inputSchemaJson = """{"type":"object","properties":{"query":{"type":"string"}},"required":["query"]}""",
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
            "weather-service" -> listOf(
                McpToolUiModel(
                    name = "get_current_weather",
                    serverId = name,
                    description = "Get real-time weather observations for coordinates or city name.",
                    inputSchemaJson = """{"type":"object","properties":{"location":{"type":"string"}},"required":["location"]}""",
                    isAdopted = true,
                    descriptorHash = computeHash("$name:get_current_weather:v1"),
                ),
                McpToolUiModel(
                    name = "get_weather_forecast",
                    serverId = name,
                    description = "Get 7-day hourly weather forecast.",
                    inputSchemaJson = """{"type":"object","properties":{"location":{"type":"string"},"days":{"type":"integer"}},"required":["location"]}""",
                    isAdopted = false,
                    descriptorHash = computeHash("$name:get_weather_forecast:v1"),
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
