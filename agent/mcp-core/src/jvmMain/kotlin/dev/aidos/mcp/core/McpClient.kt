package dev.aidos.mcp.core

import kotlinx.serialization.json.JsonObject

/**
 * A live connection to one MCP server. Protocol mechanics are implemented by the official Kotlin
 * MCP SDK; this interface is the reusable Aidos/Dictator boundary.
 */
interface McpClient : AutoCloseable {
    suspend fun initialize(): McpServerInfo
    suspend fun listTools(): List<McpToolSpec>
    suspend fun callTool(name: String, arguments: JsonObject): McpCallResult

    /** Suspending cleanup for callers already inside structured concurrency. */
    suspend fun closeSuspend() = close()
}

data class McpServerInfo(val name: String, val version: String)

data class McpCallResult(
    val content: List<McpContent>,
    val isError: Boolean,
)
