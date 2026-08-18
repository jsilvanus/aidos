package dev.aidos.mcp.core

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject

/**
 * stdio MCP client backed by the official Kotlin MCP SDK.
 *
 * Aidos still owns subprocess policy: the child gets the scrubbed environment defined by
 * [scrubbedEnvironment], and the process is destroyed when this client closes. The SDK owns
 * newline framing, JSON-RPC dispatch, initialization, capability negotiation, cancellation and
 * request correlation.
 *
 * [requestTimeoutMillis] is retained for source compatibility with the pre-SDK client. The common
 * mcp-core API does not expose per-request timeout options yet; timeout policy will move to the
 * policy/execution layer rather than being reimplemented in the transport.
 */
class StdioMcpClient(
    command: String,
    args: List<String> = emptyList(),
    extraEnv: Map<String, String> = emptyMap(),
    @Suppress("UNUSED_PARAMETER") private val requestTimeoutMillis: Long = 30_000,
) : McpClient {

    private val process = ProcessBuilder(listOf(command) + args).apply {
        redirectErrorStream(false)
        environment().clear()
        environment().putAll(scrubbedEnvironment(extraEnv))
    }.start()

    private val transport = StdioClientTransport(
        input = process.inputStream.asSource().buffered(),
        output = process.outputStream.asSink().buffered(),
        error = process.errorStream.asSource().buffered(),
    )

    private val sdkClient = Client(
        clientInfo = Implementation(
            name = "aidos-mcp-core",
            version = "0.1.0",
        ),
    )

    private val delegate = SdkMcpClient(sdkClient, transport)

    override suspend fun initialize(): McpServerInfo = delegate.initialize()

    override suspend fun listTools(): List<McpToolSpec> = delegate.listTools()

    override suspend fun callTool(
        name: String,
        arguments: JsonObject,
    ): McpCallResult = delegate.callTool(name, arguments)

    override fun close() {
        delegate.close()
        process.destroy()
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
    }
}
