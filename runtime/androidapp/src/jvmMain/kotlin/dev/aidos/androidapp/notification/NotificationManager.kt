package dev.aidos.androidapp.notification

/**
 * Notification manager for Android (M32, RFC-0044).
 *
 * Rules:
 * - Rate-limited: never fires the same notification within the throttle window.
 * - Never silently repeated: if a parked run needs the user, it says so exactly once.
 * - The foreground service notification is always live; idle state shows "Aidos: idle".
 *
 * This class is platform-neutral — the actual Android `NotificationManager` calls happen
 * in the Android Service wrapper, which delegates here for content decisions.
 */
class NotificationManager(
    private val throttleWindowMs: Long = DEFAULT_THROTTLE_MS,
) {
    private val lastFiredAt = mutableMapOf<String, Long>()
    private val firedOnce = mutableSetOf<String>()

    companion object {
        const val DEFAULT_THROTTLE_MS = 60_000L  // 1 minute
    }

    /**
     * Determines whether a notification for [notificationKey] should fire at [nowMs].
     *
     * Returns true only if:
     * 1. The key has never been shown, OR
     * 2. It is not a one-shot notification AND the throttle window has elapsed.
     */
    fun shouldFire(notificationKey: String, nowMs: Long, oneShot: Boolean = false): Boolean {
        if (oneShot && notificationKey in firedOnce) return false
        val last = lastFiredAt[notificationKey]
        return last == null || (nowMs - last) >= throttleWindowMs
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
    }

    /**
     * Creates notification content for a parked run that needs user attention.
     * This is a one-shot notification — it fires exactly once per park event (M32).
     */
    fun parkedRunContent(runId: String, reason: String): NotificationContent =
        NotificationContent(
            key = "parked_run_$runId",
            title = "Action needed",
            body = reason,
            isOneShot = true,
        )

    /** Creates notification content for the foreground service. Always live (M27). */
    fun foregroundContent(serviceDescription: String): NotificationContent =
        NotificationContent(
            key = "foreground_service",
            title = "Aidos",
            body = serviceDescription,
            isOneShot = false,
        )
}

data class NotificationContent(
    val key: String,
    val title: String,
    val body: String,
    val isOneShot: Boolean,
)
