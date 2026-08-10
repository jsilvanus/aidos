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
 *
 * TimerFired emission (RFC-0004): When a job with a time-based trigger (At, Every, Cron)
 * is dispatched successfully, `onTimerFired` is called with the job. Deliberately excludes
 * OnEvent and OnCondition triggers, which are event/condition-driven, not timer-fired — the
 * job object carries the job ID and trigger details; the caller decides whether/how to
 * publish this as a real event. Not yet exercised by any live caller — see PIPELINE.md's
 * Group 1 notes. The `androidapp` module has no reason to depend on `executor`/`EventStore`.
 */
class RuntimeClientWorkDispatcher(
    private val client: RuntimeClient,
    private val jobManager: ScheduledJobManager,
    /**
     * Called after a successful dispatch of a time-based timer job. Fires only when:
     * - `client.sessions.send()` returns `RunResult.Accepted` (confirmed success), AND
     * - `job.trigger` is Trigger.At, Trigger.Every, or Trigger.Cron (time-based).
     * Does NOT fire for Trigger.OnEvent or Trigger.OnCondition (event/condition-driven).
     * The caller maps the job to the actual event and publishes via EventStore.
     */
    private val onTimerFired: (job: ScheduledJob) -> Unit = {},
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
            val accepted = result is RunResult.Accepted
            if (accepted && isTimerFiredTrigger(job)) {
                onTimerFired(job)
            }
            accepted
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
            val accepted = result is RunResult.Accepted
            if (accepted && isTimerFiredTrigger(job)) {
                onTimerFired(job)
            }
            accepted
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
            val accepted = result is RunResult.Accepted
            if (accepted && isTimerFiredTrigger(job)) {
                onTimerFired(job)
            }
            accepted
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
            val accepted = result is RunResult.Accepted
            if (accepted && isTimerFiredTrigger(job)) {
                onTimerFired(job)
            }
            accepted
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

    /**
     * Determines if a trigger is time-based (At, Every, Cron) vs event/condition-driven
     * (OnEvent, OnCondition). Only time-based triggers fire the TimerFired event.
     * RFC-0004's causality field distinguishes these: timer-fired is a genuine elapsed-time
     * occurrence, while event/condition-fired has a different causal root.
     */
    private fun isTimerFiredTrigger(job: ScheduledJob): Boolean = when (job.trigger) {
        is Trigger.At, is Trigger.Every, is Trigger.Cron -> true
        is Trigger.OnEvent, is Trigger.OnCondition -> false
    }
}
