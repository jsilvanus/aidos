package dev.aidos.mcp.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.sse.SSE
import io.modelcontextprotocol.kotlin.sdk.client.Client
import io.modelcontextprotocol.kotlin.sdk.client.StreamableHttpClientTransport
import io.modelcontextprotocol.kotlin.sdk.types.Implementation
import kotlinx.coroutines.runBlocking

/**
 * Streamable HTTP MCP client backed by the official Kotlin MCP SDK.
 *
 * Aidos-specific endpoint/egress policy remains outside this class. The SDK owns MCP framing,
 * initialization, capability negotiation, request correlation, pagination, SSE handling and
 * Streamable HTTP session lifecycle.
 *
 * [maxRedirects] is retained in the constructor for source compatibility with the pre-SDK client;
 * the SDK transport does not follow redirects itself, so there is no redirect-following loop here.
 */
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

    private val delegate = SdkMcpClient(sdkClient, transport)

    override suspend fun initialize(): McpServerInfo = delegate.initialize()

    override suspend fun listTools(): List<McpToolSpec> = delegate.listTools()

    override suspend fun callTool(
        name: String,
        arguments: kotlinx.serialization.json.JsonObject,
    ): McpCallResult = delegate.callTool(name, arguments)

    override fun close() {
        // McpClient predates the SDK and is AutoCloseable. Keep that synchronous public contract;
        // the SDK's own close is suspend because it waits for its structured-concurrency scope.
        runBlocking {
            delegate.close()
        }
        httpClient.close()
    }
}

/**
 * RFC-0031: "Redirects to a different host are refused, not followed." Host-only comparison —
 * a scheme or port change on the same host is allowed, matching the RFC's wording.
 *
 * Kept as a pure helper for the existing policy/tests. The SDK-backed transport does not follow
 * redirects, so this helper is no longer part of the transport's request path.
 */
fun isCrossHostRedirect(fromUrl: String, toLocation: String): Boolean {
    val fromHost = java.net.URI(fromUrl).host
    val toHost = java.net.URI(fromUrl).resolve(toLocation).host
    return !fromHost.equals(toHost, ignoreCase = true)
}
