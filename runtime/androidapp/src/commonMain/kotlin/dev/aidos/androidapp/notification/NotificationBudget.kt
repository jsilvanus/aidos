package dev.aidos.androidapp.notification

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlin.time.Duration.Companion.hours

/**
 * Per-project hourly notification budget (RFC-0044, M32).
 *
 * Rules:
 * - Budget is per project per rolling hour (3 default).
 * - APPROVAL category and USER_INITIATED completions bypass the budget.
 * - When budget exhausted, notifications coalesce instead of firing individually.
 * - Once-shot notifications (parked Runs) still respect budget; they must fire but do not consume budget.
 * - Quiet hours are respected by the NotificationManager; the budget does not override them.
 */
class NotificationBudget(
    private val projectId: String,
    private val defaultBudget: Int = 3,
) {
    private data class BudgetWindow(
        val hourStart: Instant,
        val count: Int,
    )

    private var currentWindow: BudgetWindow? = null

    /**
     * Consumes budget and returns whether a notification should fire.
     *
     * - Returns true if budget available or bypassed (APPROVAL or USER_INITIATED)
     * - Returns false if budget exhausted (coalesce instead)
     * - Resets window on hour boundary
     */
    fun tryConsume(
        content: NotificationContent,
        isInitiatedByUser: Boolean,
        nowIso: () -> String = { Clock.System.now().toString() },
    ): Boolean {
        val now = Instant.parse(nowIso())

        // APPROVAL and USER_INITIATED bypass budget entirely.
        if (content.isApproval || isInitiatedByUser) {
            return true
        }

        val window = currentWindow
        val hourAgo = now.minus(1.hours)

        // Reset or initialize window.
        val activeWindow = when {
            window == null -> BudgetWindow(now, 0)
            window.hourStart < hourAgo -> BudgetWindow(now, 0)
            else -> window
        }

        currentWindow = activeWindow

        // Check if we have budget.
        return if (activeWindow.count < defaultBudget) {
            currentWindow = activeWindow.copy(count = activeWindow.count + 1)
            true
        } else {
            false
        }
    }

    /**
     * Gets the current budget remaining in this hour.
     */
    fun getRemainingBudget(nowIso: () -> String = { Clock.System.now().toString() }): Int {
        val now = Instant.parse(nowIso())
        val window = currentWindow
        val hourAgo = now.minus(1.hours)

        val activeWindow = when {
            window == null -> BudgetWindow(now, 0)
            window.hourStart < hourAgo -> BudgetWindow(now, 0)
            else -> window
        }

        currentWindow = activeWindow
        return maxOf(0, defaultBudget - activeWindow.count)
    }

    /** Reset budget for testing or manual intervention. */
    fun reset() {
        currentWindow = null
    }
}
