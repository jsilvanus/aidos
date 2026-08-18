package dev.aidos.mcp.core

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Aidos' reusable MCP client boundary backed by the official Kotlin MCP SDK.
 *
 * This class deliberately keeps the existing mcp-core API independent of SDK types. Consumers
 * such as Aidos and Dictator depend on this boundary; the SDK owns MCP protocol mechanics.
 * Transport construction remains outside this adapter so policy/lifecycle code can decide when
 * and how a connection is created.
 */
class SdkMcpClient(
    private val client: Client,
    private val transport: Transport,
) : McpClient {

    private var initialized = false

    override suspend fun initialize(): McpServerInfo {
        if (!initialized) {
            client.connect(transport)
            initialized = true
        }

        val server = requireNotNull(client.serverVersion) {
            "MCP client connected without server implementation information"
        }
        return McpServerInfo(server.name, server.version)
    }

    override suspend fun listTools(): List<McpToolSpec> {
        check(initialized) { "MCP client is not initialized" }

        // The SDK exposes MCP pagination explicitly. mcp-core's historical API returns a complete
        // catalog, so consume every page here rather than silently dropping tools after page one.
        val tools = buildList {
            var cursor: String? = null
            do {
                val request = ListToolsRequest(
                    params = cursor?.let { PaginatedRequestParams(it) },
                )
                val page = client.listTools(request)
                addAll(page.tools.map(::mapTool))
                cursor = page.nextCursor
            } while (cursor != null)
        }

        return tools
    }

    override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        check(initialized) { "MCP client is not initialized" }

        val result = client.callTool(
            CallToolRequest(
                CallToolRequestParams(
                    name = name,
                    arguments = arguments,
                ),
            ),
        )

        // mcp-core intentionally exposes only Text today. Do not silently coerce media/resources
        // into text; fail explicitly until the reusable content model grows those cases.
        val content = result.content.map { block ->
            when (block) {
                is TextContent -> McpContent.Text(block.text)
                else -> error(
                    "MCP tool '$name' returned unsupported content block: ${block::class.simpleName}",
                )
            }
        }

        return McpCallResult(
            content = content,
            isError = result.isError == true,
        )
    }

    override fun close() {
        // McpClient predates the SDK and is AutoCloseable. Preserve that synchronous contract while
        // allowing the SDK to perform its structured-concurrency shutdown correctly.
        runBlocking {
            client.close()
        }
        initialized = false
    }

    private fun mapTool(tool: io.modelcontextprotocol.kotlin.sdk.types.Tool): McpToolSpec =
        McpToolSpec(
            name = tool.name,
            description = tool.description.orEmpty(),
            inputSchema = mapSchema(tool.inputSchema),
        )

    private fun mapSchema(schema: ToolSchema): JsonObject = buildJsonObject {
        schema.schema?.let { put("\$schema", it) }
        put("type", "object")
        schema.properties?.let { properties ->
            putJsonObject("properties") { properties.forEach { (key, value) -> put(key, value) } }
        }
        schema.required?.let { required ->
            putJsonArray("required") { required.forEach { add(it) } }
        }
        schema.defs?.let { defs ->
            putJsonObject("\$defs") { defs.forEach { (key, value) -> put(key, value) } }
        }
    }
}
