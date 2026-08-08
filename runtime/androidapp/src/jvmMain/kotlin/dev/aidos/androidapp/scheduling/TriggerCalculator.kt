package dev.aidos.androidapp.scheduling

import dev.aidos.kernel.EventTriggerFilter
import dev.aidos.kernel.Trigger
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Computes next run time for scheduled job triggers (RFC-0044, M32).
 *
 * Rules:
 * - At: returns the specified instant (or null if in the past and not PROMPT)
 * - Every: returns anchor + N * interval where N is the first future occurrence
 * - OnEvent: returns null (event-driven, no timer)
 * - Cron/OnCondition: not in MVP
 */
object TriggerCalculator {
    /**
     * Computes next_run_at for a trigger.
     *
     * @param trigger The trigger specification
     * @param lastRunAt When the trigger last fired (null if never)
     * @param nowIso Current time as ISO string (defaults to now)
     * @return Instant when trigger should fire next, or null for event-driven triggers
     */
    fun nextRunAt(
        trigger: Trigger,
        lastRunAt: Instant? = null,
        nowIso: String = Clock.System.now().toString(),
    ): Instant? {
        val now = Instant.parse(nowIso)
        return when (trigger) {
            is Trigger.At -> {
                // At triggers are one-shot. If the time is past, return null (unless PROMPT guarantee).
                if (trigger.instant > now) trigger.instant else null
            }

            is Trigger.Every -> {
                // Every triggers are periodic. Compute next occurrence.
                val anchor = trigger.anchor ?: now
                val interval = trigger.interval

                // Calculate how many intervals have passed since anchor.
                val elapsed = now - anchor
                val intervalsElapsed = (elapsed.inWholeMilliseconds / interval.inWholeMilliseconds).toLong()

                // Next occurrence is at anchor + (intervalsElapsed + 1) * interval
                // If lastRunAt is set and is very recent, we just computed it, so next is (intervalsElapsed + 2)
                val nextOccurrenceIndex = intervalsElapsed + 1
                anchor + (interval * nextOccurrenceIndex)
            }

            is Trigger.OnEvent -> {
                // Event triggers are event-driven, not timer-driven.
                null
            }

            is Trigger.Cron -> {
                // Cron triggers use the cron expression to compute next run.
                CronCalculator.nextRunAt(trigger.expression, trigger.zone, nowIso)
            }

            is Trigger.OnCondition -> {
                // Condition triggers are not in MVP; return null as placeholder.
                null
            }
        }
    }

    /**
     * Counts missed occurrences between lastRunAt and now (for periodic triggers only).
     *
     * Returns 0 for one-shot triggers (At) or event-driven triggers (OnEvent).
     * For Every triggers, counts how many intervals have passed without a run.
     */
    fun missedOccurrences(
        trigger: Trigger,
        lastRunAt: Instant?,
        nowIso: String = Clock.System.now().toString(),
    ): Int {
        val now = Instant.parse(nowIso)

        return when (trigger) {
            is Trigger.Every -> {
                if (lastRunAt == null) return 0  // Never ran; nothing missed yet

                val interval = trigger.interval
                val elapsed = now - lastRunAt
                val missedCount = (elapsed.inWholeMilliseconds / interval.inWholeMilliseconds).toInt()
                // Return max(0, count - 1) because we count intervals between runs
                maxOf(0, missedCount - 1)
            }

            // One-shot or event-driven triggers don't accumulate missed occurrences.
            is Trigger.At -> 0
            is Trigger.OnEvent -> 0
            is Trigger.Cron -> 0
            is Trigger.OnCondition -> 0
        }
    }
}
