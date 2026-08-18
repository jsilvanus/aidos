package dev.aidos.mcp.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
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

    private fun handleMcp(exchange: HttpExchange) {
        capturedAuthHeader = exchange.requestHeaders.getFirst("Authorization")
        val body = exchange.requestBody.readBytes().decodeToString()
        val req = mcpJson.decodeFromString<JsonRpcRequest>(body)
        val response = when (req.method) {
            "initialize" -> JsonRpcResponse(
                id = req.id,
                result = buildJsonObject {
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
        val bytes = mcpJson.encodeToString(response).toByteArray()
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
