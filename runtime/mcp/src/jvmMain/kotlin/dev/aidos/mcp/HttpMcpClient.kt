package dev.aidos.mcp

import dev.aidos.kernel.ContentBlock
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import java.net.URI
import java.util.concurrent.atomic.AtomicLong
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Streamable HTTP MCP transport (RFC-0031 §Streamable HTTP Transport, M18): POSTs a JSON-RPC
 * request per call and reads either a plain JSON body or an SSE `data:` frame back.
 *
 * **Certificate validation**: no custom `TrustManager` or engine override is configured anywhere
 * in this class or the [HttpClient] construction below, so the CIO engine validates server
 * certificates against the JVM's default trust store for every `https://` call — the same
 * assumption [validateHttpEndpoint] makes when it lets an `https://` URL through unconditionally.
 * There is deliberately no way to disable this from here (RFC-0031: "no opt-out flag").
 *
 * **Cross-host redirect refusal**: `followRedirects` is off, so every 3xx response reaches this
 * class as data rather than something the engine already acted on; [isCrossHostRedirect] decides
 * per RFC-0031's own rule ("refused, not followed") before any follow-up request is issued.
 *
 * **MVP scope**: reads the *first* SSE `data:` frame as the JSON-RPC response, not a genuine
 * multi-event stream — real enough to prove the transport (POST, headers, redirect refusal, cert
 * validation) works end-to-end; a server that pushes multiple events per call needs more than
 * this class does today, flagged rather than silently assumed to work.
 */
class HttpMcpClient(
    private val endpointUrl: String,
    private val authHeaderName: String = "Authorization",
    private val authHeaderValue: String? = null,
    private val requestTimeoutMillis: Long = 30_000,
    private val maxRedirects: Int = 5,
) : McpClient {

    private val client = HttpClient(CIO) {
        followRedirects = false
        install(HttpTimeout) {
            requestTimeoutMillis = this@HttpMcpClient.requestTimeoutMillis
        }
    }
    private val idGen = AtomicLong(1)

    private suspend fun request(method: String, params: JsonObject?): JsonRpcResponse {
        val id = idGen.getAndIncrement()
        val req = JsonRpcRequest(id = JsonPrimitive(id), method = method, params = params)
        val body = mcpJson.encodeToString(req)

        var url = endpointUrl
        var redirects = 0
        while (true) {
            val response = client.post(url) {
                contentType(ContentType.Application.Json)
                headers {
                    append("Accept", "application/json, text/event-stream")
                    authHeaderValue?.let { append(authHeaderName, it) }
                }
                setBody(body)
            }
            if (response.status.value in 300..399) {
                val location = response.headers[HttpHeaders.Location]
                    ?: throw McpRpcException("mcp.http.redirect_without_location: $url")
                if (isCrossHostRedirect(url, location)) {
                    throw McpRpcException("mcp.http.cross_host_redirect_refused: $url -> $location")
                }
                if (++redirects > maxRedirects) {
                    throw McpRpcException("mcp.http.too_many_redirects: $url")
                }
                url = URI(url).resolve(location).toString()
                continue
            }
            return parseResponse(response)
        }
    }

    private suspend fun parseResponse(response: HttpResponse): JsonRpcResponse {
        val text = response.bodyAsText()
        val contentType = response.headers[HttpHeaders.ContentType] ?: ""
        val jsonText = if (contentType.startsWith("text/event-stream")) {
            text.lineSequence()
                .filter { it.startsWith("data:") }
                .map { it.removePrefix("data:").trim() }
                .firstOrNull()
                ?: throw McpRpcException("mcp.http.empty_sse_stream")
        } else {
            text
        }
        return runCatching { mcpJson.decodeFromString<JsonRpcResponse>(jsonText) }
            .getOrElse { throw McpRpcException("mcp.http.malformed_response: ${it.message}") }
    }

    override suspend fun initialize(): McpServerInfo {
        val response = request("initialize", buildJsonObject { put("protocolVersion", JsonPrimitive("2024-11-05")) })
        val result = response.result?.jsonObject
            ?: throw McpRpcException("mcp.http.error: ${response.error?.message ?: "initialize returned no result"}")
        val info = result["serverInfo"]?.jsonObject
        return McpServerInfo(
            name = info?.get("name")?.jsonPrimitive?.content ?: "unknown",
            version = info?.get("version")?.jsonPrimitive?.content ?: "unknown",
        )
    }

    override suspend fun listTools(): List<McpToolSpec> {
        val response = request("tools/list", null)
        val result = response.result?.jsonObject
            ?: throw McpRpcException("mcp.http.error: ${response.error?.message ?: "tools/list returned no result"}")
        return result["tools"]?.jsonArray?.map { tool ->
            val obj = tool.jsonObject
            McpToolSpec(
                name = obj["name"]?.jsonPrimitive?.content ?: "",
                description = obj["description"]?.jsonPrimitive?.content ?: "",
                inputSchema = obj["inputSchema"]?.jsonObject ?: buildJsonObject {},
            )
        } ?: emptyList()
    }

    override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
        val params = buildJsonObject {
            put("name", JsonPrimitive(name))
            put("arguments", arguments)
        }
        val response = request("tools/call", params)
        val result = response.result?.jsonObject
            ?: return McpCallResult(
                content = listOf(ContentBlock.Text(response.error?.message ?: "mcp.http.error: unknown")),
                isError = true,
            )
        val content = result["content"]?.jsonArray?.mapNotNull { block ->
            val obj = block.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "text") {
                ContentBlock.Text(obj["text"]?.jsonPrimitive?.content ?: "")
            } else {
                null // Non-text content blocks (image, embedded resource) are MVP-deferred.
            }
        } ?: emptyList()
        val isError = result["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        return McpCallResult(content = content, isError = isError)
    }

    override fun close() {
        client.close()
    }
}

/**
 * RFC-0031: "Redirects to a different host are refused, not followed." Host-only comparison —
 * a scheme or port change on the *same* host is allowed, matching the RFC's own wording ("a
 * different host", not "a different origin").
 */
fun isCrossHostRedirect(fromUrl: String, toLocation: String): Boolean {
    val fromHost = URI(fromUrl).host
    val toHost = URI(fromUrl).resolve(toLocation).host
    return !fromHost.equals(toHost, ignoreCase = true)
}
