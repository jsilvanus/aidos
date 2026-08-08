package dev.aidos.androidapp.scheduling

import kotlinx.datetime.Clock
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.minutes

/**
 * Cron expression parser and next-run calculator (RFC-0044, M32).
 *
 * Parses 5-field cron format: minute hour dayOfMonth month dayOfWeek
 * Supports common patterns like */N, N-M, N,M,O, and weekday names.
 *
 * Rules:
 * - minute: 0-59
 * - hour: 0-23
 * - dayOfMonth: 1-31
 * - month: 1-12 (Jan-Dec)
 * - dayOfWeek: 0-6 (Sun-Sat), also 7 for Sunday
 *
 * Patterns:
 * - *: any value
 * - */N: every N units (e.g., */15 minutes)
 * - N-M: range (e.g., 9-17 hours)
 * - N,M,O: list (e.g., 1,15,30 minutes)
 * - Weekday names: MON, TUE, WED, THU, FRI, SAT, SUN (case-insensitive)
 */
object CronCalculator {
    /**
     * Computes the next run time for a cron expression.
     *
     * @param expression 5-field cron expression
     * @param zone Timezone for calculation
     * @param afterIso Current time as ISO string (defaults to now). Finds next run after this instant.
     * @return Instant when the cron should fire next, or null if no valid next time exists
     */
    fun nextRunAt(
        expression: String,
        zone: TimeZone,
        afterIso: String = Clock.System.now().toString(),
    ): Instant? {
        return try {
            val after = Instant.parse(afterIso)
            val fields = expression.split("\\s+".toRegex())
            if (fields.size != 5) return null

            val minute = parseField(fields[0], 0, 59)
            val hour = parseField(fields[1], 0, 23)
            val dayOfMonth = parseField(fields[2], 1, 31)
            val month = parseField(fields[3], 1, 12)
            val dayOfWeek = parseDayOfWeek(fields[4])

            if (minute == null || hour == null || dayOfMonth == null || month == null || dayOfWeek == null) {
                return null
            }

            findNextRun(minute, hour, dayOfMonth, month, dayOfWeek, zone, after)
        } catch (e: Exception) {
            null
        }
    }

    private fun findNextRun(
        minute: Set<Int>,
        hour: Set<Int>,
        dayOfMonth: Set<Int>,
        month: Set<Int>,
        dayOfWeek: Set<Int>,
        zone: TimeZone,
        after: Instant,
    ): Instant? {
        var current = after.toLocalDateTime(zone)

        // Start from the next minute boundary
        current = current.copy(second = 0, nanosecond = 0).plusMinutes(1)

        // Try up to 4 years to find a matching time (to handle leap years, etc.)
        val maxIterations = 366 * 24 * 60 * 4
        var iterations = 0

        while (iterations < maxIterations) {
            iterations++

            // Check if current time matches the cron expression
            if (current.monthNumber in month &&
                current.hour in hour &&
                current.minute in minute
            ) {
                // Check day constraint: either dayOfMonth matches OR dayOfWeek matches
                val dayMatches = current.dayOfMonth in dayOfMonth
                val weekdayMatches = dayOfWeekValue(current.dayOfWeek) in dayOfWeek

                // If both are unrestricted (*), always match
                val isMonthUnrestricted = dayOfMonth == (1..31).toSet()
                val isWeekdayUnrestricted = dayOfWeek == (0..6).toSet()

                val dayConstraintMet = when {
                    isMonthUnrestricted && isWeekdayUnrestricted -> true
                    isMonthUnrestricted -> weekdayMatches
                    isWeekdayUnrestricted -> dayMatches
                    else -> dayMatches || weekdayMatches
                }

                if (dayConstraintMet) {
                    return current.toInstant(zone)
                }
            }

            current = current.plusMinutes(1)
        }

        return null
    }

    private fun dayOfWeekValue(dayOfWeek: DayOfWeek): Int {
        // Convert ISO weekday (1=Mon, 7=Sun) to cron weekday (0=Sun, 6=Sat)
        return if (dayOfWeek == DayOfWeek.SUNDAY) 0 else dayOfWeek.isoDayNumber
    }

    private fun parseField(field: String, min: Int, max: Int): Set<Int>? {
        return when {
            field == "*" -> (min..max).toSet()
            field.startsWith("*/") -> {
                val step = field.substring(2).toIntOrNull() ?: return null
                (min..max).filter { (it - min) % step == 0 }.toSet()
            }

            "-" in field -> {
                val (start, end) = field.split("-")
                val startVal = start.toIntOrNull() ?: return null
                val endVal = end.toIntOrNull() ?: return null
                (startVal..endVal).toSet()
            }

            "," in field -> {
                field.split(",").mapNotNull { it.trim().toIntOrNull() }.toSet()
            }

            else -> {
                val value = field.toIntOrNull() ?: return null
                setOf(value)
            }
        }
    }

    private fun parseDayOfWeek(field: String): Set<Int>? {
        val weekdayMap = mapOf(
            "sun" to 0, "mon" to 1, "tue" to 2, "wed" to 3,
            "thu" to 4, "fri" to 5, "sat" to 6,
        )

        return when {
            field == "*" -> (0..6).toSet()
            field.startsWith("*/") -> {
                val step = field.substring(2).toIntOrNull() ?: return null
                (0..6).filter { it % step == 0 }.toSet()
            }

            "-" in field -> {
                val parts = field.split("-")
                if (parts.size != 2) return null
                val start = nameToNumber(parts[0], weekdayMap) ?: return null
                val end = nameToNumber(parts[1], weekdayMap) ?: return null
                (start..end).toSet()
            }

            "," in field -> {
                field.split(",").mapNotNull { part ->
                    nameToNumber(part.trim(), weekdayMap)
                }.toSet()
            }

            else -> {
                val value = nameToNumber(field, weekdayMap) ?: return null
                // Normalize 7 (Sunday in some cron formats) to 0
                setOf(if (value == 7) 0 else value)
            }
        }
    }

    private fun nameToNumber(name: String, map: Map<String, Int>): Int? {
        return map[name.lowercase()] ?: name.toIntOrNull()
    }
}

/**
 * Extension to add minutes to LocalDateTime.
 */
private fun LocalDateTime.plusMinutes(minutes: Int): LocalDateTime {
    val instant = this.toInstant(TimeZone.UTC)
    val newInstant = instant + minutes.minutes
    return newInstant.toLocalDateTime(TimeZone.UTC)
}
