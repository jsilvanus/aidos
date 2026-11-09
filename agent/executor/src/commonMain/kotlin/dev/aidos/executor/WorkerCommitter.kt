package dev.aidos.executor

import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId

/**
 * Seam in executor mirroring [RunReconciler] (interface in commonMain, JGit implementation
 * composed in daemon), called from resumeAwaitingParent, writing to refs/aidos/workers/<workerSessionId>.
 *
 * Scoped down deliberately: the filesystem and git tools are real-working-tree tools with no
 * treeless-aware variant, so a first cut commits an outcome summary, not real code diffs (RFC-0011, PIPELINE.md §4).
 */
interface WorkerCommitter {
    suspend fun commitWorkerOutcome(
        projectId: ProjectId,
        workerSessionId: SessionId,
        childRunId: RunId,
        outcomeSummary: String,
    )
}
