package dev.aidos.kernel

/**
 * The single error taxonomy (RFC-0029), shared by the Runtime API, the Effect Broker, the agent
 * loop, and the Execution Graph.
 *
 * [code] is a stable namespaced string rather than an enum, because plugins and MCP adapters
 * introduce codes the core does not know and an enum would flatten them all to `UNKNOWN`.
 * Callers switch on [errorClass], which is closed and small.
 */
data class AidosError(
    val code: String,
    val errorClass: ErrorClass,
    val message: String,
    val detail: Map<String, String> = emptyMap(),
    val cause: AidosError? = null,
)

/**
 * The class determines behaviour: retryability, audience, and terminal effect.
 * Nothing else in the runtime decides these per call site.
 */
enum class ErrorClass {
    /** Retry with backoff. */
    TRANSIENT,

    /** Retry honouring the provider's `Retry-After`. */
    RATE_LIMITED,

    /**
     * Routed to the *model*, not the user. A model that emitted arguments failing schema
     * validation must be told so it can correct them; surfacing that to the user is noise and
     * failing the Run wastes the work so far.
     */
    INVALID_INPUT,

    /** Denied until authority changes. Returned to the model as data; may prompt the user. */
    DENIED,

    /** Not available on this profile or offline (RFC-0049). */
    UNAVAILABLE,

    /** A budget or ceiling was reached (RFC-0028). Terminates the Run. */
    EXHAUSTED,

    /** Needs reconciliation before work can continue (RFC-0053). */
    CONFLICT,

    /**
     * An `UNSAFE` effect that may or may not have landed (RFC-0009).
     * **Never retried.** Surfaced to the user with what is known.
     */
    INDETERMINATE,

    /** A defect. Terminates the Run; a bug report. */
    INTERNAL,
    ;

    val isRetryable: Boolean get() = this == TRANSIENT || this == RATE_LIMITED

    /** Whether the error is rendered into the transcript for the model to react to. */
    val isModelAudience: Boolean
        get() = this == INVALID_INPUT || this == DENIED || this == TRANSIENT ||
            this == RATE_LIMITED || this == UNAVAILABLE
}

/** Codes the kernel itself raises. Subsystems add their own; the class is what matters. */
object ErrorCodes {
    const val TOOL_UNKNOWN = "tool.unknown"
    const val TOOL_INVALID_ARGUMENTS = "tool.invalid_arguments"
    const val TOOL_UNAVAILABLE_ON_PROFILE = "tool.unavailable_on_profile"
    const val CAPABILITY_DENIED = "capability.denied"
    const val CAPABILITY_REQUIRES_APPROVAL = "capability.requires_approval"
    const val CAPABILITY_ATTENUATED_BY_TAINT = "capability.attenuated_by_taint"
    const val MODEL_CONTEXT_OVERFLOW = "model.context_overflow"
    const val MODEL_UNAVAILABLE_OFFLINE = "model.unavailable_offline"
    const val BUDGET_EXHAUSTED = "budget.exhausted"
    const val RUN_STEP_LIMIT = "run.step_limit"
    const val RUN_NO_PROGRESS = "run.no_progress"
    const val RUN_FOREGROUND_REQUIRED = "run.foreground_required"
    const val GIT_REPO_MUTATED = "git.repo_mutated"
    const val GIT_PUSH_INDETERMINATE = "git.push_indeterminate"
    const val STORAGE_MIGRATION_REQUIRED = "storage.migration_required"
    const val RUNTIME_LOCKED_BY_OTHER_INSTANCE = "runtime.locked_by_other_instance"
    const val INTENT_CYCLE_REJECTED = "intent.cycle_rejected"
    const val CONTENT_CYCLE_REJECTED = "content.cycle_rejected"
    const val EXECUTION_CYCLE_REJECTED = "execution.cycle_rejected"
}
