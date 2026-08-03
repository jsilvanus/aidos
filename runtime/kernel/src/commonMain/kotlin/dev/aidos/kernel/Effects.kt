package dev.aidos.kernel

/**
 * What an operation does to the world (RFC-0030).
 *
 * Untyped tools made preview, undo, retry, approval, and audit impossible to implement
 * generically, because the broker had no idea what an operation would do. Behaviour below is
 * derived from the effect, uniformly, rather than decided per tool.
 */
sealed interface EffectKind {
    /** Observes state. Cacheable, retryable, no approval. May run concurrently (RFC-0006). */
    data object Read : EffectKind

    /** Changes state. Previewable; approval depends on scope and Run taint. */
    data class Mutate(val scope: MutationScope) : EffectKind

    /** Sends data off the device. Subject to egress policy and taint attenuation. */
    data class Egress(val destination: String) : EffectKind

    /** Reaches the user. Rate-limited, and never silently repeated (RFC-0044). */
    data object Notify : EffectKind
}

enum class MutationScope { IN_PROJECT, OUT_OF_PROJECT }

/**
 * Whether an effect can be re-run after a crash (RFC-0009).
 *
 * Every operation must declare this. It is the single most important thing the durable execution
 * model asks of tool authors, and it is why the effect taxonomy is typed at all.
 */
enum class RecoveryClass {
    /** No external effect. Re-execute. */
    PURE,

    /** Re-executing is safe. Re-execute. */
    IDEMPOTENT,

    /** The effect can be observed after the fact. Probe, then re-execute or adopt. */
    CHECKABLE,

    /**
     * Cannot be re-run or observed — `git push`, a notification, an HTTP POST.
     *
     * **Never retried.** Reported as [ErrorClass.INDETERMINATE] with what is known. Silently
     * retrying is how duplicate pushes and double notifications happen.
     */
    UNSAFE,
    ;

    val isRetryable: Boolean get() = this != UNSAFE
}

/**
 * What a mutation *would* do, without doing it.
 *
 * Required for every [EffectKind.Mutate]. This is what makes "show me what the agent is about to
 * do" a runtime feature rather than a per-tool courtesy, and it powers dry-run, the approval
 * dialog, and the audit record.
 */
sealed interface Preview {
    data class Diff(val path: String, val unified: String) : Preview
    data class Patch(val summary: String, val unified: String) : Preview
    data class Description(val text: String) : Preview
    data object NoChange : Preview
}
