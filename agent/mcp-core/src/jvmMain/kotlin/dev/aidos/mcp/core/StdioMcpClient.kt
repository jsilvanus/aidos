package dev.aidos.mcp.core

import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StdioClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.io.asSink
import kotlinx.io.asSource
import kotlinx.io.buffered
import kotlinx.serialization.json.JsonObject

/** stdio MCP client backed by the official Kotlin MCP SDK. */
class StdioMcpClient(
    command: String,
    args: List<String> = emptyList(),
    extraEnv: Map<String, String> = emptyMap(),
    requestTimeoutMillis: Long = 30_000,
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
        clientInfo = Implementation(name = "aidos-mcp-core", version = "0.1.0"),
    )
    private val delegate = SdkMcpClient(sdkClient, transport, requestTimeoutMillis)

    override suspend fun initialize(): McpServerInfo = delegate.initialize()
    override suspend fun listTools(): List<McpToolSpec> = delegate.listTools()
    override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult =
        delegate.callTool(name, arguments)

    override fun close() {
        delegate.close()
        process.destroy()
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }

    override suspend fun closeSuspend() {
        delegate.closeSuspend()
        process.destroy()
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) process.destroyForcibly()
    }
}
