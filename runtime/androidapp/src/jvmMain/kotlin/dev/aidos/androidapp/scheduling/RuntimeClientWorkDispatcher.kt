package dev.aidos.androidapp.scheduling

import dev.aidos.api.RuntimeClient
import dev.aidos.api.RunResult
import dev.aidos.api.UserMessage
import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.Trigger
import dev.aidos.kernel.WorkClass
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Concrete work dispatcher that executes scheduled jobs via RuntimeClient (RFC-0044, M32).
 *
 * Routes different work classes to appropriate execution paths:
 * - INTERACTIVE: invoke session immediately (foreground service)
 * - DEFERRED: queue for background execution
 * - SCHEDULED: periodic execution via WorkManager
 * - OPPORTUNISTIC: execute when device conditions allow
 *
 * For MVP, INTERACTIVE and DEFERRED use the same path (immediate execution).
 * SCHEDULED and OPPORTUNISTIC are placeholders for future WorkManager integration.
 */
class RuntimeClientWorkDispatcher(
    private val client: RuntimeClient,
    private val jobManager: ScheduledJobManager,
) : WorkDispatcher {

    override suspend fun dispatch(job: ScheduledJob): Boolean {
        if (!job.enabled) return false

        val sessionId = job.sessionId ?: return false

        return when (job.workClass) {
            WorkClass.INTERACTIVE -> dispatchInteractive(job, sessionId)
            WorkClass.DEFERRED -> dispatchDeferred(job, sessionId)
            WorkClass.SCHEDULED -> dispatchScheduled(job, sessionId)
            WorkClass.OPPORTUNISTIC -> dispatchOpportunistic(job, sessionId)
        }
    }

    private suspend fun dispatchInteractive(job: ScheduledJob, sessionId: String): Boolean {
        return try {
            val message = buildMessage(job)
            val result = client.sessions.send(sessionId, message)
            val nextRunAt = computeNextRunAt(job)
            jobManager.update(job.id, lastRunAt = Clock.System.now(), lastOutcome = JobOutcome.COMPLETED, nextRunAt = nextRunAt)
            result is RunResult.Accepted
        } catch (e: Exception) {
            jobManager.update(job.id, lastOutcome = JobOutcome.FAILED)
            false
        }
    }

    private suspend fun dispatchDeferred(job: ScheduledJob, sessionId: String): Boolean {
        return try {
            val message = buildMessage(job)
            val result = client.sessions.send(sessionId, message)
            val nextRunAt = computeNextRunAt(job)
            jobManager.update(job.id, lastRunAt = Clock.System.now(), lastOutcome = JobOutcome.COMPLETED, nextRunAt = nextRunAt)
            result is RunResult.Accepted
        } catch (e: Exception) {
            jobManager.update(job.id, lastOutcome = JobOutcome.FAILED)
            false
        }
    }

    private suspend fun dispatchScheduled(job: ScheduledJob, sessionId: String): Boolean {
        return try {
            val message = buildMessage(job)
            val result = client.sessions.send(sessionId, message)
            val nextRunAt = computeNextRunAt(job)
            jobManager.update(job.id, lastRunAt = Clock.System.now(), lastOutcome = JobOutcome.COMPLETED, nextRunAt = nextRunAt)
            result is RunResult.Accepted
        } catch (e: Exception) {
            jobManager.update(job.id, lastOutcome = JobOutcome.FAILED)
            false
        }
    }

    private suspend fun dispatchOpportunistic(job: ScheduledJob, sessionId: String): Boolean {
        return try {
            val message = buildMessage(job)
            val result = client.sessions.send(sessionId, message)
            val nextRunAt = computeNextRunAt(job)
            jobManager.update(job.id, lastRunAt = Clock.System.now(), lastOutcome = JobOutcome.COMPLETED, nextRunAt = nextRunAt)
            result is RunResult.Accepted
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
            " (${job.missedOccurrences} missed occurrences coalesced)"
        } else {
            ""
        }

        return UserMessage(
            content = "Execute background job: ${job.name}\n$triggerInfo$missedInfo\nWork class: ${job.workClass}, Guarantee: ${job.guaranteeClass}"
        )
    }

    private fun computeNextRunAt(job: ScheduledJob): Instant? {
        val next = TriggerCalculator.nextRunAt(job.trigger, job.lastRunAt)
        return next
    }
}
