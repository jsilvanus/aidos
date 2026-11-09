package dev.aidos.mcp.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * JSON-RPC 2.0 wire types used by test fixtures (RFC-0031, M18).
 *
 * Production protocol handling lives in the official Kotlin MCP SDK (`SdkMcpClient`);
 * these types remain in `jvmTest` to drive wire-level test servers and codec tests.
 */
internal val mcpJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class JsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonElement,
    val method: String,
    val params: JsonElement? = null,
)

@Serializable
data class JsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonElement? = null,
    val result: JsonElement? = null,
    val error: JsonRpcError? = null,
)

@Serializable
data class JsonRpcError(
    val code: Int,
    val message: String,
    val data: JsonElement? = null,
)

class McpRpcException(message: String) : Exception(message)
