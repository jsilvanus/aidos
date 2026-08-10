package dev.aidos.executor

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId

/**
 * The RFC-0053 "before any Run may start" gate, as a seam [SqliteExecutor] calls without
 * depending on JGit or SQL details itself (`executor`'s `commonMain` is portable; JGit is
 * JVM-only). The real implementation — fingerprint comparison, content-node invalidation, the
 * `reconciliations` audit row — is composed on the JVM side (`daemon`'s `GitRunReconciler`) and
 * injected here, the same seam idiom `RealRuntimeClient` already uses for
 * `projectDbFactory`/`runExecutor`.
 */
interface RunReconciler {
    /**
     * Called immediately before a Run transitions `PENDING`/`INTERRUPTED` → `RUNNING`
     * (RFC-0053: "Reconciliation runs before any Run may start on a repository with a
     * mismatched fingerprint").
     *
     * Returns the ids of every Run on [projectId] that must be terminated with
     * `FAILED(repo.mutated)` as a result of this check — which may or may not include [runId]
     * itself; a fingerprint mismatch is a project-wide fact, not specific to the Run that
     * happened to trigger the check, so other parked Runs on the same project are swept up by
     * the same reconciliation (RFC-0053: "If a Run is parked and the repository moved
     * underneath it, the Run is terminated... rather than resumed"). An empty set means no
     * mismatch was found — [runId] may proceed.
     */
    suspend fun reconcileBeforeRun(driver: SqlDriver, projectId: ProjectId, runId: RunId): Set<RunId>
}
