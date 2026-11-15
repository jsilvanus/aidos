package dev.aidos.testing

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Deterministic virtual clock for tests (RFC-0038).
 * Advances only when a test explicitly calls [advanceBy] or [setNow].
 */
class TestClock(
    initial: Instant = Instant.parse("2026-01-01T00:00:00Z"),
) : Clock {
    private var current: Instant = initial

    override fun now(): Instant = current

    fun nowIso(): String = current.toString()

    fun advanceBy(duration: Duration) {
        current = current.plus(duration)
    }

    fun advanceMillis(millis: Long) {
        advanceBy(millis.milliseconds)
    }

    fun setNow(instant: Instant) {
        current = instant
    }
}
