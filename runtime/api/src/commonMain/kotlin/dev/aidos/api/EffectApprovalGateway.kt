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
     * [projectDriver] is the project's own already-open `state.db` driver, matching
     * [RunExecutor.send]'s own convention.
     */
    suspend fun resolve(
        projectDriver: SqlDriver,
        runId: String,
        approved: Boolean,
        denialReason: String?,
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
