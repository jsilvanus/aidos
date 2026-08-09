package dev.aidos.executor

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * RFC-0004 MVP item 4: sessions and subsystems can subscribe to topic patterns and event types.
 */
class SubscriptionRegistryTest {

    @Test
    fun matchesOnTopicPatternAndEventType() {
        val registry = SubscriptionRegistry()
        registry.subscribe(
            Subscription(
                id = "sub-1",
                subscriberId = "session-1",
                topicPatterns = listOf("filesystem:/project/src/**"),
                eventTypes = listOf("FileModified"),
            )
        )

        val matches = registry.matchingSubscribers("filesystem:/project/src/main.rs", "FileModified")
        assertEquals(listOf("sub-1"), matches.map { it.id })
    }

    @Test
    fun topicOutsidePatternDoesNotMatch() {
        val registry = SubscriptionRegistry()
        registry.subscribe(
            Subscription(id = "sub-1", subscriberId = "session-1", topicPatterns = listOf("filesystem:/project/docs/**"))
        )

        assertTrue(registry.matchingSubscribers("filesystem:/project/src/main.rs", "FileModified").isEmpty())
    }

    @Test
    fun eventTypeOutsideFilterDoesNotMatch() {
        val registry = SubscriptionRegistry()
        registry.subscribe(
            Subscription(id = "sub-1", subscriberId = "session-1", eventTypes = listOf("GitCommit"))
        )

        assertTrue(registry.matchingSubscribers("git:master", "FileModified").isEmpty())
    }

    @Test
    fun emptyPatternsAndTypesMatchEverything() {
        val registry = SubscriptionRegistry()
        registry.subscribe(Subscription(id = "sub-1", subscriberId = "session-1"))

        assertEquals(1, registry.matchingSubscribers("anything:goes", "AnyType").size)
        assertEquals(1, registry.matchingSubscribers(null, "AnyType").size)
    }

    @Test
    fun unsubscribeRemovesTheSubscription() {
        val registry = SubscriptionRegistry()
        registry.subscribe(Subscription(id = "sub-1", subscriberId = "session-1"))
        registry.unsubscribe("sub-1")

        assertTrue(registry.all().isEmpty())
        assertTrue(registry.matchingSubscribers("git:master", "GitCommit").isEmpty())
    }

    @Test
    fun multipleSubscribersCanMatchTheSameEvent() {
        val registry = SubscriptionRegistry()
        registry.subscribe(Subscription(id = "sub-1", subscriberId = "session-1", topicPatterns = listOf("git:*")))
        registry.subscribe(Subscription(id = "sub-2", subscriberId = "session-2", topicPatterns = listOf("*")))

        val matches = registry.matchingSubscribers("git:master", "GitCommit").map { it.id }.toSet()
        assertEquals(setOf("sub-1", "sub-2"), matches)
    }
}
