package dev.aidos.api

/**
 * Per-project advisory locking (RFC-0055): a project is owned by at most one runtime instance
 * at a time. Opening a project already held by another live instance is refused, not queued.
 *
 * The concrete implementation (`dev.aidos.lock.ProjectLock`, wrapping
 * `java.nio.channels.FileLock`) lives outside `:api`'s `commonMain` and is injected into
 * [RealRuntimeClient] instead, the same way [KnowledgeService] is. This is a design choice, not
 * a compilation necessity — a `jvm()`+`androidTarget()`-only module's `commonMain` can reference
 * `java.*` APIs both targets share (`identity`'s `ProjectRegistry` already does, with
 * `java.io.File`, and compiles for Android in CI). The seam exists because `FileLock`'s actual
 * *behavior* on Android's storage volumes is unverified from this sandbox (no device, no CI
 * step that exercises real file locking) — injecting the concrete implementation means Android
 * gets its own, deliberately, once someone can verify it, rather than inheriting untested
 * assumptions. Wired by external infrastructure (`JvmProjectLocker` in `:api`'s `jvmMain`, for
 * now); Android's own implementation is follow-up work, same status as capability's
 * `SqliteDirHandle` (RFC-0050's deferred SAF/scoped-storage design).
 */
interface ProjectLocker {
    /**
     * Attempts to acquire the lock for [projectId] at [projectRootPath]. [instanceId]
     * identifies this runtime instance in the lock file's metadata (RFC-0055).
     */
    fun tryAcquire(projectId: String, projectRootPath: String, instanceId: String): ProjectLockOutcome

    /** Releases the lock for [projectId], if this instance holds it. No-op otherwise. */
    fun release(projectId: String)
}

/** Result of [ProjectLocker.tryAcquire] (RFC-0055). */
sealed interface ProjectLockOutcome {
    /** Lock acquired cleanly. */
    data object Acquired : ProjectLockOutcome

    /**
     * Lock acquired after breaking a stale lock (heartbeat older than RFC-0055's threshold) from
     * a crashed or unresponsive instance. RFC-0055: "locks are never broken silently" — the
     * caller must surface this, not just treat it as [Acquired].
     */
    data class AcquiredAfterBreakingStale(
        val previousInstanceId: String,
        val previousHeartbeatAt: String,
    ) : ProjectLockOutcome

    /** Another live instance holds the lock. Maps to `runtime.locked_by_other_instance`. */
    data class HeldByOther(
        val instanceId: String,
        val acquiredAt: String,
        val heartbeatAt: String,
    ) : ProjectLockOutcome
}
