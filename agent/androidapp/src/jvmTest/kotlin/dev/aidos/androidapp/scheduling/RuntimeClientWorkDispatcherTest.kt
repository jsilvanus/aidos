package dev.aidos.androidapp.scheduling

import dev.aidos.api.MockRuntimeClient
import dev.aidos.api.RunResult
import dev.aidos.api.UserMessage
import dev.aidos.kernel.EventTriggerFilter
import dev.aidos.kernel.GuaranteeClass
import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.ScheduledJobId
import dev.aidos.kernel.Trigger
import dev.aidos.kernel.WorkClass
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.hours

/**
 * Tests for RuntimeClientWorkDispatcher.onTimerFired emission point (RFC-0004, fourth
 * emission point). Verifies the callback is invoked only for time-based triggers (At, Every,
 * Cron) on successful dispatch, and never for event/condition-driven triggers (OnEvent,
 * OnCondition) or failed dispatches.
 */
class RuntimeClientWorkDispatcherTest {

    private suspend fun createMockSession(client: MockRuntimeClient, projectId: String = "project-1"): String {
        val sessionResult = client.sessions.create(dev.aidos.api.CreateSessionRequest(projectId, "test session"))
        return (sessionResult as dev.aidos.api.SessionResult.Success).session.id
    }

    private fun createTestJob(
        id: String = "job-1",
        projectId: String = "project-1",
        sessionId: String? = "session-1",
        trigger: Trigger = Trigger.Every(1.hours, Instant.parse("2026-08-09T10:00:00Z")),
        workClass: WorkClass = WorkClass.INTERACTIVE,
        enabled: Boolean = true,
        nextRunAt: Instant? = Instant.parse("2026-08-09T12:00:00Z"),
    ) = ScheduledJob(
        id = ScheduledJobId(id),
        projectId = projectId,
        sessionId = sessionId,
        name = "test job",
        trigger = trigger,
        guaranteeClass = GuaranteeClass.EVENTUAL,
        workClass = workClass,
        constraintsJson = "{}",
        enabled = enabled,
        nextRunAt = nextRunAt,
        lastRunAt = null,
        lastOutcome = null,
        consecutiveFailures = 0,
        missedOccurrences = 0,
        createdAt = Instant.parse("2026-08-08T00:00:00Z"),
    )

    // ── Tests for timer-based triggers ──

    /**
     * A time-based trigger (Every) dispatched successfully should fire the onTimerFired
     * callback with the job object.
     */
    @Test
    fun testTimerFiredCallbackOnSuccessfulEveryDispatch() = runTest {
        val client = MockRuntimeClient()
        val jobManager = InMemoryScheduledJobManager()
        var firedJob: ScheduledJob? = null
        val dispatcher = RuntimeClientWorkDispatcher(
            client = client,
            jobManager = jobManager,
            onTimerFired = { job -> firedJob = job }
        )

        val sessionId = createMockSession(client)
        val job = createTestJob(workClass = WorkClass.INTERACTIVE, sessionId = sessionId)
        jobManager.create(job)

        val result = dispatcher.dispatch(job)

        assertTrue(result, "dispatch should return true on success")
        assertEquals(job, firedJob, "onTimerFired should be called with the exact job")
    }

    /**
     * A time-based trigger (At) dispatched successfully should fire the onTimerFired callback.
     */
    @Test
    fun testTimerFiredCallbackOnSuccessfulAtDispatch() = runTest {
        val client = MockRuntimeClient()
        val jobManager = InMemoryScheduledJobManager()
        var callCount = 0
        val dispatcher = RuntimeClientWorkDispatcher(
            client = client,
            jobManager = jobManager,
            onTimerFired = { callCount++ }
        )

        val sessionId = createMockSession(client)
        val job = createTestJob(
            trigger = Trigger.At(Instant.parse("2026-08-09T12:00:00Z")),
            sessionId = sessionId
        )
        jobManager.create(job)

        val result = dispatcher.dispatch(job)

        assertTrue(result)
        assertEquals(1, callCount, "onTimerFired should be called exactly once")
    }

    /**
     * A time-based trigger (Cron) dispatched successfully should fire the onTimerFired callback.
     */
    @Test
    fun testTimerFiredCallbackOnSuccessfulCronDispatch() = runTest {
        val client = MockRuntimeClient()
        val jobManager = InMemoryScheduledJobManager()
        var callCount = 0
        val dispatcher = RuntimeClientWorkDispatcher(
            client = client,
            jobManager = jobManager,
            onTimerFired = { callCount++ }
        )

        val sessionId = createMockSession(client)
        val job = createTestJob(
            trigger = Trigger.Cron("0 12 * * *", kotlinx.datetime.TimeZone.UTC),
            sessionId = sessionId
        )
        jobManager.create(job)

        val result = dispatcher.dispatch(job)

        assertTrue(result)
        assertEquals(1, callCount, "onTimerFired should be called for Cron triggers")
    }

    // ── Tests for event/condition-driven triggers ──

    /**
     * An event-driven trigger (OnEvent) dispatched successfully should NOT fire the callback.
     * RFC-0004's causality field distinguishes timer-fired (elapsed time) from event-fired
     * (external event); calling the callback would misrepresent causality.
     */
    @Test
    fun testTimerFiredNotCalledForOnEventTrigger() = runTest {
        val client = MockRuntimeClient()
        val jobManager = InMemoryScheduledJobManager()
        var callCount = 0
        val dispatcher = RuntimeClientWorkDispatcher(
            client = client,
            jobManager = jobManager,
            onTimerFired = { callCount++ }
        )

        val sessionId = createMockSession(client)
        val job = createTestJob(
            trigger = Trigger.OnEvent(EventTriggerFilter(eventType = "file_changed")),
            sessionId = sessionId
        )
        jobManager.create(job)

        val result = dispatcher.dispatch(job)

        assertTrue(result, "dispatch should still succeed")
        assertEquals(0, callCount, "onTimerFired should NOT be called for event-driven triggers")
    }

    /**
     * A condition-driven trigger (OnCondition) dispatched successfully should NOT fire the
     * callback.
     */
    @Test
    fun testTimerFiredNotCalledForOnConditionTrigger() = runTest {
        val client = MockRuntimeClient()
        val jobManager = InMemoryScheduledJobManager()
        var callCount = 0
        val dispatcher = RuntimeClientWorkDispatcher(
            client = client,
            jobManager = jobManager,
            onTimerFired = { callCount++ }
        )

        val sessionId = createMockSession(client)
        val job = createTestJob(
            trigger = Trigger.OnCondition(dev.aidos.kernel.ConditionRef(sessionId, "memory-pressure")),
            sessionId = sessionId
        )
        jobManager.create(job)

        val result = dispatcher.dispatch(job)

        assertTrue(result)
        assertEquals(0, callCount, "onTimerFired should NOT be called for condition-driven triggers")
    }

    // ── Tests for failed dispatches ──

    /**
     * A time-based trigger whose dispatch fails should NOT fire the callback. Failure here
     * means the RuntimeClient returns an error or throws, or sessionId is null.
     */
    @Test
    fun testTimerFiredNotCalledOnFailedDispatch() = runTest {
        val client = MockRuntimeClient()
        val jobManager = InMemoryScheduledJobManager()
        var callCount = 0
        val dispatcher = RuntimeClientWorkDispatcher(
            client = client,
            jobManager = jobManager,
            onTimerFired = { callCount++ }
        )

        // Job with no sessionId will fail dispatch (returns false immediately)
        val job = createTestJob(sessionId = null)
        jobManager.create(job)

        val result = dispatcher.dispatch(job)

        assertFalse(result, "dispatch should return false when sessionId is null")
        assertEquals(0, callCount, "onTimerFired should NOT be called on failed dispatch")
    }

    /**
     * A disabled job should not be dispatched and should not fire the callback.
     */
    @Test
    fun testTimerFiredNotCalledForDisabledJob() = runTest {
        val client = MockRuntimeClient()
        val jobManager = InMemoryScheduledJobManager()
        var callCount = 0
        val dispatcher = RuntimeClientWorkDispatcher(
            client = client,
            jobManager = jobManager,
            onTimerFired = { callCount++ }
        )

        val job = createTestJob(enabled = false)
        jobManager.create(job)

        val result = dispatcher.dispatch(job)

        assertFalse(result, "dispatch should return false for disabled jobs")
        assertEquals(0, callCount, "onTimerFired should NOT be called for disabled jobs")
    }

    // ── Tests covering multiple dispatch methods ──

    /**
     * Test dispatchScheduled with a time-based trigger to verify the callback is invoked
     * across different dispatch work classes.
     */
    @Test
    fun testTimerFiredCallbackOnSuccessfulScheduledDispatch() = runTest {
        val client = MockRuntimeClient()
        val jobManager = InMemoryScheduledJobManager()
        var firedJob: ScheduledJob? = null
        val dispatcher = RuntimeClientWorkDispatcher(
            client = client,
            jobManager = jobManager,
            onTimerFired = { job -> firedJob = job }
        )

        val sessionId = createMockSession(client)
        val job = createTestJob(workClass = WorkClass.SCHEDULED, sessionId = sessionId)
        jobManager.create(job)

        val result = dispatcher.dispatch(job)

        assertTrue(result)
        assertEquals(job, firedJob, "onTimerFired should fire for SCHEDULED work class too")
    }

    /**
     * Test dispatchDeferred with an event-driven trigger to verify the callback is NOT
     * invoked across different dispatch work classes when trigger is not time-based.
     */
    @Test
    fun testTimerFiredNotCalledForDeferredDispatchWithEventTrigger() = runTest {
        val client = MockRuntimeClient()
        val jobManager = InMemoryScheduledJobManager()
        var callCount = 0
        val dispatcher = RuntimeClientWorkDispatcher(
            client = client,
            jobManager = jobManager,
            onTimerFired = { callCount++ }
        )

        val sessionId = createMockSession(client)
        val job = createTestJob(
            workClass = WorkClass.DEFERRED,
            trigger = Trigger.OnEvent(EventTriggerFilter(eventType = "git_commit")),
            sessionId = sessionId
        )
        jobManager.create(job)

        val result = dispatcher.dispatch(job)

        assertTrue(result)
        assertEquals(0, callCount, "onTimerFired should NOT fire for event-triggered DEFERRED jobs")
    }

    /**
     * Test default no-op callback behavior. Without passing onTimerFired, the dispatcher
     * should use the default empty lambda and still dispatch successfully.
     */
    @Test
    fun testDefaultNoOpTimerFiredCallback() = runTest {
        val client = MockRuntimeClient()
        val jobManager = InMemoryScheduledJobManager()
        // Don't pass onTimerFired; should use default no-op
        val dispatcher = RuntimeClientWorkDispatcher(
            client = client,
            jobManager = jobManager,
        )

        val sessionId = createMockSession(client)
        val job = createTestJob(sessionId = sessionId)
        jobManager.create(job)

        val result = dispatcher.dispatch(job)

        assertTrue(result, "dispatch should succeed even with default no-op callback")
    }
}
