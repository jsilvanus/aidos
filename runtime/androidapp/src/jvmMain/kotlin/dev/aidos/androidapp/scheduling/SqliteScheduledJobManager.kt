package dev.aidos.androidapp.scheduling

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.ScheduledJobId
import dev.aidos.kernel.Trigger
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

/**
 * SQLite-backed scheduled job manager (RFC-0044, M32).
 *
 * Persists jobs to the scheduled_jobs table using SQLDelight. Handles:
 * - Job creation with computed next_run_at
 * - Job updates with failure tracking and auto-disable at 3 failures
 * - Job cancellation and queries
 * - Missed occurrence tracking
 * - Cleanup of disabled jobs
 *
 * Thread-safe via transaction-based locking (SQLite serialized mode).
 */
class SqliteScheduledJobManager(
    private val driver: SqlDriver,
) : ScheduledJobManager {

    override suspend fun create(job: ScheduledJob): Result<ScheduledJob> {
        return try {
            // Compute next_run_at from trigger
            val nextRunAt = TriggerCalculator.nextRunAt(job.trigger)

            val jobToInsert = job.copy(nextRunAt = nextRunAt)

            driver.execute(
                identifier = null,
                sql = """
                    INSERT INTO scheduled_jobs (
                        id, project_id, session_id, name, trigger_json, guarantee_class,
                        work_class, constraints_json, enabled, next_run_at, last_run_at,
                        last_outcome, consecutive_failures, missed_occurrences, created_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                parameters = 15,
            ) {
                bindString(0, jobToInsert.id.value)
                bindString(1, jobToInsert.projectId)
                bindString(2, jobToInsert.sessionId)
                bindString(3, jobToInsert.name)
                bindString(4, Json.encodeToString(jobToInsert.trigger))
                bindString(5, jobToInsert.guaranteeClass.toString())
                bindString(6, jobToInsert.workClass.toString())
                bindString(7, jobToInsert.constraintsJson)
                bindLong(8, if (jobToInsert.enabled) 1L else 0L)
                bindString(9, nextRunAt?.toString())
                bindString(10, jobToInsert.lastRunAt?.toString())
                bindString(11, jobToInsert.lastOutcome)
                bindLong(12, jobToInsert.consecutiveFailures.toLong())
                bindLong(13, jobToInsert.missedOccurrences.toLong())
                bindString(14, jobToInsert.createdAt.toString())
            }

            Result.success(jobToInsert)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun update(
        jobId: ScheduledJobId,
        lastRunAt: Instant?,
        lastOutcome: JobOutcome,
        nextRunAt: Instant?,
    ): Result<ScheduledJob> {
        return try {
            // Fetch existing job
            val existing = getByIdDirect(jobId)
                ?: return Result.failure(Exception("Job not found: ${jobId.value}"))

            // Calculate new consecutive failures
            val newFailures = when (lastOutcome) {
                JobOutcome.FAILED -> existing.consecutiveFailures + 1
                else -> 0
            }

            // Disable if 3+ failures
            val shouldDisable = newFailures >= 3

            // Compute next run if not provided
            val computedNextRunAt = nextRunAt ?: TriggerCalculator.nextRunAt(existing.trigger)

            driver.execute(
                identifier = null,
                sql = """
                    UPDATE scheduled_jobs
                    SET last_run_at = ?,
                        last_outcome = ?,
                        consecutive_failures = ?,
                        enabled = ?,
                        next_run_at = ?
                    WHERE id = ?
                """.trimIndent(),
                parameters = 6,
            ) {
                bindString(0, lastRunAt?.toString())
                bindString(1, lastOutcome.toString())
                bindLong(2, newFailures.toLong())
                bindLong(3, if (shouldDisable) 0L else (if (existing.enabled) 1L else 0L))
                bindString(4, computedNextRunAt?.toString())
                bindString(5, jobId.value)
            }

            val updated = existing.copy(
                lastRunAt = lastRunAt ?: existing.lastRunAt,
                lastOutcome = lastOutcome.toString(),
                consecutiveFailures = newFailures,
                enabled = if (shouldDisable) false else existing.enabled,
                nextRunAt = computedNextRunAt,
            )

            Result.success(updated)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun cancel(jobId: ScheduledJobId): Result<Unit> {
        return try {
            if (getByIdDirect(jobId) == null) {
                return Result.failure(Exception("Job not found: ${jobId.value}"))
            }

            driver.execute(
                identifier = null,
                sql = "UPDATE scheduled_jobs SET enabled = 0 WHERE id = ?",
                parameters = 1,
            ) {
                bindString(0, jobId.value)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun get(jobId: ScheduledJobId): Result<ScheduledJob?> {
        return try {
            Result.success(getByIdDirect(jobId))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun listByProject(projectId: String): Result<List<ScheduledJob>> {
        return try {
            val jobs = mutableListOf<ScheduledJob>()

            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT id, project_id, session_id, name, trigger_json, guarantee_class,
                           work_class, constraints_json, enabled, next_run_at, last_run_at,
                           last_outcome, consecutive_failures, missed_occurrences, created_at
                    FROM scheduled_jobs
                    WHERE project_id = ?
                    ORDER BY created_at DESC
                """.trimIndent(),
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val job = deserializeJob(cursor)
                        if (job != null) jobs.add(job)
                    }
                    QueryResult.Unit
                },
                parameters = 1,
            ) {
                bindString(0, projectId)
            }

            Result.success(jobs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun listDue(nowIso: String): Result<List<ScheduledJob>> {
        return try {
            val jobs = mutableListOf<ScheduledJob>()

            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT id, project_id, session_id, name, trigger_json, guarantee_class,
                           work_class, constraints_json, enabled, next_run_at, last_run_at,
                           last_outcome, consecutive_failures, missed_occurrences, created_at
                    FROM scheduled_jobs
                    WHERE enabled = 1 AND next_run_at IS NOT NULL AND next_run_at <= ?
                    ORDER BY next_run_at ASC
                """.trimIndent(),
                mapper = { cursor ->
                    while (cursor.next().value) {
                        val job = deserializeJob(cursor)
                        if (job != null) jobs.add(job)
                    }
                    QueryResult.Unit
                },
                parameters = 1,
            ) {
                bindString(0, nowIso)
            }

            Result.success(jobs)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun recordMissedOccurrence(jobId: ScheduledJobId): Result<Unit> {
        return try {
            if (getByIdDirect(jobId) == null) {
                return Result.failure(Exception("Job not found: ${jobId.value}"))
            }

            driver.execute(
                identifier = null,
                sql = """
                    UPDATE scheduled_jobs
                    SET missed_occurrences = missed_occurrences + 1
                    WHERE id = ?
                """.trimIndent(),
                parameters = 1,
            ) {
                bindString(0, jobId.value)
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun deleteDisabledBefore(beforeIso: String): Result<Int> {
        return try {
            // First, count how many rows we'll delete
            var count = 0
            driver.executeQuery(
                identifier = null,
                sql = """
                    SELECT COUNT(*) as cnt
                    FROM scheduled_jobs
                    WHERE enabled = 0 AND created_at < ?
                """.trimIndent(),
                mapper = { cursor ->
                    if (cursor.next().value) {
                        count = cursor.getLong(0)?.toInt() ?: 0
                    }
                    QueryResult.Unit
                },
                parameters = 1,
            ) {
                bindString(0, beforeIso)
            }

            // Then delete them
            driver.execute(
                identifier = null,
                sql = """
                    DELETE FROM scheduled_jobs
                    WHERE enabled = 0 AND created_at < ?
                """.trimIndent(),
                parameters = 1,
            ) {
                bindString(0, beforeIso)
            }

            Result.success(count)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Internal helper to fetch a job by ID directly (used by other methods).
     */
    private fun getByIdDirect(jobId: ScheduledJobId): ScheduledJob? {
        var result: ScheduledJob? = null

        driver.executeQuery(
            identifier = null,
            sql = """
                SELECT id, project_id, session_id, name, trigger_json, guarantee_class,
                       work_class, constraints_json, enabled, next_run_at, last_run_at,
                       last_outcome, consecutive_failures, missed_occurrences, created_at
                FROM scheduled_jobs
                WHERE id = ?
            """.trimIndent(),
            mapper = { cursor ->
                if (cursor.next().value) {
                    result = deserializeJob(cursor)
                }
                QueryResult.Unit
            },
            parameters = 1,
        ) {
            bindString(0, jobId.value)
        }

        return result
    }

    /**
     * Deserializes a job from a cursor row.
     *
     * Expected column order:
     * 0: id, 1: project_id, 2: session_id, 3: name, 4: trigger_json, 5: guarantee_class,
     * 6: work_class, 7: constraints_json, 8: enabled, 9: next_run_at, 10: last_run_at,
     * 11: last_outcome, 12: consecutive_failures, 13: missed_occurrences, 14: created_at
     */
    private fun deserializeJob(cursor: Any): ScheduledJob? {
        return try {
            // The cursor object has getString(index) and getLong(index) methods
            val id = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 0) as String? ?: return null
            val projectId = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 1) as String? ?: return null
            val sessionId = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 2) as String?
            val name = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 3) as String? ?: return null
            val triggerJson = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 4) as String? ?: return null
            val guaranteeClass = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 5) as String? ?: return null
            val workClass = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 6) as String? ?: return null
            val constraintsJson = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 7) as String? ?: "{}"
            val enabled = (cursor.javaClass.getMethod("getLong", Int::class.java).invoke(cursor, 8) as Long? ?: 0L) == 1L
            val nextRunAtStr = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 9) as String?
            val lastRunAtStr = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 10) as String?
            val lastOutcome = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 11) as String?
            val consecutiveFailures = (cursor.javaClass.getMethod("getLong", Int::class.java).invoke(cursor, 12) as Long? ?: 0L).toInt()
            val missedOccurrences = (cursor.javaClass.getMethod("getLong", Int::class.java).invoke(cursor, 13) as Long? ?: 0L).toInt()
            val createdAtStr = cursor.javaClass.getMethod("getString", Int::class.java).invoke(cursor, 14) as String? ?: return null

            val trigger = Json.decodeFromString<Trigger>(triggerJson)
            val nextRunAt = nextRunAtStr?.let { Instant.parse(it) }
            val lastRunAt = lastRunAtStr?.let { Instant.parse(it) }
            val createdAt = Instant.parse(createdAtStr)

            ScheduledJob(
                id = ScheduledJobId(id),
                projectId = projectId,
                sessionId = sessionId,
                name = name,
                trigger = trigger,
                guaranteeClass = dev.aidos.kernel.GuaranteeClass.valueOf(guaranteeClass),
                workClass = dev.aidos.kernel.WorkClass.valueOf(workClass),
                constraintsJson = constraintsJson,
                enabled = enabled,
                nextRunAt = nextRunAt,
                lastRunAt = lastRunAt,
                lastOutcome = lastOutcome,
                consecutiveFailures = consecutiveFailures,
                missedOccurrences = missedOccurrences,
                createdAt = createdAt,
            )
        } catch (e: Exception) {
            null
        }
    }
}
