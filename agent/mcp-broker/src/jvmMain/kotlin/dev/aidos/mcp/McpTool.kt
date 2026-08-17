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
import dev.aidos.mcp.core.McpClient
import dev.aidos.mcp.core.McpServerRegistration
import kotlinx.serialization.json.JsonObject

/**
 * Adapts a live [McpClient] into a broker [Tool] (RFC-0031, M18): the actual `execute()` path
 * the audit found missing entirely — `McpToolAdapter.descriptorsFor()` already said what the
 * model sees; this is what runs when the model calls one of those descriptors.
 *
 * Every result is [TrustLevel.UNTRUSTED] (RFC-0027), unconditionally — never derived from
 * anything the server itself reports, because a server is never in a position to attest to its
 * own results' trustworthiness (D30: no server-granted trust).
 *
 * **Deliberately not wired into `ToolBroker`/`RuntimeCompositionRoot` by this link.** That needs
 * the user-scope registration loading (`mcp_servers`, RFC-0054), the enable-time capability grant
 * (effect-class question, issued as `SubjectKind.MCP_SERVER` capability rows), and the
 * `mcp_operation_adoptions` per-operation adoption flow — none of which exist yet. This class is
 * the transport-to-broker seam those pieces will eventually call through; constructing one today
 * still reaches nothing a live Run can invoke. See PIPELINE.md's M18 entry for the full scoping.
 */
class McpTool(
    private val registration: McpServerRegistration,
    private val client: McpClient,
) : Tool {

    override val id: String = registration.serverId
    override val version: String = "1"

    override fun operations(): List<ToolDescriptor> = McpToolAdapter.descriptorsFor(registration)

    override suspend fun execute(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): ToolCallResult {
        val toolName = operation.removePrefix("${registration.serverId}:")
        return runCatching { client.callTool(toolName, arguments) }.fold(
            onSuccess = { result ->
                val content = result.content.map { it.toContentBlock() }
                ToolCallResult(
                    callId = "",
                    outcome = if (result.isError) {
                        ToolOutcome.Failed(AidosError(
                            code = "mcp.tool_error",
                            errorClass = ErrorClass.TRANSIENT,
                            message = content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text },
                        ))
                    } else {
                        ToolOutcome.Ok
                    },
                    content = content,
                    trustLevel = TrustLevel.UNTRUSTED,
                )
            },
            onFailure = { error ->
                ToolCallResult(
                    callId = "",
                    outcome = ToolOutcome.Failed(AidosError(
                        code = "mcp.transport_error",
                        errorClass = ErrorClass.TRANSIENT,
                        message = error.message ?: "unknown MCP transport error",
                    )),
                    content = listOf(ContentBlock.Text(error.message ?: "unknown MCP transport error")),
                    trustLevel = TrustLevel.UNTRUSTED,
                )
            },
        )
    }

    override suspend fun preview(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): Result<Preview> = Result.success(Preview.Description("MCP call: $operation"))

    /** No per-call cancellation exists on the wire in this MVP (RFC-0031's own error-handling section names crash/timeout/restart, not mid-call cancel). */
    override suspend fun cancel(operationId: String) {}
}
