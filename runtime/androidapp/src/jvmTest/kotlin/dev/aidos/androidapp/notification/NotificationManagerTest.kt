package dev.aidos.androidapp.notification

import kotlinx.datetime.LocalTime
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class NotificationManagerTest {

    @Test
    fun `notificationManager fires regular notification when throttle window elapsed`() {
        val nm = NotificationManager(throttleWindowMs = 60_000L)
        val key = "progress_update"

        val t0 = 1_000L
        assertTrue(nm.shouldFire(key, t0))
        nm.recordFired(key, t0)

        // Within throttle window — must not fire.
        assertFalse(nm.shouldFire(key, t0 + 30_000L))

        // After throttle window — may fire again.
        assertTrue(nm.shouldFire(key, t0 + 61_000L))
    }

    @Test
    fun `parked run notification is one-shot`() {
        val nm = NotificationManager(throttleWindowMs = 60_000L)
        val content = nm.parkedRunContent("run-1", "Approval needed: write to src/main.kt")

        val t0 = 1_000L
        assertTrue(nm.shouldFire(content.key, t0, oneShot = true))
        nm.recordFired(content.key, t0)

        // One-shot: should not fire again even after throttle window.
        assertFalse(nm.shouldFire(content.key, t0 + 120_000L, oneShot = true),
            "Parked run notification must fire exactly once")
    }

    @Test
    fun `approval notification bypasses quiet hours`() {
        val nm = NotificationManager(
            throttleWindowMs = 1_000L,
            quietHoursStart = LocalTime(22, 0),
            quietHoursEnd = LocalTime(8, 0)
        )

        // Use a timestamp that would be in quiet hours
        // 2026-08-08T23:00:00Z is 23:00, which is within quiet hours (22:00 - 08:00)
        val quietTime = 1_691_000_000_000L  // Arbitrary past timestamp

        // Non-approval notification should be held during quiet hours
        val content = nm.parkedRunContent("run-1", "Regular notification", isApproval = false)
        assertFalse(nm.shouldFire(content.key, quietTime),
            "Non-approval notification should be held during quiet hours")

        // Approval notification should bypass quiet hours
        val approvalContent = nm.approvalContent("run-2", "fs_write", "Needs approval")
        assertTrue(nm.shouldFire(approvalContent.key, quietTime, isApproval = true),
            "Approval notifications must bypass quiet hours")
    }

    @Test
    fun `reset clears notification state`() {
        val nm = NotificationManager(throttleWindowMs = 60_000L)
        val key = "test_key"

        val t0 = 1_000L
        assertTrue(nm.shouldFire(key, t0))
        nm.recordFired(key, t0)

        // Should be throttled
        assertFalse(nm.shouldFire(key, t0 + 30_000L))

        // Reset clears state
        nm.reset(key)

        // Now should fire again
        assertTrue(nm.shouldFire(key, t0 + 30_000L))
    }

    @Test
    fun `completion notification has correct category`() {
        val nm = NotificationManager()
        val content = nm.completionContent("Session", "Success", "project-1")

        assertEquals(content.category, NotificationCategory.COMPLETION)
        assertFalse(content.isApproval)
    }

    @Test
    fun `approval notification has correct properties`() {
        val nm = NotificationManager()
        val content = nm.approvalContent("run-1", "fs_write", "Write to file")

        assertEquals(content.category, NotificationCategory.APPROVAL)
        assertTrue(content.isApproval)
        assertFalse(content.isOneShot)
    }

    @Test
    fun `foreground service notification is always live`() {
        val nm = NotificationManager()
        val content = nm.foregroundContent("Running: Test session")

        assertFalse(content.isOneShot)
        assertFalse(content.isApproval)
    }
}
