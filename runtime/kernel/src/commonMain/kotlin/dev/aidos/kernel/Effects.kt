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

    /**
     * Changes state. Previewable; approval depends on scope, Run taint, and [reversible].
     *
     * `reversible = false` means the operation destroys work that exists nowhere else — a branch
     * switch discarding uncommitted changes, a hard reset, a file delete outside Git's reach.
     * It is **not** the same question as [RecoveryClass], which asks whether an effect can be
     * re-run after a crash. An operation can be perfectly re-runnable and still annihilate an
     * hour of the user's typing.
     *
     * Conflating the two would have let a destructive checkout into D26's benign class — an
     * in-project, re-runnable mutation, approvable by saying "approve" while cycling.
     */
    data class Mutate(
        val scope: MutationScope,
        val reversible: Boolean = true,
    ) : EffectKind

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
 * How much verification an approval needs before it may be given (RFC-0057, D26).
 *
 * Attention is the scarce resource on a phone, so the three modes — focused, glance, eyes-free —
 * are not equally capable of verifying a request. This decides which mode suffices.
 */
enum class ApprovalTier {
    /** In-project, reversible, untrusted-free, already granted. A glance, or one spoken word. */
    BENIGN,

    /** Out-of-project or irreversible. Structured readback, then a phrase naming the action. */
    READBACK,

    /** Egress, tainted Run, or a new grant. Needs the user's eyes. Never answerable by voice. */
    EYES_ONLY,
}

/**
 * D26's classifier, executable rather than prose.
 *
 * Note that [EffectKind.Mutate.reversible] is consulted separately from [RecoveryClass]. An
 * earlier form of this rule used `recovery != UNSAFE` as the proxy for "reversible", which let a
 * branch switch that discards uncommitted work — in-project, untainted, and perfectly
 * re-runnable — qualify as [ApprovalTier.BENIGN], and therefore be approvable by saying
 * "approve" while cycling.
 */
fun approvalTier(
    effect: EffectKind,
    recoveryClass: RecoveryClass,
    runTaint: TrustLevel,
    isNewGrant: Boolean,
): ApprovalTier = when {
    isNewGrant -> ApprovalTier.EYES_ONLY
    runTaint != TrustLevel.TRUSTED -> ApprovalTier.EYES_ONLY
    effect is EffectKind.Egress -> ApprovalTier.EYES_ONLY

    recoveryClass == RecoveryClass.UNSAFE -> ApprovalTier.READBACK
    effect is EffectKind.Mutate && effect.scope == MutationScope.OUT_OF_PROJECT ->
        ApprovalTier.READBACK
    effect is EffectKind.Mutate && !effect.reversible -> ApprovalTier.READBACK

    else -> ApprovalTier.BENIGN
}

/**
 * What a mutation *would* do, without doing it.
 *
 * Required for every [EffectKind.Mutate]. This is what makes "show me what the agent is about to
 * do" a runtime feature rather than a per-tool courtesy, and it powers dry-run, the approval
 * dialog, and the audit record.
 */
sealed interface Preview {
    /**
     * Structured, not a unified-diff string (RFC-0052, D25). A mid-Run approval card and a
     * commit-time hunk card are the same decision at different moments, rendered by the same
     * component (RFC-0050) — two shapes would put a diff parser back in every frontend.
     */
    data class Diff(val fileDiff: FileDiff) : Preview

    data class Patch(val summary: String, val unified: String) : Preview
    data class Description(val text: String) : Preview
    data object NoChange : Preview
}
