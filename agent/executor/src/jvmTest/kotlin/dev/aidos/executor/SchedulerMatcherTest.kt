package dev.aidos.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC-0005 MVP items 1 (event-driven wake: topic and type matching) and 3 (self-wake refusal).
 */
class SchedulerMatcherTest {

    private fun event(type: String = "GitCommit", topic: String? = "git:master") = EventRow(
        id = "evt-1", sequence = 1, type = type, source = "git",
        payload = "{}", causality = null, causalDepth = 0, timestamp = "2026-08-09T00:00:00Z", topic = topic,
    )

    private fun sub(
        sessionId: String,
        topicPatterns: List<String> = listOf("*"),
        eventTypes: List<String>? = null,
        selfWake: Boolean = false,
    ) = SessionSubscriptionRow(
        id = "sub-$sessionId", sessionId = sessionId, topicPatterns = topicPatterns,
        eventTypes = eventTypes, selfWake = selfWake,
    )

    @Test
    fun wakesASessionWhoseTopicAndTypeBothMatch() {
        val result = SchedulerMatcher.match(
            event = event(type = "GitCommit", topic = "git:master"),
            sourceSessionId = null,
            subscriptions = listOf(sub("driver-1", topicPatterns = listOf("git:*"), eventTypes = listOf("GitCommit"))),
        )
        assertEquals(listOf("driver-1"), result.woken)
        assertTrue(result.selfWakeRefused.isEmpty())
    }

    @Test
    fun doesNotWakeASessionWhoseTopicDoesNotMatch() {
        val result = SchedulerMatcher.match(
            event = event(topic = "git:master"),
            sourceSessionId = null,
            subscriptions = listOf(sub("s1", topicPatterns = listOf("filesystem:/project/**"))),
        )
        assertTrue(result.woken.isEmpty())
    }

    @Test
    fun doesNotWakeASessionWhoseEventTypeFilterExcludesIt() {
        val result = SchedulerMatcher.match(
            event = event(type = "GitCommit"),
            sourceSessionId = null,
            subscriptions = listOf(sub("s1", eventTypes = listOf("FileModified"))),
        )
        assertTrue(result.woken.isEmpty())
    }

    @Test
    fun refusesSelfWakeByDefault() {
        val result = SchedulerMatcher.match(
            event = event(),
            sourceSessionId = "driver-1",
            subscriptions = listOf(sub("driver-1", topicPatterns = listOf("git:*"))),
        )
        assertTrue(result.woken.isEmpty())
        assertEquals(listOf("driver-1"), result.selfWakeRefused)
    }

    @Test
    fun allowsSelfWakeWhenSubscriptionOptsIn() {
        val result = SchedulerMatcher.match(
            event = event(),
            sourceSessionId = "driver-1",
            subscriptions = listOf(sub("driver-1", topicPatterns = listOf("git:*"), selfWake = true)),
        )
        assertEquals(listOf("driver-1"), result.woken)
        assertTrue(result.selfWakeRefused.isEmpty())
    }

    @Test
    fun theLoadBearingCaseADriverWakingWhenItsWorkerCompletes() {
        // The driver did not source the event (its worker did), so self-wake refusal never applies.
        val result = SchedulerMatcher.match(
            event = event(type = "RunCompleted", topic = "session:worker-1"),
            sourceSessionId = "worker-1",
            subscriptions = listOf(sub("driver-1", topicPatterns = listOf("session:worker-1"), eventTypes = listOf("RunCompleted"))),
        )
        assertEquals(listOf("driver-1"), result.woken)
    }

    @Test
    fun nullSourceSessionIdNeverTriggersSelfWakeRefusal() {
        val result = SchedulerMatcher.match(
            event = event(),
            sourceSessionId = null,
            subscriptions = listOf(sub("driver-1", topicPatterns = listOf("git:*"))),
        )
        assertEquals(listOf("driver-1"), result.woken)
        assertTrue(result.selfWakeRefused.isEmpty())
    }

    @Test
    fun multipleSubscriptionsResolveIndependently() {
        val result = SchedulerMatcher.match(
            event = event(type = "GitCommit", topic = "git:master"),
            sourceSessionId = "author-session",
            subscriptions = listOf(
                sub("author-session", topicPatterns = listOf("git:*"), eventTypes = listOf("GitCommit")),
                sub("observer-session", topicPatterns = listOf("git:*"), eventTypes = listOf("GitCommit")),
            ),
        )
        assertEquals(listOf("observer-session"), result.woken)
        assertEquals(listOf("author-session"), result.selfWakeRefused)
    }
}
