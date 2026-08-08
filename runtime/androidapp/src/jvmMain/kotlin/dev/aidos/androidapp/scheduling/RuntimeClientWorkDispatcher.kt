package dev.aidos.androidapp.scheduling

import dev.aidos.api.RuntimeClient
import dev.aidos.api.UserMessage
import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.Trigger
import dev.aidos.kernel.WorkClass
import kotlinx.datetime.Clock

/**
 * Concrete work dispatcher that executes scheduled jobs via RuntimeClient (RFC-0044, M32).
 *
 * Routes different work classes to appropriate execution paths:
 * - INTERACTIVE: invoke session immediately (foreground service)
 * - DEFERRED: queue for background execution
 * - SCHEDULED: already managed by WorkManager; just invoke
 * - OPPORTUNISTIC: queue with constraints (charging, idle, unmetered)
 *
 * Each dispatch includes trigger context (missedOccurrences, trigger type) in the session message.
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
            // Build the message with trigger context.
            val message = buildMessage(job)
            
            // Send the message to the session.
            val result = client.sessions.send(sessionId, message)
            
            // Update job with completion status.
            val nextRunAt = computeNextRunAt(job)
            jobManager.update(job.id, lastRunAt = Clock.System.now(), lastOutcome = JobOutcome.COMPLETED, nextRunAt = nextRunAt)
            
            result.run != null  // Check if execution succeeded
        } catch (e: Exception) {
            jobManager.update(job.id, lastOutcome = JobOutcome.FAILED)
            false
        }
    }

    private suspend fun dispatchDeferred(job: ScheduledJob, sessionId: String): Boolean {
        return try {
            // For MVP, same as interactive. In production, queue to WorkManager background.
            val message = buildMessage(job)
            val result = client.sessions.send(sessionId, message)
            val nextRunAt = computeNextRunAt(job)
            jobManager.update(job.id, lastRunAt = Clock.System.now(), lastOutcome = JobOutcome.COMPLETED, nextRunAt = nextRunAt)
            result.run != null
        } catch (e: Exception) {
            jobManager.update(job.id, lastOutcome = JobOutcome.FAILED)
            false
        }
    }

    private suspend fun dispatchScheduled(job: ScheduledJob, sessionId: String): Boolean {
        return try {
            // Invoke the scheduled session.
            val message = buildMessage(job)
            val result = client.sessions.send(sessionId, message)
            val nextRunAt = computeNextRunAt(job)
            jobManager.update(job.id, lastRunAt = Clock.System.now(), lastOutcome = JobOutcome.COMPLETED, nextRunAt = nextRunAt)
            result.run != null
        } catch (e: Exception) {
            jobManager.update(job.id, lastOutcome = JobOutcome.FAILED)
            false
        }
    }

    private suspend fun dispatchOpportunistic(job: ScheduledJob, sessionId: String): Boolean {
        return try {
            // For MVP, same as deferred. In production, check constraints and queue.
            val message = buildMessage(job)
            val result = client.sessions.send(sessionId, message)
            val nextRunAt = computeNextRunAt(job)
            jobManager.update(job.id, lastRunAt = Clock.System.now(), lastOutcome = JobOutcome.COMPLETED, nextRunAt = nextRunAt)
            result.run != null
        } catch (e: Exception) {
            jobManager.update(job.id, lastOutcome = JobOutcome.FAILED)
            false
        }
    }

    /**
     * Builds a user message with trigger context.
     *
     * The message includes:
     * - Job name and description
     * - Trigger type (At, Every, OnEvent, Cron, OnCondition)
     * - Missed occurrences (if periodic trigger)
     * - Work class
     * - Guarantee class
     */
    private fun buildMessage(job: ScheduledJob): UserMessage {
        val triggerInfo = when (val trigger = job.trigger) {
            is Trigger.At -> "Scheduled job at ${trigger.instant}"
            is Trigger.Every -> "Recurring job every ${trigger.interval}"
            is Trigger.OnEvent -> "Event-triggered job on ${trigger.filter.eventType}"
            is Trigger.Cron -> "Cron job: ${trigger.expression}"
            is Trigger.OnCondition -> "Condition-triggered job"
        }
        
        val missedInfo = if (job.missedOccurrences > 0) {
            " (missed ${job.missedOccurrences} occurrence${if (job.missedOccurrences > 1) "s" else ""})"
        } else {
            ""
        }

        return UserMessage(
            content = "${job.name}\n$triggerInfo$missedInfo",
        )
    }

    /**
     * Computes the next run time for a trigger.
     *
     * For now, this is delegated to TriggerCalculator. In a full implementation,
     * this would be part of the job manager's state.
     */
    private fun computeNextRunAt(job: ScheduledJob): String? {
        val next = TriggerCalculator.nextRunAt(job.trigger, job.lastRunAt)
        return next?.toString()
    }
}
