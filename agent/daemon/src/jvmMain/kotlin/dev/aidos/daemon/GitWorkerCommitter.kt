package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.executor.WorkerCommitter
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.kernel.SessionId
import dev.aidos.worker.FileChange
import dev.aidos.worker.TreelessWorker
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

/**
 * JVM composition of RFC-0011 / RFC-0053 treeless worker commit seam.
 *
 * Called from [dev.aidos.executor.SqliteExecutor.resumeAwaitingParent].
 * Writes an outcome summary directly to `refs/aidos/workers/<workerSessionId>` without a worktree.
 */
class GitWorkerCommitter(
    private val driver: SqlDriver,
    private val authorName: String = "Aidos Worker",
    private val authorEmail: String = "worker@aidos.local",
) : WorkerCommitter {

    override suspend fun commitWorkerOutcome(
        projectId: ProjectId,
        workerSessionId: SessionId,
        childRunId: RunId,
        outcomeSummary: String,
    ) {
        val rootPath = projectRootPath(driver, projectId.value) ?: return
        val gitDir = File(rootPath, ".git")
        if (!gitDir.exists()) return

        val repo = FileRepositoryBuilder().setGitDir(gitDir).build()
        try {
            val worker = TreelessWorker(repo)
            worker.commit(
                workerId = workerSessionId.value,
                changes = listOf(
                    FileChange.Write(
                        path = ".aidos/workers/${workerSessionId.value}/outcome.md",
                        content = outcomeSummary.encodeToByteArray(),
                    ),
                ),
                authorName = authorName,
                authorEmail = authorEmail,
                message = "Worker ${workerSessionId.value} outcome for run ${childRunId.value}",
            )
        } finally {
            repo.close()
        }
    }

    private fun projectRootPath(driver: SqlDriver, projectId: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT root_path FROM projects WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, projectId) }.value
}
