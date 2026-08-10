package dev.aidos.api

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile

/**
 * Creates a durable Run for a user message (RFC-0008, RFC-0009), once storage and the
 * AgentLoop↔executor bridge (`runtime/executor/RunCreator.kt`, `AgentLoopTaskRunner.kt`) are
 * wired.
 *
 * **Why this seam exists instead of `RealRuntimeClient` calling `executor` directly:** `executor`
 * depends on `prompt`, and `prompt` depends on `api` — so `api` depending on `executor` in turn
 * would cycle. This interface is where the two meet: `api` only knows the shape of "run a
 * message," `daemon`'s `RuntimeClientFactory` (or any other composition root) implements it using
 * the real `executor` types it's free to depend on.
 *
 * Unset (`RealRuntimeClient.runExecutor == null`) preserves the pre-persistence in-memory-only
 * `RunSummary`/`RunResult.Accepted` stub, the same fallback pattern [KnowledgeService] and the
 * persistence seams (`userDriver`, `projectDbFactory`, `projectLocker`) already use.
 */
interface RunExecutor {
    /**
     * [projectDriver] is the project's own already-open `state.db` driver — the same one
     * `RealRuntimeClient` used to create/open the project, not one this call opens itself.
     *
     * Returns [RunResult.Accepted] with the real, persisted Run id once the `runs` row and its
     * first `Task(kind = MODEL_CALL)` exist — **not** once the Run has actually produced a model
     * response. Driving the Run to completion needs a real `InferenceRouter` + `PromptAssembler`
     * + `EffectBroker` (a `CapabilityManager` with real tools registered), which does not exist
     * anywhere in the runtime composition root yet; that is separate, larger follow-up work, not
     * this seam's job. A durable, un-driven `PENDING` Run is the honest, correct state to leave it
     * in — not a hollow "drive it with nothing registered" gesture.
     */
    suspend fun send(
        projectDriver: SqlDriver,
        projectId: String,
        sessionId: String,
        message: UserMessage,
        platformProfile: PlatformProfile,
        deviceId: String,
        networkAvailable: Boolean,
    ): RunResult
}
