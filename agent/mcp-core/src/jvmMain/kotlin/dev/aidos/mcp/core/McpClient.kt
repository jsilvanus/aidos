package dev.aidos.mcp.core

import kotlinx.serialization.json.JsonObject

/**
 * A live connection to one MCP server, speaking JSON-RPC 2.0 over whichever transport
 * implements this (RFC-0031, M18). Lazily connected by the implementation's own constructor —
 * nothing in this interface has a separate "connect" step, matching D30's "nothing spawns or
 * connects on project open": a client is only ever constructed at the moment a call needs it.
 */
interface McpClient : AutoCloseable {
    /** The MCP `initialize` handshake. Must be called before [listTools]/[callTool]. */
    suspend fun initialize(): McpServerInfo

    /** MCP `tools/list`. Schemas are returned as-is; validating them is the caller's job. */
    suspend fun listTools(): List<McpToolSpec>

    /**
     * MCP `tools/call`. Whatever wraps this in a kernel `ToolCallResult` must report
     * `TrustLevel.UNTRUSTED` (RFC-0027) — this interface itself carries no trust level, it just
     * relays what the server said.
     */
    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult
}

data class McpServerInfo(val name: String, val version: String)

data class McpCallResult(
    val content: List<McpContent>,
    val isError: Boolean,
)
