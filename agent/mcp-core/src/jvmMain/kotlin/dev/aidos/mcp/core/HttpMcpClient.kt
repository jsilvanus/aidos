package dev.aidos.mcp.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.serialization.json.JsonObject

/** Streamable HTTP MCP client backed by the official Kotlin MCP SDK. */
class HttpMcpClient(
    private val endpointUrl: String,
    private val authHeaderName: String = "Authorization",
    private val authHeaderValue: String? = null,
    private val requestTimeoutMillis: Long = 30_000,
    @Suppress("UNUSED_PARAMETER") private val maxRedirects: Int = 5,
) : McpClient {

    private val httpClient = HttpClient(CIO) {
        followRedirects = false
        install(SSE)
        install(HttpTimeout) {
            requestTimeoutMillis = this@HttpMcpClient.requestTimeoutMillis
        }
    }

    private val sdkClient = Client(
        clientInfo = Implementation(
            name = "aidos-mcp-core",
            version = "0.1.0",
        ),
    )

    private val transport = StreamableHttpClientTransport(
        client = httpClient,
        url = endpointUrl,
        requestBuilder = {
            authHeaderValue?.let { headers.append(authHeaderName, it) }
        },
    )

    private val delegate = SdkMcpClient(sdkClient, transport, requestTimeoutMillis)

    override suspend fun initialize(): McpServerInfo = delegate.initialize()
    override suspend fun listTools(): List<McpToolSpec> = delegate.listTools()
    override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult =
        delegate.callTool(name, arguments)

    override suspend fun close() {
        delegate.close()
        httpClient.close()
    }
}

fun isCrossHostRedirect(fromUrl: String, toLocation: String): Boolean {
    val fromHost = java.net.URI(fromUrl).host
    val toHost = java.net.URI(fromUrl).resolve(toLocation).host
    return !fromHost.equals(toHost, ignoreCase = true)
}
