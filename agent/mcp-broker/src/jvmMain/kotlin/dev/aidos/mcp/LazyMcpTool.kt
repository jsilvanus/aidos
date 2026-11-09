package dev.aidos.mcp

import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.Preview
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.mcp.core.McpTransport
import dev.aidos.mcp.policy.McpTransportPolicy
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * A lazy [Tool] implementation representing an adopted MCP server (RFC-0031).
 *
 * It exposes adopted operations without connecting on project open (D30).
 * Connection and handshake are deferred until [execute] is called for the first time,
 * and idle connections can be released via [releaseIfIdle] (or automatically before the
 * next call after [idleTimeoutMillis]) without losing the server's adopted descriptor catalog.
 */
class LazyMcpTool(
    val serverId: String,
    val projectId: String,
    private val initialAdoptedSpecs: List<McpToolSpec>,
    private val transport: McpTransport,
    private val activator: suspend () -> McpActivationOutcome,
    val idleTimeoutMillis: Long = DEFAULT_IDLE_TIMEOUT_MILLIS,
    private val clockMillis: () -> Long = System::currentTimeMillis,
) : Tool, AutoCloseable {

    override val id: String = serverId
    override val version: String = "1"

    private val mutex = Mutex()

    @Volatile
    private var activeActivation: McpServerActivation? = null

    @Volatile
    private var inFlight: Int = 0

    @Volatile
    private var lastActivityEpochMillis: Long = 0L

    /** Whether a live [McpServerActivation] is currently held by this tool. */
    val isConnected: Boolean
        get() = activeActivation != null

    /** Number of tool invocations currently executing against this server. */
    val inFlightCount: Int
        get() = inFlight

    /** Epoch timestamp (ms) of the most recent activation or completed tool execution. */
    val lastActivityMillis: Long
        get() = lastActivityEpochMillis

    override fun operations(): List<ToolDescriptor> {
        val active = activeActivation
        if (active != null) {
            return active.tool.operations()
        }
        val classification = McpTransportPolicy.classify(transport)
        return initialAdoptedSpecs.map { spec ->
            ToolDescriptor(
                name = "$serverId:${spec.name}",
                title = spec.name,
                description = spec.description,
                inputSchema = spec.inputSchema,
                resultGuidance = null,
                effect = classification.toEffectKind(),
                requiredPermission = classification.permission.toPermission(),
                recoveryClass = classification.recovery.toRecoveryClass(),
                availability = classification.toToolAvailability(),
            )
        }
    }

    override suspend fun execute(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): ToolCallResult {
        val activation = mutex.withLock {
            val now = clockMillis()
            val existing = activeActivation
            if (existing != null && inFlight == 0 && idleTimeoutMillis > 0 && (now - lastActivityEpochMillis) >= idleTimeoutMillis) {
                activeActivation = null
                existing.close()
            }
            var active = activeActivation
            if (active == null) {
                when (val outcome = activator()) {
                    is McpActivationOutcome.Activated -> {
                        active = outcome.activation
                        activeActivation = active
                        lastActivityEpochMillis = clockMillis()
                    }
                    is McpActivationOutcome.Failed -> {
                        val desc = outcome.failure.describe()
                        return ToolCallResult(
                            callId = "",
                            outcome = ToolOutcome.Failed(
                                AidosError(
                                    code = "mcp.activation_failed",
                                    errorClass = ErrorClass.TRANSIENT,
                                    message = desc,
                                )
                            ),
                            content = listOf(ContentBlock.Text("MCP activation failed: $desc")),
                            trustLevel = TrustLevel.UNTRUSTED,
                        )
                    }
                }
            }
            inFlight++
            lastActivityEpochMillis = clockMillis()
            active
        }

        return try {
            activation.tool.execute(handle, operation, arguments)
        } finally {
            withContext(NonCancellable) {
                mutex.withLock {
                    inFlight = (inFlight - 1).coerceAtLeast(0)
                    lastActivityEpochMillis = clockMillis()
                }
            }
        }
    }

    /**
     * Releases the underlying [McpServerActivation] if one is open, no calls are in flight,
     * and at least [maxIdleMillis] have elapsed since [lastActivityMillis] (RFC-0031:
     * "Lifecycle: lazy start, idle stop").
     *
     * Returns `true` if an active connection was closed, or `false` if disconnected, busy,
     * or still within the idle window.
     */
    suspend fun releaseIfIdle(
        nowMillis: Long = clockMillis(),
        maxIdleMillis: Long = idleTimeoutMillis,
    ): Boolean = mutex.withLock {
        val active = activeActivation ?: return@withLock false
        if (inFlight > 0) return@withLock false
        if (maxIdleMillis > 0 && (nowMillis - lastActivityEpochMillis) < maxIdleMillis) {
            return@withLock false
        }
        activeActivation = null
        active.close()
        true
    }

    override suspend fun preview(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): Result<Preview> = Result.success(Preview.Description("MCP call: $operation"))

    override suspend fun cancel(operationId: String) {
        activeActivation?.tool?.cancel(operationId)
    }

    override fun close() {
        activeActivation?.client?.close()
        activeActivation = null
    }

    suspend fun closeSuspend() {
        mutex.withLock {
            val active = activeActivation
            activeActivation = null
            active?.close()
        }
    }

    companion object {
        const val DEFAULT_IDLE_TIMEOUT_MILLIS: Long = 300_000L
    }
}

fun McpActivationFailure.describe(): String = when (this) {
    is McpActivationFailure.ServerNotFound -> "MCP server '$serverName' not found"
    is McpActivationFailure.ServerRejected -> "MCP server '$serverName' rejected: $reason"
    is McpActivationFailure.SecretUnresolved -> "MCP secret reference '$reference' for server '$serverName' could not be resolved"
    is McpActivationFailure.ConnectFailed -> "MCP connection to '$serverName' failed: $message"
}
