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

        val completion = NotificationContent("c1", "Test", "Body", false, false, NotificationCategory.COMPLETION)

        // First three COMPLETION notifications consume budget.
        assertTrue(budget.tryConsume(completion, false) { now() })
        assertTrue(budget.tryConsume(completion, false) { now() })
        assertTrue(budget.tryConsume(completion, false) { now() })

        // Fourth is rejected.
        assertFalse(budget.tryConsume(completion, false) { now() })
    }

    // RFC-0044: APPROVAL category bypasses budget.
    @Test
    fun testApprovalBypassesBudget() {
        val budget = NotificationBudget("project-1", defaultBudget = 1)
        val baseTime = "2026-08-09T10:00:00Z"
        fun now() = baseTime

        val completion = NotificationContent("c1", "Test", "Body", false, false, NotificationCategory.COMPLETION)
        val approval = NotificationContent("a1", "Approval", "Body", false, true, NotificationCategory.APPROVAL)

        // One COMPLETION consumes the budget.
        assertTrue(budget.tryConsume(completion, false) { now() })
        assertFalse(budget.tryConsume(completion, false) { now() })

        // But APPROVAL still fires (bypass).
        assertTrue(budget.tryConsume(approval, false) { now() })
        assertTrue(budget.tryConsume(approval, false) { now() })
    }

    // RFC-0044: USER_INITIATED completions bypass budget.
    @Test
    fun testUserInitiatedBypassesBudget() {
        val budget = NotificationBudget("project-1", defaultBudget = 1)
        val baseTime = "2026-08-09T10:00:00Z"
        fun now() = baseTime

        val completion = NotificationContent("c1", "Test", "Body", false, false, NotificationCategory.COMPLETION)

        // One COMPLETION consumes the budget.
        assertTrue(budget.tryConsume(completion, false) { now() })
        assertFalse(budget.tryConsume(completion, false) { now() })

        // But USER_INITIATED still fires (bypass).
        assertTrue(budget.tryConsume(completion, true) { now() })
        assertTrue(budget.tryConsume(completion, true) { now() })
    }

    // RFC-0044: Budget resets after 1 hour (rolling hour window).
    @Test
    fun testBudgetResetsAfterOneHour() {
        val budget = NotificationBudget("project-1", defaultBudget = 1)
        val startTime = "2026-08-09T10:00:00Z"
        val oneHourLater = "2026-08-09T11:00:01Z"
        var time = startTime
        fun now() = time

        val completion = NotificationContent("c1", "Test", "Body", false, false, NotificationCategory.COMPLETION)

        // Consume the budget at 10:00.
        assertTrue(budget.tryConsume(completion, false) { now() })
        assertFalse(budget.tryConsume(completion, false) { now() })

        // Move to 11:00:01 (past the 1-hour window).
        time = oneHourLater

        // Budget is reset; can consume again.
        assertTrue(budget.tryConsume(completion, false) { now() })
        assertFalse(budget.tryConsume(completion, false) { now() })
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

        val completion = NotificationContent("c1", "Test", "Body", false, false, NotificationCategory.COMPLETION)

        // Consume at 10:30.
        assertTrue(budget.tryConsume(completion, false) { now() })

        // At 11:29 (59 min later), still within the 1-hour window.
        time = fiftyNineMinutesLater
        assertFalse(budget.tryConsume(completion, false) { now() })

        // At 11:31 (61 min later), window has passed.
        time = sixtyOneMinutesLater
        assertTrue(budget.tryConsume(completion, false) { now() })
    }

    // Reset clears budget state.
    @Test
    fun testResetClearsBudgetState() {
        val budget = NotificationBudget("project-1", defaultBudget = 1)
        val time = "2026-08-09T10:00:00Z"
        fun now() = time

        val completion = NotificationContent("c1", "Test", "Body", false, false, NotificationCategory.COMPLETION)

        // Consume.
        assertTrue(budget.tryConsume(completion, false) { now() })
        assertFalse(budget.tryConsume(completion, false) { now() })

        // Reset.
        budget.reset()

        // Budget is available again.
        assertTrue(budget.tryConsume(completion, false) { now() })
    }

    // INFORMATIONAL category respects budget (no bypass).
    @Test
    fun testInformationalRespectsBudget() {
        val budget = NotificationBudget("project-1", defaultBudget = 1)
        val time = "2026-08-09T10:00:00Z"
        fun now() = time

        val informational = NotificationContent("i1", "Info", "Body", false, false, NotificationCategory.INFORMATIONAL)

        // One INFORMATIONAL consumes budget.
        assertTrue(budget.tryConsume(informational, false) { now() })
        assertFalse(budget.tryConsume(informational, false) { now() })
    }
}
