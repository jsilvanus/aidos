package dev.aidos.amp

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.ToolAvailability
import dev.aidos.kernel.ToolDescriptor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * High-level tool definition provided by the AMP consumer to the Aidos agent core.
 */
data class AmpToolDefinition(
    val name: String,
    val description: String,
    val inputSchema: JsonObject,
    val handler: suspend (arguments: JsonObject) -> AmpToolExecutionResult,
) {
    fun toToolDescriptor(): ToolDescriptor = ToolDescriptor(
        name = name,
        title = name,
        description = description,
        inputSchema = inputSchema,
        effect = EffectKind.Read,
        requiredPermission = Permission.MODEL_QUERY,
        recoveryClass = RecoveryClass.PURE,
        availability = ToolAvailability(
            profiles = setOf(PlatformProfile.MOBILE, PlatformProfile.DESKTOP),
            tier = AvailabilityTier.UNIVERSAL,
        ),
    )
}

/**
 * Result of an AMP tool execution.
 */
data class AmpToolExecutionResult(
    val outputText: String,
    val isError: Boolean = false,
)

/**
 * Representation of a prior turn in the AMP conversation history.
 */
@Serializable
data class AmpConversationTurn(
    val role: String, // "user", "assistant", "system"
    val text: String,
    val senderId: String? = null,
)

/**
 * Final result produced by [AmpAgentRunner] for an agentic turn.
 */
data class AmpAgentTurnResult(
    val replyText: String,
    val executedToolCalls: List<ExecutedToolCall> = emptyList(),
    val totalSteps: Int = 1,
)

data class ExecutedToolCall(
    val toolName: String,
    val argumentsJson: String,
    val resultText: String,
)
