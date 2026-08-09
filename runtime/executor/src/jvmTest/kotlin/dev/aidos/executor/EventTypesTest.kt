package dev.aidos.executor

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards the exact spelling of RFC-0004's MVP-scoped event type names — a typo here would break
 * [SchedulerMatcher]/[SubscriptionRegistry] event-type matching silently, since both compare
 * plain strings.
 */
class EventTypesTest {

    @Test
    fun namesMatchRfc0004sMvpEventTypesListVerbatim() {
        assertEquals("UserCommand", EventTypes.USER_COMMAND)
        assertEquals("TimerFired", EventTypes.TIMER_FIRED)
        assertEquals("FileModified", EventTypes.FILE_MODIFIED)
        assertEquals("FileCreated", EventTypes.FILE_CREATED)
        assertEquals("FileDeleted", EventTypes.FILE_DELETED)
        assertEquals("GitCommit", EventTypes.GIT_COMMIT)
        assertEquals("ToolCompleted", EventTypes.TOOL_COMPLETED)
        assertEquals("PermissionRequested", EventTypes.PERMISSION_REQUESTED)
        assertEquals("PermissionGranted", EventTypes.PERMISSION_GRANTED)
        assertEquals("PermissionDenied", EventTypes.PERMISSION_DENIED)
        assertEquals("SessionWoken", EventTypes.SESSION_WOKEN)
        assertEquals("SessionSleeping", EventTypes.SESSION_SLEEPING)
        assertEquals("ArtifactCreated", EventTypes.ARTIFACT_CREATED)
        assertEquals("Error", EventTypes.ERROR)
    }

    @Test
    fun allFourteenMvpTypeNamesAreDistinct() {
        val names = listOf(
            EventTypes.USER_COMMAND, EventTypes.TIMER_FIRED, EventTypes.FILE_MODIFIED,
            EventTypes.FILE_CREATED, EventTypes.FILE_DELETED, EventTypes.GIT_COMMIT,
            EventTypes.TOOL_COMPLETED, EventTypes.PERMISSION_REQUESTED, EventTypes.PERMISSION_GRANTED,
            EventTypes.PERMISSION_DENIED, EventTypes.SESSION_WOKEN, EventTypes.SESSION_SLEEPING,
            EventTypes.ARTIFACT_CREATED, EventTypes.ERROR,
        )
        assertEquals(14, names.size)
        assertEquals(14, names.toSet().size)
    }
}
