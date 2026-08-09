package dev.aidos.androidapp.scheduling

import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.ScheduledJobId
import kotlinx.datetime.Instant

/**
 * Job execution status outcomes (RFC-0044, M32).
 */
enum class JobOutcome {
    COMPLETED,       // Ran successfully
    FAILED,          // Raised an error (tracked for three-strike disable)
    CANCELLED,       // Cancelled by user or admin
    SKIPPED,         // Missed (coalesced, never replayed)
}

/**
 * Scheduled job manager interface (RFC-0044, M32).
 *
 * Abstracts the data access and scheduling logic for scheduled jobs. Implementations
 * may use WorkManager (Android), a background queue (desktop), or a mock for testing.
 *
 * Rules:
 * - Jobs are durable across restarts (persisted to scheduled_jobs table).
 * - Three consecutive failures disable the job.
 * - Missed occurrences are coalesced (missedOccurrences counter, never replayed).
 * - Cancellation by ID is durable.
 * - A scheduled job runs under the session's existing capabilities (RFC-0018).
 */
interface ScheduledJobManager {
    /**
     * Creates a new scheduled job. Returns the job with computed next_run_at.
     *
     * next_run_at is calculated from the trigger:
     * - At: the specified instant
     * - Every: anchor + N * interval (where N is the first future occurrence)
     * - OnEvent: null (event-driven, not timer-driven)
     */
    suspend fun create(job: ScheduledJob): Result<ScheduledJob>

    /**
     * Updates an existing job. Used to record execution outcomes.
     *
     * If lastOutcome is FAILED, consecutiveFailures is incremented.
     * If consecutiveFailures >= 3, enabled is set to false and a notification fires once.
     *
     * If the trigger fires next, nextRunAt is recomputed. Otherwise, nextRunAt remains
     * the previously computed value.
     */
    suspend fun update(
        jobId: ScheduledJobId,
        lastRunAt: Instant? = null,
        lastOutcome: JobOutcome,
        nextRunAt: Instant? = null,
    ): Result<ScheduledJob>

    /** Cancels a job by ID. Durable across restarts. Cancels any in-flight Run cooperatively. */
    suspend fun cancel(jobId: ScheduledJobId): Result<Unit>

    /** Reads a job by ID. */
    suspend fun get(jobId: ScheduledJobId): Result<ScheduledJob?>

    /** Lists all jobs for a project (enabled and disabled). */
    suspend fun listByProject(projectId: String): Result<List<ScheduledJob>>

    /** Lists jobs due to run (enabled only, next_run_at <= now). Ordered by next_run_at. */
    suspend fun listDue(nowIso: String): Result<List<ScheduledJob>>

    /**
     * Records a missed occurrence (e.g., device was off).
     *
     * Increments missedOccurrences counter. When the trigger fires next, the Run is given
     * access to missedOccurrences via the trigger Event, but the session does not replay
     * missed executions (RFC-0044 / RFC-0028).
     */
    suspend fun recordMissedOccurrence(jobId: ScheduledJobId): Result<Unit>

    /** Deletes all disabled jobs older than [beforeIso] (cleanup). */
    suspend fun deleteDisabledBefore(beforeIso: String): Result<Int>
}
