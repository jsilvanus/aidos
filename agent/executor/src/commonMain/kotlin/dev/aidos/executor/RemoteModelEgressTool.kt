package dev.aidos.executor

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolAvailability
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Brokered egress gate for remote [dev.aidos.kernel.ModelAdapter] invocations (RFC-0021, RFC-0023).
 *
 * Registering this tool on [dev.aidos.broker.ToolBroker] ensures every remote model call passes
 * through `EffectBroker.invoke()` — enforcing `Permission.NETWORK_EGRESS` capability validation,
 * RFC-0027 taint attenuation, and `audit_log` persistence without exposing an internal operation
 * to the model's callable tool list.
 */
class RemoteModelEgressTool : Tool {
    override val id: String = TOOL_ID
    override val version: String = "1.0.0"

    private val descriptor = ToolDescriptor(
        name = MODEL_EGRESS_TOOL_NAME,
        title = "Remote model egress",
        description = "Internal brokered egress gate for remote model invocations.",
        inputSchema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("provider", buildJsonObject { put("type", "string") })
                    put("model", buildJsonObject { put("type", "string") })
                },
            )
            put("required", JsonArray(listOf(JsonPrimitive("provider"), JsonPrimitive("model"))))
        },
        effect = EffectKind.Egress("remote-model"),
        requiredPermission = Permission.NETWORK_EGRESS,
        recoveryClass = RecoveryClass.IDEMPOTENT,
        availability = ToolAvailability(
            profiles = PlatformProfile.entries.toSet(),
            tier = AvailabilityTier.NETWORKED,
            requiresNetwork = true,
        ),
    )

    override fun operations(): List<ToolDescriptor> = listOf(descriptor)

    override suspend fun execute(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): ToolCallResult {
        val provider = arguments["provider"]?.jsonPrimitive?.content ?: "unknown"
        val model = arguments["model"]?.jsonPrimitive?.content ?: "unknown"
        return ToolCallResult(
            callId = handle.capabilityId.value,
            outcome = ToolOutcome.Ok,
            content = listOf(ContentBlock.Text("egress authorized for $provider/$model")),
            trustLevel = TrustLevel.TRUSTED,
        )
    }

    override suspend fun preview(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): Result<Preview> = Result.success(Preview.Description("Remote model egress"))

    override suspend fun cancel(operationId: String) = Unit

    companion object {
        const val TOOL_ID: String = "model_egress"
        const val MODEL_EGRESS_TOOL_NAME: String = "model:egress"
    }
}

/**
 * Lightweight KMP projection of a recalled RFC-0026 memory entry passed into
 * [AgentLoopTaskRunner] without coupling `:executor` (JVM+Android) to `:memory` (JVM).
 */
data class RecalledMemoryItem(
    val id: String,
    val kind: String,
    val content: String,
    val trustLevel: TrustLevel,
    val inferred: Boolean = false,
)
