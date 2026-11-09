package fi.italeino.aidos.engine.ui

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Representation of an MCP server registration for Compose UI (RFC-0031, RFC-0054).
 */
@Serializable
data class McpServerUiModel(
    val id: String,
    val name: String,
    val transport: String, // "stdio" or "http"
    val command: String? = null,
    val args: List<String> = emptyList(),
    val endpointUrl: String? = null,
    val profiles: Set<String> = setOf("DESKTOP"),
    val secretRefs: Map<String, String> = emptyMap(),
    val status: String = "Configured",
    val autoRestart: Boolean = true,
    val toolCount: Int = 0,
    val adoptedCount: Int = 0,
)

/**
 * Representation of an MCP tool operation for catalog inspection & adoption.
 */
@Serializable
data class McpToolUiModel(
    val name: String,
    val serverId: String,
    val description: String? = null,
    val inputSchemaJson: String? = null,
    val isAdopted: Boolean = false,
    val descriptorHash: String? = null,
    val adoptedAt: String? = null,
)

/**
 * UI State for the MCP management screen.
 */
data class McpUiState(
    val servers: List<McpServerUiModel> = emptyList(),
    val selectedServerId: String? = null,
    val selectedCatalog: List<McpToolUiModel> = emptyList(),
    val isLoadingCatalog: Boolean = false,
    val searchQuery: String = "",
    val transportFilter: McpTransportFilter = McpTransportFilter.ALL,
    val adoptionFilter: McpAdoptionFilter = McpAdoptionFilter.ALL,
    val message: String? = null,
)

enum class McpTransportFilter {
    ALL,
    STDIO,
    HTTP,
}

enum class McpAdoptionFilter {
    ALL,
    ADOPTED,
    AVAILABLE,
}

/**
 * Parsed MCP server configuration extracted from user input or pasted JSON.
 */
data class ParsedMcpServerConfig(
    val name: String,
    val transport: String,
    val command: String? = null,
    val args: List<String> = emptyList(),
    val endpointUrl: String? = null,
    val envOrHeaders: Map<String, String> = emptyMap(),
    val profiles: Set<String> = setOf("DESKTOP"),
)

sealed interface McpJsonParseResult {
    data class Success(val servers: List<ParsedMcpServerConfig>) : McpJsonParseResult
    data class Error(val message: String) : McpJsonParseResult
}

/**
 * Parser for standard MCP JSON configuration standards (Claude Desktop, Cursor, VSCode, Antigravity).
 *
 * Supports:
 * - Standard wrapper: `{"mcpServers": { "name": { "command": "...", "args": [...] } } }`
 * - Direct server dictionary: `{"filesystem": { "command": "npx", "args": [...] } }`
 * - Single server object: `{"command": "python", "args": ["main.py"]}`
 * - Remote SSE/HTTP: `{"url": "https://..."}` or `{"endpoint_url": "https://..."}`
 */
object McpConfigJsonParser {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun parse(rawInput: String): McpJsonParseResult {
        val sanitized = sanitizeJson(rawInput)
        if (sanitized.isBlank()) {
            return McpJsonParseResult.Error("JSON input is empty")
        }

        val rootElement = try {
            json.parseToJsonElement(sanitized)
        } catch (e: Exception) {
            return McpJsonParseResult.Error("Invalid JSON: ${e.message}")
        }

        if (rootElement !is JsonObject) {
            return McpJsonParseResult.Error("Expected a JSON object (found ${rootElement::class.simpleName})")
        }

        val results = mutableListOf<ParsedMcpServerConfig>()

        // Case 1: Standard `mcpServers` object wrapper
        if (rootElement.containsKey("mcpServers")) {
            val serversObj = rootElement["mcpServers"]
            if (serversObj !is JsonObject) {
                return McpJsonParseResult.Error("'mcpServers' must be a JSON object mapping server names to configs")
            }
            for ((serverName, serverElement) in serversObj) {
                if (serverElement is JsonObject) {
                    val parsed = parseServerObject(serverName, serverElement)
                        ?: return McpJsonParseResult.Error("Server '$serverName' must define either 'command' (stdio) or 'url' (http)")
                    results.add(parsed)
                }
            }
            if (results.isEmpty()) {
                return McpJsonParseResult.Error("'mcpServers' object contained no valid server configurations")
            }
            return McpJsonParseResult.Success(results)
        }

        // Case 2: Single server object (has command or url directly at top level)
        if (rootElement.containsKey("command") || rootElement.containsKey("url") || rootElement.containsKey("endpoint_url") || rootElement.containsKey("endpointUrl")) {
            val name = rootElement["name"]?.jsonPrimitive?.content
                ?: rootElement["id"]?.jsonPrimitive?.content
                ?: "custom-server"
            val parsed = parseServerObject(name, rootElement)
                ?: return McpJsonParseResult.Error("Configuration must define either 'command' (stdio) or 'url' (http)")
            return McpJsonParseResult.Success(listOf(parsed))
        }

        // Case 3: Direct dictionary of server objects: `{"name1": { "command": ... }, "name2": { ... }}`
        for ((serverName, serverElement) in rootElement) {
            if (serverElement is JsonObject && (serverElement.containsKey("command") || serverElement.containsKey("url") || serverElement.containsKey("endpoint_url") || serverElement.containsKey("endpointUrl"))) {
                parseServerObject(serverName, serverElement)?.let { results.add(it) }
            }
        }

        if (results.isNotEmpty()) {
            return McpJsonParseResult.Success(results)
        }

        return McpJsonParseResult.Error(
            "Could not detect MCP server configuration. Paste standard Claude Desktop 'mcpServers' JSON or a single server object."
        )
    }

    private fun parseServerObject(serverName: String, obj: JsonObject): ParsedMcpServerConfig? {
        val command = obj["command"]?.jsonPrimitive?.content
        val url = (obj["url"] ?: obj["endpoint_url"] ?: obj["endpointUrl"])?.jsonPrimitive?.content

        if (command == null && url == null) {
            return null
        }

        val transport = if (url != null) "http" else "stdio"
        val args = obj["args"]?.let { element ->
            if (element is JsonArray) {
                element.mapNotNull { it.jsonPrimitive.content }
            } else emptyList()
        } ?: emptyList()

        val envOrHeaders = mutableMapOf<String, String>()
        val envObj = (obj["env"] ?: obj["environment"]) as? JsonObject
        envObj?.forEach { (k, v) ->
            envOrHeaders[k] = v.jsonPrimitive.content
        }

        val headersObj = obj["headers"] as? JsonObject
        headersObj?.forEach { (k, v) ->
            envOrHeaders[k] = v.jsonPrimitive.content
        }

        val profiles = mutableSetOf("DESKTOP")
        if (transport == "http") {
            profiles.add("MOBILE")
        }
        val profilesArray = obj["profiles"] as? JsonArray
        if (profilesArray != null) {
            val parsedProfiles = profilesArray.mapNotNull { it.jsonPrimitive.content }.toSet()
            if (parsedProfiles.isNotEmpty()) {
                profiles.clear()
                profiles.addAll(parsedProfiles)
            }
        }

        return ParsedMcpServerConfig(
            name = serverName.trim(),
            transport = transport,
            command = command?.trim(),
            args = args,
            endpointUrl = url?.trim(),
            envOrHeaders = envOrHeaders,
            profiles = profiles,
        )
    }

    private fun sanitizeJson(input: String): String {
        var text = input.trim()
        // Strip markdown code fences if present: ```json ... ```
        if (text.startsWith("```")) {
            val firstLineBreak = text.indexOf('\n')
            if (firstLineBreak != -1) {
                text = text.substring(firstLineBreak + 1)
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length - 3)
            }
            text = text.trim()
        }

        // Clean simple single-line comments without breaking inside URLs or quotes
        val lines = text.lines()
        val cleaned = StringBuilder()
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("//") || trimmed.startsWith("#")) {
                continue
            }
            cleaned.appendLine(line)
        }
        return cleaned.toString().trim()
    }
}
