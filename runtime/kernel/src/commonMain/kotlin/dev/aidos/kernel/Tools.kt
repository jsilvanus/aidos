package dev.aidos.kernel

import kotlinx.serialization.json.JsonObject

/**
 * How a tool operation is described to a model (RFC-0008).
 *
 * **Structurally aligned with MCP's tool shape** — `name`, `description`, `inputSchema` as JSON
 * Schema — and that alignment is a locked decision (D23). Runtime-only fields are strictly
 * additive and never mixed into what a model or an MCP server sees. No custom schema dialect, no
 * Aidos-specific type system.
 */
data class ToolDescriptor(
    val name: String,
    val title: String,
    val description: String,
    val inputSchema: JsonObject,

    /**
     * How to *read* this operation's output — what is significant, useful thresholds, caveats,
     * and what a citation should look like.
     *
     * Distinct from [description], which says how to *call* it. A knowledge tool returning
     * ranked matches with similarity scores is the clearest case: without guidance a model
     * treats `0.4` as a finding, and with it treats it as weak evidence. That difference is D6
     * — a model confirming its own success on evidence that does not support it.
     *
     * Emitted with the tool *result*, not with the tool definition, so it stays out of the
     * MCP-shaped surface above (D23).
     *
     * **Runtime-authored, and `TRUSTED` accordingly.** A tool never supplies its own — least of
     * all an MCP server, which is an `UNTRUSTED` subject (RFC-0027) and would otherwise be
     * telling the model how to weigh its own output.
     */
    val resultGuidance: String? = null,

    // Runtime-side. Never sent to the model.
    val effect: EffectKind,
    val requiredPermission: Permission,
    val recoveryClass: RecoveryClass,
    val availability: ToolAvailability,
)

/**
 * A tool call emitted by a model, normalized out of whatever shape the provider used.
 *
 * Arguments are **not** validated yet. Validation happens before capability resolution, and
 * capability resolution happens before any effect (RFC-0008).
 */
data class ToolCall(
    val callId: String,
    val toolName: String,
    val arguments: JsonObject,

    /** The capability the caller names for this exercise. Absent means denied. */
    val capabilityId: CapabilityId?,

    /** Retained when parsing was heuristic; parsing ambiguity is security-relevant. */
    val rawText: String? = null,
)

data class ToolCallResult(
    val callId: String,
    val outcome: ToolOutcome,
    val content: List<ContentBlock>,

    /** Almost always UNTRUSTED. Tool results taint the Run (RFC-0027). */
    val trustLevel: TrustLevel,
)

/**
 * `Denied` and `Failed` are returned **to the model**, not raised as exceptions. A model that
 * asks for something it may not have must be told, so it can adapt. That is the difference
 * between an agent and a crash.
 */
sealed interface ToolOutcome {
    data object Ok : ToolOutcome
    data class Denied(val reason: DenialReason) : ToolOutcome
    data class Failed(val error: AidosError) : ToolOutcome
    data object Cancelled : ToolOutcome
}

sealed interface ContentBlock {
    data class Text(val text: String) : ContentBlock
    data class Image(val mimeType: String, val data: ByteArray) : ContentBlock {
        override fun equals(other: Any?): Boolean =
            other is Image && other.mimeType == mimeType && other.data.contentEquals(data)
        override fun hashCode(): Int = 31 * mimeType.hashCode() + data.contentHashCode()
    }
    /** A reference, not bulk content — the same rule the event bus follows (RFC-0004). */
    data class ResourceRef(val nodeId: ContentNodeId, val sizeBytes: Long) : ContentBlock
}

/**
 * A tool.
 *
 * Note what is absent: no `sessionId` parameter. A tool does not need to know which session
 * called it, and passing it invited tools to make their own authority decisions. Scope arrives
 * in the handle.
 */
interface Tool {
    val id: String
    val version: String

    fun operations(): List<ToolDescriptor>

    /** Arguments are already schema-validated by the loop before this is called. */
    suspend fun execute(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): ToolCallResult

    /** Required for every [EffectKind.Mutate]. */
    suspend fun preview(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): Result<Preview>

    /** RFC-0006 specifies cancellation; there was previously no method for it. */
    suspend fun cancel(operationId: String)
}

/**
 * The single path through which sessions reach the outside world (RFC-0030).
 *
 * Validation, capability resolution, budget reservation, preview, audit, and taint all happen
 * here, in that order. Nothing bypasses it.
 */
interface EffectBroker {
    fun register(tool: Tool)

    /** Filtered by profile, connectivity, and the subject's grants before a model sees them. */
    fun descriptorsFor(
        subjectId: String,
        profile: PlatformProfile,
        networkAvailable: Boolean,
    ): List<ToolDescriptor>

    suspend fun invoke(
        subjectId: String,
        call: ToolCall,
        runTaint: TrustLevel,
    ): ToolCallResult

    suspend fun preview(subjectId: String, call: ToolCall): Result<Preview>

    suspend fun cancel(callId: String)
}
