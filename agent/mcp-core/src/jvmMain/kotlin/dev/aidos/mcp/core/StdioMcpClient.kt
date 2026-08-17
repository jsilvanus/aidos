package dev.aidos.mcp.core

import java.io.BufferedReader
import java.io.BufferedWriter
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * stdio MCP transport (RFC-0031 §Stdio Transport, M18): spawns the server as a subprocess with a
 * [scrubbedEnvironment] and speaks newline-delimited JSON-RPC 2.0 over its stdin/stdout.
 *
 * The blocking read loop runs on its own daemon [Thread], not a coroutine dispatcher — the same
 * fix M10's `SocketRuntimeClient` needed for the same reason: a `Channel`/coroutine wrapper around
 * a blocking `BufferedReader.readLine()` does not respond to structured cancellation, and this
 * client's callers (a Run's tool call) must be cancellable. [pending] is how a reply reaches the
 * coroutine that sent the request; a JSON-RPC id round-trips as the map key.
 */
class StdioMcpClient(
    command: String,
    args: List<String> = emptyList(),
    extraEnv: Map<String, String> = emptyMap(),
    private val requestTimeoutMillis: Long = 30_000,
) : McpClient {

    private val process = ProcessBuilder(listOf(command) + args).apply {
        redirectErrorStream(false)
        environment().clear()
        environment().putAll(scrubbedEnvironment(extraEnv))
    }.start()

    private val stdin: BufferedWriter = process.outputStream.bufferedWriter()
    private val stdout: BufferedReader = process.inputStream.bufferedReader()
    private val nextId = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JsonRpcResponse>>()

    @Volatile
    private var closed = false

    private val readerThread = Thread({ readLoop() }, "mcp-stdio-reader").apply {
        isDaemon = true
        start()
    }

    private fun readLoop() {
        try {
            while (true) {
                val line = stdout.readLine() ?: break
                if (line.isBlank()) continue
                val response = runCatching { mcpJson.decodeFromString<JsonRpcResponse>(line) }.getOrNull()
                val id = (response?.id as? JsonPrimitive)?.longOrNullCompat()
                if (response != null && id != null) {
                    pending.remove(id)?.complete(response)
                }
                // Malformed lines and unmatched ids are dropped -- a server writing to stdout
                // outside the protocol (a stray log line) must not crash the read loop.
            }
        } catch (_: Exception) {
            // Falls through to failPending below regardless of cause (stream closed, IO error).
        } finally {
            failPending("mcp.stdio.closed: server process ended (exit=${runCatching { process.exitValue() }.getOrDefault(-1)})")
        }
    }

    private fun failPending(message: String) {
        val failed = pending.keys.toList()
        for (id in failed) {
            pending.remove(id)?.completeExceptionally(McpRpcException(message))
        }
    }

    /** `internal`, not `private`: lets tests drive raw JSON-RPC methods the public API never sends (e.g. a deliberately-unanswered request), to test timeout/crash handling directly rather than only through [initialize]/[listTools]/[callTool]'s narrower shapes. */
    internal suspend fun request(method: String, params: JsonObject?): JsonRpcResponse {
        if (closed || !process.isAlive) {
            throw McpRpcException("mcp.stdio.crashed: server process is not running")
        }
        val id = nextId.getAndIncrement()
        val deferred = CompletableDeferred<JsonRpcResponse>()
        pending[id] = deferred
        val req = JsonRpcRequest(id = JsonPrimitive(id), method = method, params = params)
        val line = mcpJson.encodeToString(req)
        synchronized(stdin) {
            stdin.write(line)
            stdin.newLine()
            stdin.flush()
        }
        return try {
            withTimeout(requestTimeoutMillis) { deferred.await() }
        } catch (e: Exception) {
            pending.remove(id)
            throw if (e is McpRpcException) e else McpRpcException("mcp.stdio.timeout: no reply to '$method' within ${requestTimeoutMillis}ms")
        }
    }

    override suspend fun initialize(): McpServerInfo {
        val response = request(
            "initialize",
            buildJsonObject { put("protocolVersion", JsonPrimitive("2024-11-05")) },
        )
        val result = response.result?.jsonObject
            ?: throw McpRpcException("mcp.stdio.error: ${response.error?.message ?: "initialize returned no result"}")
        val info = result["serverInfo"]?.jsonObject
        return McpServerInfo(
            name = info?.get("name")?.jsonPrimitive?.content ?: "unknown",
            version = info?.get("version")?.jsonPrimitive?.content ?: "unknown",
        )
    }

    override suspend fun listTools(): List<McpToolSpec> {
        val response = request("tools/list", null)
        val result = response.result?.jsonObject
            ?: throw McpRpcException("mcp.stdio.error: ${response.error?.message ?: "tools/list returned no result"}")
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
                content = listOf(McpContent.Text(response.error?.message ?: "mcp.stdio.error: unknown")),
                isError = true,
            )
        val content = result["content"]?.jsonArray?.mapNotNull { block ->
            val obj = block.jsonObject
            if (obj["type"]?.jsonPrimitive?.content == "text") {
                McpContent.Text(obj["text"]?.jsonPrimitive?.content ?: "")
            } else {
                null // Non-text content blocks (image, embedded resource) are MVP-deferred.
            }
        } ?: emptyList()
        val isError = result["isError"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        return McpCallResult(content = content, isError = isError)
    }

    override fun close() {
        if (closed) return
        closed = true
        failPending("mcp.stdio.closed: client closed")
        runCatching { stdin.close() }
        runCatching { stdout.close() }
        process.destroy()
        if (!process.waitFor(2, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
        }
        readerThread.interrupt()
    }
}

private fun JsonPrimitive.longOrNullCompat(): Long? = content.toLongOrNull()
