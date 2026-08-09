package dev.aidos.androidapp.scheduling

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.ScheduledJobId
import dev.aidos.kernel.GuaranteeClass
import dev.aidos.kernel.WorkClass
import dev.aidos.kernel.Trigger
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * SQLite-backed scheduled job manager (RFC-0044, M32).
 *
 * Persists scheduled jobs to the scheduled_jobs table using SQLDelight.
 * Supports all CRUD operations and job lifecycle management.
 */
class SqliteScheduledJobManager(
    private val driver: SqlDriver,
) : ScheduledJobManager {

    private val database by lazy { ScheduledJobsDb(driver) }
    private val queries by lazy { database.scheduledJobsQueries }

    override suspend fun create(job: ScheduledJob): Result<ScheduledJob> = try {
        // Check if job already exists
        val existing = queries.getScheduledJob(job.id.value).executeAsOneOrNull()
        if (existing != null) {
            return Result.failure(Exception("Job already exists: ${job.id.value}"))
        }

        // Insert new job
        queries.insertScheduledJob(
            id = job.id.value,
            project_id = job.projectId,
            session_id = job.sessionId,
            name = job.name,
            trigger_json = Json.encodeToString(job.trigger),
            guarantee_class = job.guaranteeClass.toString(),
            work_class = job.workClass.toString(),
            constraints_json = job.constraintsJson,
            enabled = if (job.enabled) 1L else 0L,
            next_run_at = job.nextRunAt?.toString(),
            last_run_at = job.lastRunAt?.toString(),
            last_outcome = job.lastOutcome,
            consecutive_failures = job.consecutiveFailures.toLong(),
            missed_occurrences = job.missedOccurrences.toLong(),
            created_at = job.createdAt.toString()
        )

        Result.success(job)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun update(
        jobId: ScheduledJobId,
        lastRunAt: Instant?,
        lastOutcome: JobOutcome,
        nextRunAt: Instant?,
    ): Result<ScheduledJob> = try {
        // Get existing job
        val existing = queries.getScheduledJob(jobId.value).executeAsOneOrNull()
            ?: return Result.failure(Exception("Job not found: ${jobId.value}"))

        // Reconstruct ScheduledJob from database row
        val job = deserializeScheduledJobRow(existing)

        // Calculate new failure count
        val newFailures = when (lastOutcome) {
            JobOutcome.FAILED -> job.consecutiveFailures + 1
            JobOutcome.COMPLETED,
            JobOutcome.CANCELLED,
            JobOutcome.SKIPPED -> 0
        }

        // Disable if 3 consecutive failures
        val shouldDisable = newFailures >= 3

        // Update in database
        queries.updateJobOutcome(
            last_run_at = lastRunAt?.toString() ?: existing.last_run_at,
            last_outcome = lastOutcome.toString(),
            next_run_at = nextRunAt?.toString() ?: existing.next_run_at,
            consecutive_failures = newFailures.toLong(),
            enabled = if (shouldDisable) 0L else existing.enabled,
            id = jobId.value
        )

        // Retrieve updated job
        val updated = queries.getScheduledJob(jobId.value).executeAsOne()
        val updatedJob = deserializeScheduledJobRow(updated)

        Result.success(updatedJob)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun cancel(jobId: ScheduledJobId): Result<Unit> = try {
        queries.cancelJob(jobId.value)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun get(jobId: ScheduledJobId): Result<ScheduledJob?> = try {
        val row = queries.getScheduledJob(jobId.value).executeAsOneOrNull()
        if (row == null) {
            Result.success(null)
        } else {
            Result.success(deserializeScheduledJobRow(row))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun listByProject(projectId: String): Result<List<ScheduledJob>> = try {
        val rows = queries.listByProject(projectId).executeAsList()
        Result.success(rows.map { deserializeScheduledJobRow(it) })
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun listDue(nowIso: String): Result<List<ScheduledJob>> = try {
        val rows = queries.listDue(nowIso).executeAsList()
        Result.success(rows.map { deserializeScheduledJobRow(it) })
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun recordMissedOccurrence(jobId: ScheduledJobId): Result<Unit> = try {
        queries.recordMissedOccurrence(jobId.value)
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }

    override suspend fun deleteDisabledBefore(beforeIso: String): Result<Int> = try {
        // Get count before deleting
        val countResult = queries.countDeletedBefore(beforeIso).executeAsOne()
        val count = countResult.count.toInt()

        // Delete
        queries.deleteDisabledBefore(beforeIso)

        Result.success(count)
    } catch (e: Exception) {
        Result.failure(e)
    }

    private fun deserializeScheduledJobRow(row: ScheduledJobs): ScheduledJob {
        return ScheduledJob(
            id = ScheduledJobId(row.id),
            projectId = row.project_id,
            sessionId = row.session_id,
            name = row.name,
            trigger = Json.decodeFromString(row.trigger_json),
            guaranteeClass = GuaranteeClass.valueOf(row.guarantee_class),
            workClass = WorkClass.valueOf(row.work_class),
            constraintsJson = row.constraints_json,
            enabled = row.enabled == 1L,
            nextRunAt = row.next_run_at?.let { Instant.parse(it) },
            lastRunAt = row.last_run_at?.let { Instant.parse(it) },
            lastOutcome = row.last_outcome,
            consecutiveFailures = row.consecutive_failures.toInt(),
            missedOccurrences = row.missed_occurrences.toInt(),
            createdAt = Instant.parse(row.created_at),
        )
    }
}


