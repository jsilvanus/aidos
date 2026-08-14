package dev.aidos.executor

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Verifies [TopicMatcher] against RFC-0004's own worked examples ("Topics and Filtering").
 */
class TopicMatcherTest {

    @Test
    fun singleWildcardMatchesOneSegmentDirectlyUnderneath() {
        val pattern = "filesystem:/project/src/*"
        assertTrue(TopicMatcher.matches(pattern, "filesystem:/project/src/main.rs"))
    }

    @Test
    fun singleWildcardDoesNotCrossASegmentBoundary() {
        val pattern = "filesystem:/project/src/*"
        assertFalse(TopicMatcher.matches(pattern, "filesystem:/project/src/nested/main.rs"))
    }

    @Test
    fun doubleWildcardMatchesAnyDepth() {
        val pattern = "filesystem:/project/**"
        assertTrue(TopicMatcher.matches(pattern, "filesystem:/project/src/main.rs"))
        assertTrue(TopicMatcher.matches(pattern, "filesystem:/project/src/nested/deep/main.rs"))
        assertTrue(TopicMatcher.matches(pattern, "filesystem:/project/README.md"))
    }

    @Test
    fun namespaceWildcardMatchesWithinTheNamespace() {
        assertTrue(TopicMatcher.matches("git:*", "git:master"))
        assertFalse(TopicMatcher.matches("git:*", "session:sess-123"))
    }

    @Test
    fun loneWildcardMatchesAllEvents() {
        assertTrue(TopicMatcher.matches("*", "filesystem:/project/src/main.rs"))
        assertTrue(TopicMatcher.matches("*", "git:master"))
        assertTrue(TopicMatcher.matches("*", null))
    }

    @Test
    fun exactTopicMatchesOnlyItself() {
        assertTrue(TopicMatcher.matches("session:sess-123", "session:sess-123"))
        assertFalse(TopicMatcher.matches("session:sess-123", "session:sess-456"))
    }

    @Test
    fun nullTopicMatchesNothingButTheLoneWildcard() {
        assertFalse(TopicMatcher.matches("git:*", null))
        assertFalse(TopicMatcher.matches("session:sess-123", null))
    }

    @Test
    fun matchesAnyIsTrueWhenPatternListIsEmpty() {
        assertTrue(TopicMatcher.matchesAny(emptyList(), "git:master"))
    }

    @Test
    fun matchesAnyRequiresAtLeastOnePatternToMatch() {
        assertTrue(TopicMatcher.matchesAny(listOf("session:*", "git:*"), "git:master"))
        assertFalse(TopicMatcher.matchesAny(listOf("session:*", "tool:*"), "git:master"))
    }
}
