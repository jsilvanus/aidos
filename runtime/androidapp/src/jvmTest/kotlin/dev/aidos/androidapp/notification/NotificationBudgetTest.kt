package dev.aidos.androidapp.notification

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NotificationBudgetTest {

    // RFC-0044: Per-project hourly budget (3 default).
    @Test
    fun testBudgetAllowsThreeNotifications() {
        val budget = NotificationBudget("project-1", defaultBudget = 3)
        val baseTime = "2026-08-09T10:00:00Z"
        var callCount = 0
        fun now() = baseTime  // Fixed time for this test

        // First three COMPLETION notifications consume budget.
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })

        // Fourth is rejected.
        assertFalse(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })
    }

    // RFC-0044: APPROVAL category bypasses budget.
    @Test
    fun testApprovalBypassesBudget() {
        val budget = NotificationBudget("project-1", defaultBudget = 1)
        val baseTime = "2026-08-09T10:00:00Z"
        fun now() = baseTime

        // One COMPLETION consumes the budget.
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })
        assertFalse(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })

        // But APPROVAL still fires (bypass).
        assertTrue(budget.tryConsume(NotificationCategory.APPROVAL, false) { now() })
        assertTrue(budget.tryConsume(NotificationCategory.APPROVAL, false) { now() })
    }

    // RFC-0044: USER_INITIATED completions bypass budget.
    @Test
    fun testUserInitiatedBypassesBudget() {
        val budget = NotificationBudget("project-1", defaultBudget = 1)
        val baseTime = "2026-08-09T10:00:00Z"
        fun now() = baseTime

        // One COMPLETION consumes the budget.
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })
        assertFalse(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })

        // But USER_INITIATED still fires (bypass).
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, true) { now() })
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, true) { now() })
    }

    // RFC-0044: Budget resets after 1 hour (rolling hour window).
    @Test
    fun testBudgetResetsAfterOneHour() {
        val budget = NotificationBudget("project-1", defaultBudget = 1)
        val startTime = "2026-08-09T10:00:00Z"
        val oneHourLater = "2026-08-09T11:00:01Z"
        var time = startTime
        fun now() = time

        // Consume the budget at 10:00.
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })
        assertFalse(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })

        // Move to 11:00:01 (past the 1-hour window).
        time = oneHourLater

        // Budget is reset; can consume again.
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })
        assertFalse(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })
    }

    // RFC-0044: Budget window is rolling (not aligned to clock hours).
    @Test
    fun testBudgetWindowIsRolling() {
        val budget = NotificationBudget("project-1", defaultBudget = 1)
        val firstTime = "2026-08-09T10:30:00Z"
        val fiftyNineMinutesLater = "2026-08-09T11:29:00Z"
        val sixtyOneMinutesLater = "2026-08-09T11:31:00Z"
        var time = firstTime
        fun now() = time

        // Consume at 10:30.
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })

        // At 11:29 (59 min later), still within the 1-hour window.
        time = fiftyNineMinutesLater
        assertFalse(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })

        // At 11:31 (61 min later), window has passed.
        time = sixtyOneMinutesLater
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })
    }

    // Reset clears budget state.
    @Test
    fun testResetClearsBudgetState() {
        val budget = NotificationBudget("project-1", defaultBudget = 1)
        val time = "2026-08-09T10:00:00Z"
        fun now() = time

        // Consume.
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })
        assertFalse(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })

        // Reset.
        budget.reset()

        // Budget is available again.
        assertTrue(budget.tryConsume(NotificationCategory.COMPLETION, false) { now() })
    }

    // INFORMATIONAL category respects budget (no bypass).
    @Test
    fun testInformationalRespectsBudget() {
        val budget = NotificationBudget("project-1", defaultBudget = 1)
        val time = "2026-08-09T10:00:00Z"
        fun now() = time

        // One INFORMATIONAL consumes budget.
        assertTrue(budget.tryConsume(NotificationCategory.INFORMATIONAL, false) { now() })
        assertFalse(budget.tryConsume(NotificationCategory.INFORMATIONAL, false) { now() })
    }
}
