package dev.aidos.androidapp.scheduling

import dev.aidos.kernel.EventTriggerFilter
import dev.aidos.kernel.Trigger
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

class TriggerCalculatorTest {

    // RFC-0044: At triggers are one-shot; nextRunAt is the specified instant if in the future.
    @Test
    fun testAtTriggerInFuture() {
        val instant = Instant.parse("2026-08-09T12:00:00Z")
        val trigger = Trigger.At(instant)
        val now = "2026-08-09T11:00:00Z"

        val next = TriggerCalculator.nextRunAt(trigger, null, now)
        assertEquals(instant, next)
    }

    // At trigger in the past returns null (already fired).
    @Test
    fun testAtTriggerInPast() {
        val instant = Instant.parse("2026-08-09T11:00:00Z")
        val trigger = Trigger.At(instant)
        val now = "2026-08-09T12:00:00Z"

        val next = TriggerCalculator.nextRunAt(trigger, null, now)
        assertNull(next)
    }

    // Every trigger with anchor: computes first future occurrence.
    @Test
    fun testEveryTriggerComputesNextOccurrence() {
        val anchor = Instant.parse("2026-08-09T10:00:00Z")
        val interval = 1.hours
        val trigger = Trigger.Every(interval, anchor)
        val now = "2026-08-09T11:30:00Z"

        val next = TriggerCalculator.nextRunAt(trigger, null, now)
        // 1 hour has passed, so next occurrence is at 12:00 (anchor + 2 * 1h)
        assertEquals(Instant.parse("2026-08-09T12:00:00Z"), next)
    }

    // Every trigger without anchor uses now as anchor.
    @Test
    fun testEveryTriggerWithoutAnchor() {
        val interval = 30.minutes
        val trigger = Trigger.Every(interval, null)
        val now = "2026-08-09T10:15:00Z"

        val next = TriggerCalculator.nextRunAt(trigger, null, now)
        // Next occurrence after 30 minutes
        assertEquals(Instant.parse("2026-08-09T10:45:00Z"), next)
    }

    // OnEvent triggers are event-driven; nextRunAt is null.
    @Test
    fun testOnEventTriggerReturnsNull() {
        val filter = EventTriggerFilter(eventType = "file_changed")
        val trigger = Trigger.OnEvent(filter)
        val now = "2026-08-09T10:00:00Z"

        val next = TriggerCalculator.nextRunAt(trigger, null, now)
        assertNull(next)
    }

    // Missed occurrences for Every triggers.
    @Test
    fun testMissedOccurrencesForEveryTrigger() {
        val anchor = Instant.parse("2026-08-09T10:00:00Z")
        val interval = 1.hours
        val trigger = Trigger.Every(interval, anchor)

        // Last run at 10:00, now is 13:30 => 3 hours passed, so 2 missed (10→11, 11→12, 12→13).
        // Actually: elapsed = 3.5 hours; 3.5 / 1 = 3 intervals; missed = 3 - 1 = 2.
        val lastRunAt = Instant.parse("2026-08-09T10:00:00Z")
        val now = "2026-08-09T13:30:00Z"

        val missed = TriggerCalculator.missedOccurrences(trigger, lastRunAt, now)
        assertEquals(2, missed)
    }

    // No missed occurrences if trigger never ran.
    @Test
    fun testNoMissedOccurrencesIfNeverRan() {
        val trigger = Trigger.Every(1.hours, Instant.parse("2026-08-09T10:00:00Z"))
        val now = "2026-08-09T13:00:00Z"

        val missed = TriggerCalculator.missedOccurrences(trigger, null, now)
        assertEquals(0, missed)
    }

    // At triggers don't track missed occurrences.
    @Test
    fun testAtTriggerNoMissedOccurrences() {
        val trigger = Trigger.At(Instant.parse("2026-08-09T12:00:00Z"))
        val lastRunAt = Instant.parse("2026-08-09T12:00:00Z")
        val now = "2026-08-09T13:00:00Z"

        val missed = TriggerCalculator.missedOccurrences(trigger, lastRunAt, now)
        assertEquals(0, missed)
    }

    // OnEvent triggers don't track missed occurrences.
    @Test
    fun testOnEventTriggerNoMissedOccurrences() {
        val trigger = Trigger.OnEvent(EventTriggerFilter(eventType = "file_changed"))
        val lastRunAt = Instant.parse("2026-08-09T12:00:00Z")
        val now = "2026-08-09T13:00:00Z"

        val missed = TriggerCalculator.missedOccurrences(trigger, lastRunAt, now)
        assertEquals(0, missed)
    }
}
