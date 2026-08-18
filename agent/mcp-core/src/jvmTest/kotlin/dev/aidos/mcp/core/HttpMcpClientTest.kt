package dev.aidos.mcp.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class HttpMcpClientTest {
    private var server: HttpServer? = null
    private var capturedAuthHeader: String? = null

    @AfterTest
    fun tearDown() { server?.stop(0) }

    private fun startFakeServer(): HttpServer {
        val srv = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        srv.createContext("/mcp") { exchange -> handleMcp(exchange) }
        srv.createContext("/redirect-cross-host") { exchange ->
            exchange.responseHeaders.add("Location", "http://example.invalid/mcp")
            exchange.sendResponseHeaders(302, -1)
            exchange.responseBody.close()
        }
        srv.createContext("/redirect-same-host") { exchange ->
            exchange.responseHeaders.add("Location", "/mcp")
            exchange.sendResponseHeaders(302, -1)
            exchange.responseBody.close()
        }
        srv.start()
        server = srv
        return srv
    }

    /**
     * JSON-RPC requires `error` to be ABSENT on success, not null. `mcpJson` writes explicit
     * nulls, and the SDK then tries to deserialize `null` as an RPCError and fails the whole
     * response. The pre-SDK client never looked at `error` on a successful reply, so this fixture
     * shipped invalid JSON-RPC unnoticed.
     */
    private val fixtureJson = Json { explicitNulls = false }

    private fun handleMcp(exchange: HttpExchange) {
        capturedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
        // MCP Streamable HTTP also uses GET (to open the server->client SSE channel) and DELETE
        // (to end the session); both are optional for a server, and 405 is the spec's way of
        // saying "not offered". Answering them with a JSON-RPC parse attempt instead threw out of
        // the handler, and com.sun's HttpServer closes the socket on a handler throw -- which the
        // client reports as "the server prematurely closed the connection", nothing about method.
        if (exchange.requestMethod != "POST") {
            exchange.sendResponseHeaders(405, -1)
            exchange.responseBody.close()
            return
        }
        val body = exchange.requestBody.readBytes().decodeToString()
        // A notification (no `id`) gets 202 Accepted and an empty body -- there is nothing to
        // correlate a response to. JsonRpcRequest.id is deliberately non-null, so decoding one as
        // a request throws; the SDK sends notifications/initialized immediately after the
        // handshake, so this path is on the critical path of every connection, not an edge case.
        val envelope = mcpJson.parseToJsonElement(body).jsonObject
        if (envelope["id"] == null) {
            exchange.sendResponseHeaders(202, -1)
            exchange.responseBody.close()
            return
        }
        val req = mcpJson.decodeFromString<JsonRpcRequest>(body)
        val response = when (req.method) {
            // protocolVersion and capabilities are REQUIRED by MCP's InitializeResult. The
            // pre-SDK client read only serverInfo and tolerated their absence; the official SDK
            // deserializes strictly, so omitting them makes every later request time out instead
            // of failing loudly. Echo the client's protocolVersion so this fixture survives
            // version negotiation moving.
            "initialize" -> JsonRpcResponse(
                id = req.id,
                result = buildJsonObject {
                    put(
                        "protocolVersion",
                        (req.params as? JsonObject)?.get("protocolVersion") ?: JsonPrimitive("2024-11-05"),
                    )
                    put("capabilities", buildJsonObject { put("tools", buildJsonObject { }) })
                    put("serverInfo", buildJsonObject {
                        put("name", JsonPrimitive("fake-mcp-http-server"))
                        put("version", JsonPrimitive("0.0.1"))
                    })
                },
            )
            "tools/list" -> JsonRpcResponse(
                id = req.id,
                result = buildJsonObject {
                    put("tools", kotlinx.serialization.json.buildJsonArray {
                        add(buildJsonObject {
                            put("name", JsonPrimitive("echo"))
                            put("description", JsonPrimitive("echoes its input"))
                            put("inputSchema", buildJsonObject {})
                        })
                    })
                },
            )
            "tools/call" -> {
                val params = req.params?.jsonObject
                val name = params?.get("name")?.jsonPrimitive?.content
                val args = params?.get("arguments")?.jsonObject
                if (name == "echo") {
                    JsonRpcResponse(
                        id = req.id,
                        result = buildJsonObject {
                            put("content", kotlinx.serialization.json.buildJsonArray {
                                add(buildJsonObject {
                                    put("type", JsonPrimitive("text"))
                                    put("text", args?.get("text") ?: JsonPrimitive(""))
                                })
                            })
                            put("isError", JsonPrimitive(false))
                        },
                    )
                } else {
                    JsonRpcResponse(id = req.id, error = JsonRpcError(code = -32601, message = "unknown tool '$name'"))
                }
            }
            else -> JsonRpcResponse(id = req.id, error = JsonRpcError(code = -32601, message = "unknown method '${req.method}'"))
        }
        val bytes = fixtureJson.encodeToString(response).toByteArray()
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun endpointUrl(srv: HttpServer, path: String = "/mcp") = "http://127.0.0.1:${srv.address.port}$path"

    @Test
    fun `initialize returns the server's info over a real HTTP POST`() = runBlocking {
        val srv = startFakeServer()
        val client = HttpMcpClient(endpointUrl(srv))
        try {
            val info = client.initialize()
            assertEquals("fake-mcp-http-server", info.name)
        } finally { client.close() }
    }

    @Test
    fun `tools_call round-trips a real request and response over HTTP`() = runBlocking {
        val srv = startFakeServer()
        val client = HttpMcpClient(endpointUrl(srv))
        try {
            val result = client.callTool("echo", buildJsonObject { put("text", JsonPrimitive("hello over http")) })
            assertFalse(result.isError)
            assertEquals("hello over http", (result.content.first() as McpContent.Text).text)
        } finally { client.close() }
    }

    @Test
    fun `the resolved header value reaches the server on every call`() = runBlocking {
        val srv = startFakeServer()
        val client = HttpMcpClient(endpointUrl(srv), authHeaderName = "Authorization", authHeaderValue = "Bearer resolved-from-vault")
        try {
            client.initialize()
            assertEquals("Bearer resolved-from-vault", capturedAuthHeader)
        } finally { client.close() }
    }

    @Test
    fun `the SDK transport does not follow a same-host redirect`() = runBlocking {
        val srv = startFakeServer()
        val client = HttpMcpClient(endpointUrl(srv, "/redirect-same-host"))
        try {
            val result = runCatching { client.initialize() }
            assertTrue(result.isFailure, "the SDK transport must not silently follow a 3xx response")
        } finally { client.close() }
    }

    @Test
    fun `a cross-host redirect is refused, not followed`() = runBlocking {
        val srv = startFakeServer()
        val client = HttpMcpClient(endpointUrl(srv, "/redirect-cross-host"))
        try {
            val result = runCatching { client.initialize() }
            assertTrue(result.isFailure, "the client must refuse a redirect to a different host (RFC-0031)")
        } finally { client.close() }
    }

    @Test
    fun `same host, different scheme or port is not a cross-host redirect`() {
        assertFalse(isCrossHostRedirect("http://mcp.example.com/v1", "https://mcp.example.com/v1"))
        assertFalse(isCrossHostRedirect("http://mcp.example.com:8080/v1", "http://mcp.example.com:9090/v1"))
    }

    @Test
    fun `a relative redirect resolves against the original host and is not cross-host`() {
        assertFalse(isCrossHostRedirect("https://mcp.example.com/v1/call", "/v1/other"))
    }

    @Test
    fun `a different host is refused regardless of scheme`() {
        assertTrue(isCrossHostRedirect("https://mcp.example.com/v1", "https://attacker.example.com/v1"))
        assertTrue(isCrossHostRedirect("https://mcp.example.com/v1", "http://attacker.example.com/v1"))
    }
}
