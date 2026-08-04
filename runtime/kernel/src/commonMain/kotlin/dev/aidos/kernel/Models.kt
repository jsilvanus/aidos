package dev.aidos.kernel

/** Model classes. Not "capabilities" — that word means a security grant (RFC-0018). */
enum class ModelKind { LLM, EMBEDDING, STT, TTS, VISION, OCR, RERANKER, TRANSLATION }

/**
 * The provider-neutral model interface (RFC-0008).
 *
 * Adapters for models without native tool-calling — many local GGUF models — implement the same
 * interface using constrained decoding or a documented text protocol and report
 * [supportsNativeToolCalls] as false. **The loop above this boundary is identical**, which is
 * what makes the offline path a first-class citizen rather than a degraded one: the agent loop
 * does not know whether the model is local or remote.
 */
interface ModelAdapter {
    val providerId: String
    val modelId: String
    val modelVersion: String
    val contextWindow: Int
    val isLocal: Boolean

    fun supportsNativeToolCalls(): Boolean

    suspend fun invoke(request: ModelRequest): Result<ModelResponse>
}

data class ModelRequest(
    val messages: List<Turn>,
    val tools: List<ToolDescriptor>,
    val toolChoice: ToolChoice,
    val maxOutputTokens: Int,
    val stopConditions: List<String> = emptyList(),
)

data class ModelResponse(
    val text: String?,
    val toolCalls: List<ToolCall>,
    val stopReason: StopReason,
    val usage: TokenUsage,
    val modelId: String,
    val modelVersion: String,
)

sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Specific(val toolName: String) : ToolChoice
}

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, STOP_SEQUENCE, REFUSAL }

data class TokenUsage(val inputTokens: Int, val outputTokens: Int)

sealed interface Turn {
    val trustLevel: TrustLevel

    data class System(val content: String) : Turn {
        override val trustLevel: TrustLevel get() = TrustLevel.TRUSTED
    }

    data class User(
        val content: List<ContentBlock>,
        override val trustLevel: TrustLevel,
    ) : Turn

    /** Model output is UNTRUSTED: it is a function of input that included untrusted content. */
    data class Assistant(
        val text: String?,
        val toolCalls: List<ToolCall>,
    ) : Turn {
        override val trustLevel: TrustLevel get() = TrustLevel.UNTRUSTED
    }

    data class ToolResult(
        val result: ToolCallResult,
    ) : Turn {
        override val trustLevel: TrustLevel get() = result.trustLevel
    }
}

/**
 * Model selection (RFC-0020).
 *
 * Routing decides whether the user's code leaves the device, so it is **user-owned policy, not
 * an engine heuristic**. Falling back across the network boundary is never automatic unless the
 * user has said so.
 *
 * Selection happens *before* prompt assembly, because the token budget derives from the chosen
 * model's context window (RFC-0025). Assembly may report that the prompt cannot fit, which
 * returns here for a larger-context candidate — a bounded two-phase negotiation, not a loop.
 */
interface InferenceRouter {
    suspend fun select(
        kind: ModelKind,
        context: RoutingContext,
    ): RoutingDecision
}

data class RoutingContext(
    val profile: PlatformProfile,
    val networkAvailable: Boolean,
    val budgetRemaining: Budget?,
    val runTaint: TrustLevel,
    val minimumContextWindow: Int? = null,
    val executionWindow: ExecutionWindow,
)

sealed interface RoutingDecision {
    data class Local(val adapter: ModelAdapter) : RoutingDecision
    data class RemoteApproved(val adapter: ModelAdapter) : RoutingDecision
    data class RemotePendingApproval(val adapter: ModelAdapter, val reason: String) : RoutingDecision

    /** Expected on MOBILE, not an error. The user is told which model kind is missing. */
    data class UnavailableOffline(val kind: ModelKind) : RoutingDecision

    data class DisabledByPolicy(val reason: String) : RoutingDecision

    /** MOBILE without a foreground service: park, do not route remote instead (D24). */
    data object ForegroundRequired : RoutingDecision
}

/**
 * Weights, downloads, and loaded instances. **User scope** (RFC-0054): multi-gigabyte, and one
 * loaded instance can saturate a phone, so loading is globally serialized through an admission
 * queue rather than being per-project.
 */
interface ModelRuntime {
    suspend fun catalog(): List<ModelDescriptor>
    suspend fun installed(): List<ModelDescriptor>
    suspend fun load(modelId: String): Result<ModelAdapter>
    suspend fun unload(modelId: String)
    fun loaded(): List<String>
}

data class ModelDescriptor(
    val id: String,
    val name: String,
    val kind: ModelKind,
    val providerId: String,
    val isLocal: Boolean,
    val contextWindow: Int,
    val sizeBytes: Long?,
    val digest: String?,
)
