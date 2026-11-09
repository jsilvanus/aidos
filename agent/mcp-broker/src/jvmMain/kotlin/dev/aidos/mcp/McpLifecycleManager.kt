package dev.aidos.mcp

import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Manages the lazy-start / idle-stop lifecycle of registered [LazyMcpTool] instances (RFC-0031,
 * "Lifecycle: lazy start, idle stop").
 *
 * Opening a project spawns nothing and connects to nothing; each [LazyMcpTool] connects on its
 * first tool execution. [McpLifecycleManager] tracks those tools and releases any server connection
 * that has been idle for at least [defaultIdleTimeoutMillis] with zero calls in flight. Released
 * servers keep their adopted descriptor catalog available offline and transparently re-activate on
 * the next tool execution.
 */
class McpLifecycleManager(
    val defaultIdleTimeoutMillis: Long = LazyMcpTool.DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : AutoCloseable {

    private val tools = ConcurrentHashMap<String, LazyMcpTool>()

    private fun keyFor(projectId: String, serverId: String): String = "$projectId:$serverId"

    /** Registers [tool] for idle lifecycle management and returns it for fluent wiring. */
    fun register(tool: LazyMcpTool): LazyMcpTool {
        tools[keyFor(tool.projectId, tool.serverId)] = tool
        return tool
    }

    /** Removes a tool registration and synchronously closes any live connection it holds. */
    fun unregister(projectId: String, serverId: String) {
        tools.remove(keyFor(projectId, serverId))?.close()
    }

    /** Returns the server IDs of all currently connected MCP tools. */
    fun connectedServerIds(): Set<String> =
        tools.values.filter { it.isConnected }.map { it.serverId }.toSet()

    /**
     * Sweeps all registered [LazyMcpTool] instances and closes any connection that has been
     * idle for at least [maxIdleMillis] with no in-flight calls.
     *
     * Returns the `serverId`s of connections that were released during this sweep.
     */
    suspend fun sweepIdle(
        nowMillis: Long = clockMillis(),
        maxIdleMillis: Long? = null,
    ): List<String> {
        val released = mutableListOf<String>()
        for (tool in tools.values) {
            if (tool.releaseIfIdle(nowMillis = nowMillis, maxIdleMillis = maxIdleMillis ?: tool.idleTimeoutMillis)) {
                released.add(tool.serverId)
            }
        }
        return released
    }

    /**
     * Starts a background coroutine in [scope] that periodically sweeps idle MCP connections
     * every [checkIntervalMillis]. Cancelling the returned [Job] stops the background sweep.
     */
    fun startPeriodicSweep(
        scope: CoroutineScope,
        checkIntervalMillis: Long,
        maxIdleMillis: Long? = null,
    ): Job = scope.launch {
        while (isActive) {
            delay(checkIntervalMillis)
            sweepIdle(nowMillis = clockMillis(), maxIdleMillis = maxIdleMillis)
        }
    }

    /** Suspending shutdown of all registered MCP server connections. */
    suspend fun closeAllSuspend() {
        for (tool in tools.values) {
            tool.closeSuspend()
        }
    }

    override fun close() {
        for (tool in tools.values) {
            tool.close()
        }
    }
}
