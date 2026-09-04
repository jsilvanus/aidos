package dev.aidos.kernel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.serialization.Serializable
import kotlin.time.Duration

enum class ModelKind { LLM, EMBEDDING, STT, TTS, VISION, OCR, RERANKER, TRANSLATION }

interface ModelAdapter {
    val providerId: String
    val modelId: String
    val modelVersion: String
    val contextWindow: Int
    val isLocal: Boolean
    fun supportsNativeToolCalls(): Boolean
    suspend fun invoke(request: ModelRequest): Result<ModelResponse>

    /**
     * Token-by-token variant of [invoke] (RFC-0021, "Streaming"). The default falls back to
     * [invoke] and emits its result as a single terminal event — correct for any adapter whose
     * backend has no partial output to offer. An adapter backed by a token-emitting inference
     * engine (e.g. llama.cpp) overrides this to yield real incremental [ModelStreamEvent.Delta]s
     * as they're produced, rather than a caller having to wait for [invoke] to return and then
     * chop the finished text into fake chunks.
     */
    suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        invoke(request).fold(
            onSuccess = { emit(ModelStreamEvent.Done(it)) },
            onFailure = { emit(ModelStreamEvent.Failed(it)) },
        )
    }

    val providerRetention: ProviderRetention? get() = null
}

/** Adapter capability for models that expose native vector embeddings. */
interface EmbeddingModelAdapter : ModelAdapter {
    /** Returns the model's native embedding vector without prompt/chat formatting. */
    suspend fun embed(text: String): Result<FloatArray>
}

/**
 * One event of a streamed [ModelAdapter.invokeStreaming] response. Named to parallel RFC-0052's
 * `RuntimeEvent.AiResponseDelta` one layer down the stack: this is the adapter-to-router event,
 * not the router-to-frontend event RFC-0052 already defines from it.
 */
sealed interface ModelStreamEvent {
    /** A partial increment of assistant text as it is produced. Zero or more per stream. */
    data class Delta(val text: String) : ModelStreamEvent

    /** Terminal: the complete response this stream produced, once generation finished. */
    data class Done(val response: ModelResponse) : ModelStreamEvent

    /** Terminal: generation failed partway through (or before producing any output at all). */
    data class Failed(val error: Throwable) : ModelStreamEvent
}

enum class RetentionPolicy { ZERO, TRANSIENT, RETAINED, UNKNOWN }
enum class TrainingUse { NONE, OPT_OUT_HONOURED, UNSPECIFIED }

@Serializable
data class ProviderRetention(
    val policy: RetentionPolicy,
    val statedDurationDays: Int?,
    val trainingUse: TrainingUse,
    val recordedAt: Instant,
)

data class ModelRequest(
    val messages: List<Turn>,
    val tools: List<ToolDescriptor>,
    val toolChoice: ToolChoice,
    val maxOutputTokens: Int,
    val stopConditions: List<String> = emptyList(),
)

/** Generalized RFC-0022 response. Outputs remain ordered and ModelOutput is intentionally open. */
data class ModelResponse(
    val outputs: List<ModelOutput>,
    val stopReason: StopReason?,
    val usage: Usage?,
    val model: ModelRef?,
)

sealed interface ToolChoice {
    data object Auto : ToolChoice
    data object None : ToolChoice
    data object Required : ToolChoice
    data class Specific(val toolName: String) : ToolChoice
}

enum class StopReason { END_TURN, TOOL_USE, MAX_TOKENS, STOP_SEQUENCE, REFUSAL }

sealed interface Turn {
    val trustLevel: TrustLevel
    data class System(val content: String) : Turn {
        override val trustLevel: TrustLevel get() = TrustLevel.TRUSTED
    }
    data class User(val content: List<ContentBlock>, override val trustLevel: TrustLevel) : Turn
    data class Assistant(val text: String?, val toolCalls: List<ToolCall>) : Turn {
        override val trustLevel: TrustLevel get() = TrustLevel.UNTRUSTED
    }
    data class ToolResult(val result: ToolCallResult) : Turn {
        override val trustLevel: TrustLevel get() = result.trustLevel
    }
}

interface InferenceRouter {
    suspend fun select(kind: ModelKind, context: RoutingContext): RoutingDecision
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
    data class UnavailableOffline(val kind: ModelKind) : RoutingDecision
    data class DisabledByPolicy(val reason: String) : RoutingDecision
    data object ForegroundRequired : RoutingDecision
}

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

@Serializable
sealed class Trigger {
    @Serializable data class At(val instant: Instant) : Trigger()
    @Serializable data class Every(val interval: Duration, val anchor: Instant? = null) : Trigger()
    @Serializable data class OnEvent(val filter: EventTriggerFilter) : Trigger()
    @Serializable data class Cron(val expression: String, val zone: TimeZone) : Trigger()
    @Serializable data class OnCondition(val predicate: ConditionRef) : Trigger()
}

@Serializable
data class EventTriggerFilter(val eventType: String, val metadata: Map<String, String> = emptyMap())

@Serializable
data class ConditionRef(val sessionId: String, val predicateName: String)

@Serializable
enum class GuaranteeClass { PROMPT, EVENTUAL, OPPORTUNISTIC }

@Serializable
enum class WorkClass { INTERACTIVE, DEFERRED, SCHEDULED, OPPORTUNISTIC }

@Serializable
data class ScheduledJob(
    val id: ScheduledJobId,
    val projectId: String,
    val sessionId: String?,
    val name: String,
    val trigger: Trigger,
    val guaranteeClass: GuaranteeClass,
    val workClass: WorkClass,
    val constraintsJson: String = "{}",
    val enabled: Boolean = true,
    val nextRunAt: Instant?,
    val lastRunAt: Instant?,
    val lastOutcome: String?,
    val consecutiveFailures: Int = 0,
    val missedOccurrences: Int = 0,
    val createdAt: Instant,
)
