package dev.aidos.mcp.core

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.shared.RequestOptions
import kotlin.time.Duration.Companion.milliseconds
import io.modelcontextprotocol.kotlin.sdk.shared.Transport
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequest
import io.modelcontextprotocol.kotlin.sdk.types.McpException
import io.modelcontextprotocol.kotlin.sdk.types.RPCError
import io.modelcontextprotocol.kotlin.sdk.types.CallToolRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.ListToolsRequest
import io.modelcontextprotocol.kotlin.sdk.types.PaginatedRequestParams
import io.modelcontextprotocol.kotlin.sdk.types.TextContent
import io.modelcontextprotocol.kotlin.sdk.types.ToolAnnotations
import io.modelcontextprotocol.kotlin.sdk.types.ToolSchema
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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

    /**
     * Connects on first use, so callers cannot get an `IllegalStateException` for forgetting an
     * explicit [initialize]. MCP still requires the handshake before any other request -- that is
     * exactly what `Client.connect` performs -- so this is lazy, not lenient: the connection is
     * established before the first request either way, just without a second way to get it wrong.
     * Idempotent, and the reason connecting is not done in the constructor is D30: constructing a
     * client must not reach the network or spawn anything.
     */
    private suspend fun ensureConnected() {
        if (!initialized) {
            client.connect(transport)
            initialized = true
        }
    }

    override suspend fun initialize(): McpServerInfo {
        ensureConnected()
        val server = requireNotNull(client.serverVersion) {
            "MCP client connected without server implementation information"
        }
        return McpServerInfo(server.name, server.version)
    }

    override suspend fun listTools(): List<McpToolSpec> {
        ensureConnected()
        val options = RequestOptions(timeout = requestTimeoutMillis.milliseconds)
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
        ensureConnected()
        val result = try {
            client.callTool(
                CallToolRequest(CallToolRequestParams(name = name, arguments = arguments)),
                RequestOptions(timeout = requestTimeoutMillis.milliseconds),
            )
        } catch (e: McpException) {
            // The SDK throws on a JSON-RPC error response; McpClient's contract returns one as
            // isError. Preserve the contract, because McpTool distinguishes a tool that failed
            // (mcp.tool_error, reported to the model so it can adapt) from a transport that failed
            // (mcp.transport_error) -- collapsing them would tell the model a server is unreachable
            // when it merely rejected the call.
            //
            // The two SDK-side conditions are excluded and still propagate: a closed connection and
            // a timed-out request are transport failures, not answers from a server.
            if (e.code == RPCError.ErrorCode.CONNECTION_CLOSED || e.code == RPCError.ErrorCode.REQUEST_TIMEOUT) {
                throw e
            }
            return McpCallResult(
                content = listOf(McpContent.Text(e.message ?: "mcp.error: code ${e.code}")),
                isError = true,
            )
        }
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
        McpToolSpec(
            name = tool.name,
            description = tool.description.orEmpty(),
            inputSchema = mapSchema(tool.inputSchema),
            // Captured, not consumed. These are here so the migration does not silently discard
            // what the server said -- `annotations` in particular carries destructiveHint. Nothing
            // reads them, so they are outside the descriptor hash by design; McpToolSpec's KDoc
            // states what must happen the day something does.
            title = tool.title,
            outputSchema = tool.outputSchema?.let(::mapSchema),
            annotations = tool.annotations?.let(::mapAnnotations),
        )

    private fun mapAnnotations(annotations: ToolAnnotations): JsonObject = buildJsonObject {
        annotations.title?.let { put("title", it) }
        annotations.readOnlyHint?.let { put("readOnlyHint", it) }
        annotations.destructiveHint?.let { put("destructiveHint", it) }
        annotations.idempotentHint?.let { put("idempotentHint", it) }
        annotations.openWorldHint?.let { put("openWorldHint", it) }
    }

    /**
     * Rebuilds a JSON Schema object from the SDK's [ToolSchema], which models exactly `$schema`,
     * `type`, `properties`, `required` and `$defs` and has no catch-all -- so a top-level keyword
     * outside that set (`additionalProperties`, `oneOf`, ...) is already gone by the time this
     * runs, and no mapping here can bring it back. `properties` and `$defs` are `JsonObject`s and
     * survive verbatim, so a parameter's own constraints are exact.
     *
     * That loss is tolerable *only* because of the invariant `McpDescriptorHash` documents: the
     * hash covers exactly what the model is shown, and the descriptor the model is shown is built
     * from this same output. A keyword dropped here is one the model never sees, so a server
     * cannot use it to change behaviour after adoption. Aidos does not validate arguments against
     * `inputSchema` either -- the server does -- so nothing local is weakened. See RFC-0031,
     * "Protocol layer: the official Kotlin MCP SDK, not a hand-rolled client".
     */
    private fun mapSchema(schema: ToolSchema): JsonObject = buildJsonObject {
        schema.schema?.let { put("\$schema", it) }
        put("type", "object")
        schema.properties?.let { properties ->
            putJsonObject("properties") { properties.forEach { (key, value) -> put(key, value) } }
        }
        schema.required?.let { required -> putJsonArray("required") { required.forEach { add(JsonPrimitive(it)) } } }
        schema.defs?.let { defs -> putJsonObject("\$defs") { defs.forEach { (key, value) -> put(key, value) } } }
    }
}
