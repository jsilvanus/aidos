package dev.aidos.androidapp.scheduling

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone

/**
 * Cron expression parser and next-run calculator (RFC-0044, M32).
 * Currently a placeholder that returns null for all cron expressions.
 * TODO: Implement full 5-field cron parsing when needed.
 */
object CronCalculator {
    fun nextRunAt(
        expression: String,
        zone: TimeZone,
        afterIso: String = Clock.System.now().toString(),
    ): Instant? {
        // Placeholder: return null
        // Full implementation would parse 5-field cron format:
        // minute (0-59) hour (0-23) dayOfMonth (1-31) month (1-12) dayOfWeek (0-6)
        return null
    }
}
