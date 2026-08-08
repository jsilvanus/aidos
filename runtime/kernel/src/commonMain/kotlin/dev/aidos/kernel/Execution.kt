package dev.aidos.kernel

import kotlinx.datetime.Instant

/**
 * The Execution Graph (RFC-0019).
 *
 * Not a log written beside execution — **it is the program**. The executor drives these rows
 * (RFC-0009), which is why recovery is a query rather than a restore, and why the audit trail is
 * a byproduct rather than a duplicate write.
 */
data class Run(
    val id: RunId,
    val sessionId: SessionId,
    val projectId: ProjectId,
    val triggerEventId: EventId,
    val startedAt: Instant,
    val endedAt: Instant?,
    val state: RunState,
    val error: AidosError?,
    val userMessageSummary: String?,
    val retryPolicy: RetryPolicy,
    val stepIndex: Int,
    val maxSteps: Int,

    /** Monotonic within the Run. Never decreases (RFC-0027). */
    val taintLevel: TrustLevel,
    val taintSourceNodeId: ContentNodeId?,

    /** Recorded for provenance: "why did this Run not run the tests?" (RFC-0049) */
    val platformProfile: PlatformProfile,
    val networkAvailable: Boolean,
    val degradedTools: List<String>,
)

enum class RunState {
    PENDING,
    RUNNING,
    YIELDED,
    COMPLETED,
    FAILED,
    CANCELLED,
    INTERRUPTED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED
}

data class Task(
    val id: TaskId,
    val runId: RunId,

    /** Null for emergent tasks; set for declared plans (RFC-0019). */
    val planId: PlanId?,
    val sessionId: SessionId,
    val projectId: ProjectId,
    val ordinal: Int,
    val kind: TaskKind,
    val description: String,
    val toolName: String?,
    val modelKind: ModelKind?,
    val state: TaskState,
    val startedAt: Instant?,
    val endedAt: Instant?,

    /** Set when parked on a child session's Run — worker fan-out (RFC-0006). */
    val awaitingRunId: RunId?,
    val retryPolicy: RetryPolicy,

    /** How this approval was given, if at all: tap | voice_tier1 | voice_tier2 (RFC-0057, D26). */
    val approvalChannel: String?,

    /** For tier 2 approvals: the recognized phrase naming the action (RFC-0057). */
    val approvalPhrase: String?,
)

enum class TaskKind { MODEL_CALL, TOOL_CALL, CAPABILITY_REQUEST, USER_PROMPT, COMPOSITE }

enum class TaskState {
    PENDING,
    RUNNING,
    AWAITING_APPROVAL,
    AWAITING_INPUT,
    COMPLETED,
    FAILED,
    CANCELLED,
    SKIPPED,
    ;

    val isTerminal: Boolean
        get() = this == COMPLETED || this == FAILED || this == CANCELLED || this == SKIPPED

    val isParked: Boolean
        get() = this == AWAITING_APPROVAL || this == AWAITING_INPUT
}

data class Attempt(
    val id: AttemptId,
    val taskId: TaskId,
    val attemptNumber: Int,
    val startedAt: Instant,
    val endedAt: Instant?,
    val state: AttemptState,
    val error: AidosError?,
    val modelProvider: String?,
    val modelVersion: String?,
    val tokensInput: Int?,
    val tokensOutput: Int?,
    val costUnits: Long?,

    /** The authority actually exercised — named, not inferred. */
    val capabilityId: CapabilityId?,
    val idempotencyKey: String?,
    val recoveryClass: RecoveryClass,
    val auditRef: AuditId,
)

enum class AttemptState { RUNNING, COMPLETED, FAILED, CANCELLED }

data class RetryPolicy(
    val maxAttempts: Int,
    /** Retry is driven by error *class*, not a per-site boolean (RFC-0029). */
    val retryOn: Set<ErrorClass>,
    val backoff: BackoffStrategy,
) {
    /**
     * A Task may only be retried if its effect can be re-run. Policy never overrides this:
     * retrying an interrupted `git push` is forbidden regardless of configuration.
     */
    fun permits(error: AidosError, recoveryClass: RecoveryClass, attemptNumber: Int): Boolean =
        recoveryClass.isRetryable &&
            attemptNumber < maxAttempts &&
            error.errorClass in retryOn
}

sealed interface BackoffStrategy {
    data object None : BackoffStrategy
    data class Fixed(val delaySeconds: Int) : BackoffStrategy
    data class Exponential(val initialSeconds: Int, val maxSeconds: Int) : BackoffStrategy
}

/** What a parked Run is waiting for. A description, not a resumable handle (RFC-0006). */
sealed interface SuspendedOperation {
    data class AiCall(val requestId: String) : SuspendedOperation
    data class ToolCall(val toolName: String, val callId: String) : SuspendedOperation
    data class UserPrompt(val promptId: String, val question: String) : SuspendedOperation
    data class CapabilityApproval(val requestId: String, val permission: Permission) : SuspendedOperation

    /** Waiting on a worker. Previously unrepresentable, which made fan-out inexpressible. */
    data class ChildRun(val childRunId: RunId, val childSessionId: SessionId) : SuspendedOperation

    /** MOBILE reached a local model call without a foreground service (D24). */
    data class ForegroundRequired(val reason: ForegroundReason) : SuspendedOperation
}

enum class ForegroundReason { LOCAL_INFERENCE }

/**
 * The executor (RFC-0009).
 *
 * `drive` is re-entrant and idempotent. Calling it on a complete Run is a no-op; calling it after
 * a crash resumes exactly where the rows say execution was. There is no in-memory state that
 * must survive — which is the entire reason the model is a step machine rather than a suspended
 * coroutine.
 */
interface Executor {
    suspend fun drive(runId: RunId)

    /**
     * Returns the runnable set, not a single Task.
     *
     * The invariant is that at most one *effectful* Task is RUNNING per Run; `Read` effects may
     * proceed concurrently, because they have no order that matters (D14).
     */
    suspend fun nextRunnableTasks(runId: RunId): List<Task>

    /** Applies recovery classes to interrupted attempts, releases reservations, re-validates. */
    suspend fun recover(projectId: ProjectId): RecoveryReport
}

data class RecoveryReport(
    val runsExamined: Int,
    val runsResumed: Int,
    val runsFailed: Int,
    val indeterminateEffects: List<AttemptId>,
    val reservationsReleased: Int,
)
