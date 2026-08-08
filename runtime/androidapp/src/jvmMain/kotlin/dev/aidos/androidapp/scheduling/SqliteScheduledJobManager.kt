package dev.aidos.androidapp.scheduling

import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.ScheduledJobId
import kotlinx.datetime.Instant

/**
 * SQLite-backed scheduled job manager (RFC-0044, M32).
 *
 * This is currently delegating to InMemoryScheduledJobManager to ensure compilation.
 * A full SQLDelight integration will be implemented once the build system is fully configured.
 *
 * TODO: Implement true SQLDelight persistence when schema generation is ready.
 * The scheduled_jobs table is defined in schema/project.sql and ready for queries.
 */
class SqliteScheduledJobManager(
    // TODO: Receive SqlDriver parameter when SQLDelight is properly wired
    // private val driver: SqlDriver,
) : ScheduledJobManager {

    // For now, delegate to in-memory manager to unblock compilation
    private val delegate = InMemoryScheduledJobManager()

    override suspend fun create(job: ScheduledJob): Result<ScheduledJob> {
        return delegate.create(job)
    }

    override suspend fun update(
        jobId: ScheduledJobId,
        lastRunAt: Instant?,
        lastOutcome: JobOutcome,
        nextRunAt: Instant?,
    ): Result<ScheduledJob> {
        return delegate.update(jobId, lastRunAt, lastOutcome, nextRunAt)
    }

    override suspend fun cancel(jobId: ScheduledJobId): Result<Unit> {
        return delegate.cancel(jobId)
    }

    override suspend fun get(jobId: ScheduledJobId): Result<ScheduledJob?> {
        return delegate.get(jobId)
    }

    override suspend fun listByProject(projectId: String): Result<List<ScheduledJob>> {
        return delegate.listByProject(projectId)
    }

    override suspend fun listDue(nowIso: String): Result<List<ScheduledJob>> {
        return delegate.listDue(nowIso)
    }

    override suspend fun recordMissedOccurrence(jobId: ScheduledJobId): Result<Unit> {
        return delegate.recordMissedOccurrence(jobId)
    }

    override suspend fun deleteDisabledBefore(beforeIso: String): Result<Int> {
        return delegate.deleteDisabledBefore(beforeIso)
    }
}
