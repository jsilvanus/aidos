package dev.aidos.androidapp.scheduling

import dev.aidos.api.RuntimeClient
import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.WorkClass

/**
 * Concrete work dispatcher that executes scheduled jobs via RuntimeClient (RFC-0044, M32).
 *
 * Routes different work classes to appropriate execution paths:
 * - INTERACTIVE: invoke session immediately (foreground service)
 * - DEFERRED: queue for background execution
 * - SCHEDULED: already managed by WorkManager; just invoke
 * - OPPORTUNISTIC: queue with charging/idle constraints
 */
class RuntimeClientWorkDispatcher(
    private val client: RuntimeClient,
    private val jobManager: ScheduledJobManager,
) : WorkDispatcher {

    override suspend fun dispatch(job: ScheduledJob): Boolean {
        // Only jobs with sessions can be dispatched (for MVP).
        val sessionId = job.sessionId ?: return false

        return when (job.workClass) {
            WorkClass.INTERACTIVE -> {
                // INTERACTIVE: invoke immediately in foreground service.
                dispatchInteractive(job, sessionId)
            }

            WorkClass.DEFERRED -> {
                // DEFERRED: queue for background execution.
                // For MVP, we treat this the same as INTERACTIVE (actual background via WorkManager later).
                dispatchDeferred(job, sessionId)
            }

            WorkClass.SCHEDULED -> {
                // SCHEDULED: WorkManager already scheduled it; just invoke.
                dispatchScheduled(job, sessionId)
            }

            WorkClass.OPPORTUNISTIC -> {
                // OPPORTUNISTIC: queue with constraints (charging, idle, unmetered).
                // For MVP, we treat this like DEFERRED.
                dispatchOpportunistic(job, sessionId)
            }
        }
    }

    private suspend fun dispatchInteractive(job: ScheduledJob, sessionId: String): Boolean {
        return try {
            // Send a message to the session to trigger execution.
            val result = client.send(sessionId, dev.aidos.api.UserMessage(
                content = "Scheduled job: ${job.name}",
                // In real implementation, include trigger info (missedOccurrences, etc.)
            ))
            // Update job with completion status.
            jobManager.update(job.id, lastOutcome = JobOutcome.COMPLETED)
            result.run is dev.aidos.kernel.Run  // Check if execution succeeded
        } catch (e: Exception) {
            jobManager.update(job.id, lastOutcome = JobOutcome.FAILED)
            false
        }
    }

    private suspend fun dispatchDeferred(job: ScheduledJob, sessionId: String): Boolean {
        return try {
            // For MVP, same as interactive. In production, queue to WorkManager background.
            val result = client.send(sessionId, dev.aidos.api.UserMessage(
                content = "Deferred job: ${job.name}",
            ))
            jobManager.update(job.id, lastOutcome = JobOutcome.COMPLETED)
            result.run is dev.aidos.kernel.Run
        } catch (e: Exception) {
            jobManager.update(job.id, lastOutcome = JobOutcome.FAILED)
            false
        }
    }

    private suspend fun dispatchScheduled(job: ScheduledJob, sessionId: String): Boolean {
        return try {
            // Invoke the scheduled session.
            val result = client.send(sessionId, dev.aidos.api.UserMessage(
                content = "Scheduled session: ${job.name}",
            ))
            jobManager.update(job.id, lastOutcome = JobOutcome.COMPLETED)
            result.run is dev.aidos.kernel.Run
        } catch (e: Exception) {
            jobManager.update(job.id, lastOutcome = JobOutcome.FAILED)
            false
        }
    }

    private suspend fun dispatchOpportunistic(job: ScheduledJob, sessionId: String): Boolean {
        return try {
            // For MVP, same as deferred. In production, check constraints and queue.
            val result = client.send(sessionId, dev.aidos.api.UserMessage(
                content = "Opportunistic job: ${job.name}",
            ))
            jobManager.update(job.id, lastOutcome = JobOutcome.COMPLETED)
            result.run is dev.aidos.kernel.Run
        } catch (e: Exception) {
            jobManager.update(job.id, lastOutcome = JobOutcome.FAILED)
            false
        }
    }
}
