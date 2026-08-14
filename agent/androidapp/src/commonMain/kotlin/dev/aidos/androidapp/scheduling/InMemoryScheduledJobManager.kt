package dev.aidos.androidapp.scheduling

import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.ScheduledJobId
import dev.aidos.kernel.WorkClass
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.math.max

/**
 * In-memory implementation of ScheduledJobManager (RFC-0044, M32).
 *
 * Used for MVP testing and single-device scenarios. A production implementation would
 * use SQLDelight to persist to the scheduled_jobs table.
 *
 * Thread-safe via synchronized access.
 */
class InMemoryScheduledJobManager : ScheduledJobManager {
    private val jobs = mutableMapOf<String, ScheduledJob>()
    private val disabledNotified = mutableSetOf<String>()

    override suspend fun create(job: ScheduledJob): Result<ScheduledJob> = synchronized(jobs) {
        if (jobs.containsKey(job.id.value)) {
            return Result.failure(Exception("Job already exists: ${job.id.value}"))
        }
        jobs[job.id.value] = job
        Result.success(job)
    }

    override suspend fun update(
        jobId: ScheduledJobId,
        lastRunAt: Instant?,
        lastOutcome: JobOutcome,
        nextRunAt: Instant?,
    ): Result<ScheduledJob> = synchronized(jobs) {
        val existing = jobs[jobId.value]
            ?: return Result.failure(Exception("Job not found: ${jobId.value}"))

        val newFailures = when (lastOutcome) {
            JobOutcome.FAILED -> existing.consecutiveFailures + 1
            JobOutcome.COMPLETED,
            JobOutcome.CANCELLED,
            JobOutcome.SKIPPED -> 0  // Reset on success, cancellation, or skip
        }

        val shouldDisable = newFailures >= 3
        val updated = existing.copy(
            lastRunAt = lastRunAt ?: existing.lastRunAt,
            lastOutcome = lastOutcome.toString(),
            nextRunAt = nextRunAt ?: existing.nextRunAt,
            consecutiveFailures = newFailures,
            enabled = if (shouldDisable) false else existing.enabled,
        )

        // Track that we've notified about disable (one-time notification).
        if (shouldDisable && jobId.value !in disabledNotified) {
            disabledNotified.add(jobId.value)
        }

        jobs[jobId.value] = updated
        Result.success(updated)
    }

    override suspend fun cancel(jobId: ScheduledJobId): Result<Unit> = synchronized(jobs) {
        val job = jobs[jobId.value]
            ?: return Result.failure(Exception("Job not found: ${jobId.value}"))
        jobs[jobId.value] = job.copy(enabled = false)
        Result.success(Unit)
    }

    override suspend fun get(jobId: ScheduledJobId): Result<ScheduledJob?> = synchronized(jobs) {
        Result.success(jobs[jobId.value])
    }

    override suspend fun listByProject(projectId: String): Result<List<ScheduledJob>> = synchronized(jobs) {
        Result.success(jobs.values.filter { it.projectId == projectId })
    }

    override suspend fun listDue(nowIso: String): Result<List<ScheduledJob>> = synchronized(jobs) {
        val now = Instant.parse(nowIso)
        val due = jobs.values
            .filter { it.enabled && it.nextRunAt != null && (it.nextRunAt as Instant) <= now }
            .sortedBy { it.nextRunAt }
        Result.success(due)
    }

    override suspend fun recordMissedOccurrence(jobId: ScheduledJobId): Result<Unit> = synchronized(jobs) {
        val job = jobs[jobId.value]
            ?: return Result.failure(Exception("Job not found: ${jobId.value}"))
        jobs[jobId.value] = job.copy(missedOccurrences = job.missedOccurrences + 1)
        Result.success(Unit)
    }

    override suspend fun deleteDisabledBefore(beforeIso: String): Result<Int> = synchronized(jobs) {
        val before = Instant.parse(beforeIso)
        val toDelete = jobs.values
            .filter { !it.enabled && it.createdAt < before }
            .map { it.id.value }
        val count = toDelete.size
        toDelete.forEach { jobs.remove(it) }
        Result.success(count)
    }

    /** Clears all jobs (for testing). */
    fun clear() {
        synchronized(jobs) {
            jobs.clear()
            disabledNotified.clear()
        }
    }
}

/**
 * Dispatcher that routes scheduled jobs to appropriate execution mechanisms (RFC-0044, M32).
 *
 * Rules:
 * - INTERACTIVE: runs inline or under foreground service (user-facing, high latency budget)
 * - DEFERRED: runs under background constraints (WorkManager, no foreground service required)
 * - SCHEDULED: runs periodically under WorkManager (timers, recurring sessions)
 * - OPPORTUNISTIC: runs when device is idle+charging+unmetered
 */
interface WorkDispatcher {
    /**
     * Dispatch a job to its appropriate executor.
     *
     * @param job The scheduled job to dispatch
     * @return true if dispatched successfully, false if cannot dispatch (e.g., constraints not met)
     */
    suspend fun dispatch(job: ScheduledJob): Boolean
}

/**
 * Mock dispatcher for testing (routes all work to a callback).
 */
class MockWorkDispatcher(
    private val onDispatch: suspend (job: ScheduledJob) -> Unit = {},
) : WorkDispatcher {
    val dispatchedJobs = mutableListOf<ScheduledJob>()

    override suspend fun dispatch(job: ScheduledJob): Boolean {
        dispatchedJobs.add(job)
        onDispatch(job)
        return true
    }

    fun clear() {
        dispatchedJobs.clear()
    }
}

/**
 * Job scheduler that queries due jobs and dispatches them (RFC-0044, M32).
 *
 * Runs periodically (or on-demand) to:
 * 1. Find jobs due to run (enabled, next_run_at <= now)
 * 2. Evaluate constraints (DEFERRED: check network, charging; OPPORTUNISTIC: check idle)
 * 3. Dispatch to appropriate executor
 * 4. Update job state after execution
 */
class JobScheduler(
    private val jobManager: ScheduledJobManager,
    private val dispatcher: WorkDispatcher,
) {
    /**
     * Runs the scheduler: finds due jobs and dispatches them.
     *
     * @param nowIso current time as ISO string (defaults to now)
     * @return count of jobs dispatched
     */
    suspend fun runScheduleRound(nowIso: String = Clock.System.now().toString()): Int {
        val dueResult = jobManager.listDue(nowIso)
        if (dueResult.isFailure) return 0

        val due = dueResult.getOrNull() ?: return 0
        var count = 0

        for (job in due) {
            // Check constraints based on work class.
            if (!canDispatch(job)) {
                // Cannot dispatch now (e.g., OPPORTUNISTIC without charging).
                // Record missed and recompute next_run_at.
                jobManager.recordMissedOccurrence(job.id)
                continue
            }

            // Dispatch the job.
            val success = dispatcher.dispatch(job)
            if (success) count++
        }

        return count
    }

    /** Checks if a job can be dispatched now based on its work class and constraints. */
    private fun canDispatch(job: ScheduledJob): Boolean {
        return when (job.workClass) {
            WorkClass.INTERACTIVE -> true  // Always dispatchable (foreground service available)
            WorkClass.DEFERRED -> true      // WorkManager handles constraints
            WorkClass.SCHEDULED -> true     // WorkManager periodic handles constraints
            WorkClass.OPPORTUNISTIC -> {
                // Check constraints: charging, idle, unmetered.
                // For MVP, assume we can dispatch (real implementation checks device state).
                true
            }
        }
    }
}
