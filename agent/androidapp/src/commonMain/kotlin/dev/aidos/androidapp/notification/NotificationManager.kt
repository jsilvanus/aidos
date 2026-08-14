package dev.aidos.androidapp.notification

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalTime
import kotlin.math.min
import kotlin.time.Duration.Companion.hours

/**
 * Notification manager for Android (M32, RFC-0044).
 *
 * Rules:
 * - Rate-limited: never fires the same notification within the throttle window.
 * - Never silently repeated: if a parked run needs the user, it says so exactly once.
 * - The foreground service notification is always live; idle state shows "Aidos: idle".
 * - Quiet hours: notifications outside a configured time window are held until quiet hours end.
 * - Approval notifications bypass quiet hours and budget.
 *
 * This class is platform-neutral — the actual Android `NotificationManager` calls happen
 * in the Android Service wrapper, which delegates here for content decisions.
 */
class NotificationManager(
    private val throttleWindowMs: Long = DEFAULT_THROTTLE_MS,
    private val quietHoursStart: LocalTime? = null,  // e.g., 22:00
    private val quietHoursEnd: LocalTime? = null,    // e.g., 08:00
) {
    private val lastFiredAt = mutableMapOf<String, Long>()
    private val firedOnce = mutableSetOf<String>()
    private val pendingQuietHours = mutableSetOf<String>()

    companion object {
        const val DEFAULT_THROTTLE_MS = 60_000L  // 1 minute
    }

    /**
     * Determines whether a notification for [notificationKey] should fire at [nowMs].
     *
     * Returns true only if:
     * 1. The key has never been shown, OR
     * 2. It is not a one-shot notification AND the throttle window has elapsed.
     * 3. Quiet hours rules are satisfied (approval notifications bypass quiet hours).
     */
    fun shouldFire(
        notificationKey: String,
        nowMs: Long,
        oneShot: Boolean = false,
        isApproval: Boolean = false,
    ): Boolean {
        if (oneShot && notificationKey in firedOnce) return false
        val last = lastFiredAt[notificationKey]
        val throttleOk = last == null || (nowMs - last) >= throttleWindowMs

        if (!throttleOk) return false

        // Approval notifications bypass quiet hours entirely.
        if (isApproval) return true

        // Check quiet hours (non-approval notifications may be deferred).
        if (isInQuietHours(nowMs) && !isApproval) {
            pendingQuietHours.add(notificationKey)
            return false
        }

        // Clear from pending if we're now outside quiet hours.
        pendingQuietHours.remove(notificationKey)
        return true
    }

    /** Records that a notification was fired. Call this after the OS notification is issued. */
    fun recordFired(notificationKey: String, nowMs: Long) {
        lastFiredAt[notificationKey] = nowMs
        firedOnce.add(notificationKey)
    }

    /** Resets state for a key (e.g., when a parked run resumes). */
    fun reset(notificationKey: String) {
        lastFiredAt.remove(notificationKey)
        firedOnce.remove(notificationKey)
        pendingQuietHours.remove(notificationKey)
    }

    /**
     * Gets notifications that should fire now (outside quiet hours) and
     * were previously deferred due to quiet hours.
     */
    fun getPendingNotifications(): Set<String> {
        val now = Clock.System.now().toEpochMilliseconds()
        return if (isInQuietHours(now)) {
            emptySet()
        } else {
            val pending = pendingQuietHours.toSet()
            pendingQuietHours.clear()
            pending
        }
    }

    /**
     * Creates notification content for a parked run that needs user attention.
     * This is a one-shot notification — it fires exactly once per park event (M32).
     * Approval notifications bypass quiet hours and budget constraints.
     */
    fun parkedRunContent(runId: String, reason: String, isApproval: Boolean = false): NotificationContent =
        NotificationContent(
            key = "parked_run_$runId",
            title = "Action needed",
            body = reason,
            isOneShot = true,
            isApproval = isApproval,
        )

    /** Creates notification content for the foreground service. Always live (M27). */
    fun foregroundContent(serviceDescription: String): NotificationContent =
        NotificationContent(
            key = "foreground_service",
            title = "Aidos",
            body = serviceDescription,
            isOneShot = false,
            isApproval = false,
        )

    /** Creates notification content for a background session completion. */
    fun completionContent(sessionName: String, result: String, projectId: String): NotificationContent =
        NotificationContent(
            key = "completion_${projectId}_${System.currentTimeMillis()}",
            title = "Session completed",
            body = "$sessionName: $result",
            isOneShot = false,
            isApproval = false,
            category = NotificationCategory.COMPLETION,
        )

    /** Creates notification content for approval requests (budget and quiet hours bypass). */
    fun approvalContent(runId: String, capability: String, reason: String): NotificationContent =
        NotificationContent(
            key = "approval_$runId",
            title = "Approval required",
            body = "$capability: $reason",
            isOneShot = false,
            isApproval = true,
            category = NotificationCategory.APPROVAL,
        )

    private fun isInQuietHours(nowMs: Long): Boolean {
        if (quietHoursStart == null || quietHoursEnd == null) return false

        val instant = Instant.fromEpochMilliseconds(nowMs)
        val now = instant.toString().split("T")[1].substring(0, 8)  // HH:MM:SS
        val nowTime = LocalTime.parse(now)

        return if (quietHoursStart < quietHoursEnd) {
            // Normal case: 22:00 - 08:00
            nowTime in quietHoursStart..quietHoursEnd
        } else {
            // Overnight case: 22:00 - 08:00 (next day)
            nowTime >= quietHoursStart || nowTime <= quietHoursEnd
        }
    }
}

data class NotificationContent(
    val key: String,
    val title: String,
    val body: String,
    val isOneShot: Boolean,
    val isApproval: Boolean = false,
    val category: NotificationCategory = NotificationCategory.INFORMATIONAL,
)

enum class NotificationCategory {
    APPROVAL,        // Run awaiting capability grant; blocks work until answered
    COMPLETION,      // Session completed; may be coalesced
    INFORMATIONAL,   // Status update; always coalesced
}
