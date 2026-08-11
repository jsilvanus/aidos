package dev.aidos.api

import app.cash.sqldelight.db.SqlDriver

/**
 * Resolves a Run parked on a `CAPABILITY_APPROVAL` continuation (RFC-0008 step 8d) — the
 * `approve`/`deny` counterpart to [RunExecutor], same seam idiom and same reason it exists:
 * `executor` depends on `prompt`, which depends on `api`, so `api` depending on `executor`
 * directly would cycle. `daemon`'s `RuntimeCompositionRoot` implements this using the real
 * `executor` types it is free to depend on; `api` only knows the shape of "resolve a pending
 * approval."
 *
 * Unset (`RealRuntimeClient.effectApprovalGateway == null`) preserves the pre-wiring no-op stub
 * `CapabilityCommands.approveEffect`/`denyEffect` had before this — the same fallback pattern
 * [RunExecutor], [KnowledgeService], and the persistence seams already use.
 */
interface EffectApprovalGateway {
    /**
     * Resolves `CAPABILITY_APPROVAL` or `TOOL_CALL`, whichever is actually parked — [approved]
     * is meaningless for `USER_PROMPT` (there is no yes/no to give, only an answer), so a `false`
     * here also reaches a parked question's decline path (see `daemon`'s `resolveAnyApproval` for
     * the dispatch), but approving a question is [answer]'s job, not this one's.
     *
     * [projectDriver] is the project's own already-open `state.db` driver, matching
     * [RunExecutor.send]'s own convention.
     */
    suspend fun resolve(
        projectDriver: SqlDriver,
        runId: String,
        approved: Boolean,
        denialReason: String?,
    ): EffectResolution

    /**
     * Resolves a Run parked on `USER_PROMPT` (the model's `ask_user` tool call) with a free-text
     * [answer]. Has no counterpart in [resolve] — a question is not a yes/no decision.
     */
    suspend fun answer(
        projectDriver: SqlDriver,
        runId: String,
        answer: String,
    ): EffectResolution
}

sealed interface EffectResolution {
    /** Approved: the Run resumed (its ultimate outcome — further progress, completion, or a
     *  later failure — is whatever that resume produced; this only confirms it happened). */
    data object Resumed : EffectResolution

    /** Denied: the parked task and its Run are now failed. */
    data object Denied : EffectResolution

    /** No pending `CAPABILITY_APPROVAL` continuation exists for this Run. */
    data object NotFound : EffectResolution
}
