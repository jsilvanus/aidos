package dev.aidos.mcp.core

import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The JSON-RPC 2.0 wire codec both transports share (RFC-0031, M18). */
class JsonRpcTest {

    @Test
    fun `a request round-trips through encode and decode`() {
        val req = JsonRpcRequest(
            id = JsonPrimitive(7),
            method = "tools/call",
            params = buildJsonObject { put("name", JsonPrimitive("echo")) },
        )
        val encoded = mcpJson.encodeToString(req)
        val decoded = mcpJson.decodeFromString<JsonRpcRequest>(encoded)
        assertEquals(req, decoded)
        // The wire form must actually say "jsonrpc":"2.0" -- not just the Kotlin default value,
        // proving encodeDefaults is on (a request missing this field is invalid per the spec).
        assertEquals(true, encoded.contains("\"jsonrpc\":\"2.0\""))
    }

    @Test
    fun `a success response round-trips`() {
        val resp = JsonRpcResponse(id = JsonPrimitive(1), result = buildJsonObject { put("ok", JsonPrimitive(true)) })
        val decoded = mcpJson.decodeFromString<JsonRpcResponse>(mcpJson.encodeToString(resp))
        assertEquals(resp, decoded)
        assertNull(decoded.error)
    }

    @Test
    fun `an error response round-trips`() {
        val resp = JsonRpcResponse(id = JsonPrimitive(2), error = JsonRpcError(code = -32601, message = "unknown method"))
        val decoded = mcpJson.decodeFromString<JsonRpcResponse>(mcpJson.encodeToString(resp))
        assertEquals(resp, decoded)
        assertNull(decoded.result)
        assertEquals(-32601, decoded.error?.code)
    }

    @Test
    fun `unknown fields on the wire are ignored, not a decode failure`() {
        // MCP servers are third-party code; a server on a newer protocol revision must not break
        // this client by adding a field it doesn't recognize.
        val decoded = mcpJson.decodeFromString<JsonRpcResponse>(
            """{"jsonrpc":"2.0","id":1,"result":{},"unexpectedField":"from a newer server"}"""
        )
        assertEquals(JsonPrimitive(1), decoded.id)
    }
}
