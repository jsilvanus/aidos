package dev.aidos.mcp.core

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Reusable MCP client boundary backed by the official Kotlin MCP SDK. */
class SdkMcpClient(
    private val client: Client,
    private val transport: Transport,
    private val requestTimeoutMillis: Long = 30_000,
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
        val options = RequestOptions(timeout = requestTimeoutMillis)
        return buildList {
            var cursor: String? = null
            do {
                val request = ListToolsRequest(params = cursor?.let { PaginatedRequestParams(it) })
                val page = client.listTools(request, options)
                addAll(page.tools.map(::mapTool))
                cursor = page.nextCursor
            } while (cursor != null)
        }
    }

    override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        check(initialized) { "MCP client is not initialized" }
        val result = client.callTool(
            CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
            RequestOptions(timeout = requestTimeoutMillis),
        )
        val content = result.content.map { block ->
            when (block) {
                is TextContent -> McpContent.Text(block.text)
                else -> error("MCP tool '$name' returned unsupported content block: ${block::class.simpleName}")
            }
        }
        return McpCallResult(content = content, isError = result.isError == true)
    }

    /** AutoCloseable cleanup is synchronous at the Aidos boundary; wrappers close their resources. */
    override fun close() {
        initialized = false
    }

    /** SDK cleanup for callers already inside structured concurrency; no runBlocking bridge. */
    override suspend fun closeSuspend() {
        client.close()
        initialized = false
    }

    private fun mapTool(tool: io.modelcontextprotocol.kotlin.sdk.types.Tool): McpToolSpec =
        McpToolSpec(tool.name, tool.description.orEmpty(), mapSchema(tool.inputSchema))

    /**
     * WARNING: SDK ToolSchema cannot represent arbitrary top-level JSON Schema keywords. This
     * compatibility mapping is therefore not safe as the descriptor-hash source. Raw-schema
     * preservation is a required follow-up of this migration, not an optional normalization.
     */
    private fun mapSchema(schema: ToolSchema): JsonObject = buildJsonObject {
        schema.schema?.let { put("\$schema", it) }
        put("type", "object")
        schema.properties?.let { properties ->
            putJsonObject("properties") { properties.forEach { (key, value) -> put(key, value) } }
        }
        schema.required?.let { required -> putJsonArray("required") { required.forEach { add(it) } } }
        schema.defs?.let { defs -> putJsonObject("\$defs") { defs.forEach { (key, value) -> put(key, value) } } }
    }
}
