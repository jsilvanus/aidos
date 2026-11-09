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
import kotlinx.serialization.json.JsonObject

/**
 * A lazy [Tool] implementation representing an adopted MCP server (RFC-0031).
 *
 * It exposes adopted operations without connecting on project open (D30).
 * Connection and handshake are deferred until [execute] is called for the first time.
 */
class LazyMcpTool(
    val serverId: String,
    val projectId: String,
    private val initialAdoptedSpecs: List<McpToolSpec>,
    private val transport: McpTransport,
    private val activator: suspend () -> McpActivationOutcome,
) : Tool, AutoCloseable {

    override val id: String = serverId
    override val version: String = "1"

    @Volatile
    private var activeActivation: McpServerActivation? = null

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
        var active = activeActivation
        if (active == null) {
            when (val outcome = activator()) {
                is McpActivationOutcome.Activated -> {
                    active = outcome.activation
                    activeActivation = active
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
        return active.tool.execute(handle, operation, arguments)
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
        activeActivation?.close()
        activeActivation = null
    }
}

fun McpActivationFailure.describe(): String = when (this) {
    is McpActivationFailure.ServerNotFound -> "MCP server '$serverName' not found"
    is McpActivationFailure.ServerRejected -> "MCP server '$serverName' rejected: $reason"
    is McpActivationFailure.SecretUnresolved -> "MCP secret reference '$reference' for server '$serverName' could not be resolved"
    is McpActivationFailure.ConnectFailed -> "MCP connection to '$serverName' failed: $message"
}
