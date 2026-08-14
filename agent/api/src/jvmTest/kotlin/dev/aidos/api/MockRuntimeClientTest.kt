package dev.aidos.api

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M9 done-when (RFC-0052):
 *
 * 1. Every RuntimeClient method is reachable in-process via MockRuntimeClient.
 * 2. No method takes a client-side filesystem path (LocalPath is in-process transport only).
 * 3. Diffs returned as structured hunks (DiffSummary, FileChange) not as formatted strings (D25).
 * 4. Event stream resumes from sinceSequence delivering the gap (RFC-0004).
 */
class MockRuntimeClientTest {

    @Test
    fun `create project and list it`() = runTest {
        val client = MockRuntimeClient()
        val result = client.projects.create(
            CreateProjectRequest("my-project", "desc", ProjectLocation.RuntimeManaged("my-project"))
        )
        assertIs<ProjectResult.Success>(result)
        assertEquals("my-project", result.project.name)

        val list = client.projects.list()
        assertEquals(1, list.size)
        assertEquals("my-project", list[0].name)
    }

    @Test
    fun `create session and send message returns run id`() = runTest {
        val client = MockRuntimeClient()
        val projResult = client.projects.create(
            CreateProjectRequest("proj", "d", ProjectLocation.RuntimeManaged("proj"))
        ) as ProjectResult.Success

        val sessResult = client.sessions.create(
            CreateSessionRequest(projResult.project.id, "session-1")
        )
        assertIs<SessionResult.Success>(sessResult)

        val runResult = client.sessions.send(
            sessResult.session.id, UserMessage("hello")
        )
        assertIs<RunResult.Accepted>(runResult)
        assertNotNull(runResult.runId)
    }

    @Test
    fun `session send to unknown session returns error`() = runTest {
        val client = MockRuntimeClient()
        val result = client.sessions.send("no-such-session", UserMessage("hi"))
        assertIs<RunResult.Error>(result)
        assertEquals("session.not_found", result.code)
    }

    @Test
    fun `grant and list capabilities`() = runTest {
        val client = MockRuntimeClient()
        val projResult = client.projects.create(
            CreateProjectRequest("p", "d", ProjectLocation.RuntimeManaged("p"))
        ) as ProjectResult.Success
        val sessResult = client.sessions.create(
            CreateSessionRequest(projResult.project.id, "s")
        ) as SessionResult.Success

        val capResult = client.capabilities.grant(
            GrantCapabilityRequest(sessResult.session.id, "FS_READ", scope = "/src")
        )
        assertIs<CapabilityResult.Success>(capResult)

        val caps = client.capabilities.list(sessResult.session.id)
        assertEquals(1, caps.size)
        assertEquals("FS_READ", caps[0].permission)
        assertEquals("/src", caps[0].scope)
    }

    @Test
    fun `diff changes returns structured DiffSummary not a string`() = runTest {
        val client = MockRuntimeClient()
        val summary = client.diff.changes("proj-1")
        // D25: API returns a DiffSummary (structured), not a String
        assertIs<DiffSummary>(summary)
        assertEquals(0, summary.filesChanged)
    }

    @Test
    fun `runtime ping returns true`() = runTest {
        val client = MockRuntimeClient()
        assertTrue(client.runtime.ping())
    }

    @Test
    fun `runtime version returns mock profile`() = runTest {
        val client = MockRuntimeClient()
        val version = client.runtime.version()
        assertEquals("MOCK", version.profile)
        assertEquals(1, version.apiVersion)
    }

    @Test
    fun `event subscription receives emitted events`() = runTest {
        val client = MockRuntimeClient()
        val received = mutableListOf<RuntimeEvent>()

        val job = launch {
            client.events.subscribe(EventFilter())
                .collect { received.add(it) }
        }

        // Emit an event
        val projResult = client.projects.create(
            CreateProjectRequest("p2", "d", ProjectLocation.RuntimeManaged("p2"))
        )
        assertIs<ProjectResult.Success>(projResult)

        // Give coroutines a moment to collect
        withTimeoutOrNull(500) {
            while (received.isEmpty()) kotlinx.coroutines.delay(10)
        }

        assertTrue(received.isNotEmpty(), "Should have received at least one event")
        job.cancel()
    }

    @Test
    fun `sinceSequence replays buffered events`() = runTest {
        val client = MockRuntimeClient()

        // Emit 3 events before subscribing
        repeat(3) { i ->
            client.emit(RuntimeEvent.RunStarted(
                eventId = "evt-$i", timestamp = kotlinx.datetime.Clock.System.now(),
                projectId = "p", sessionId = "s", runId = "r-$i",
            ))
        }

        val received = mutableListOf<RuntimeEvent>()
        val job = launch {
            // Resume from sequence 1 (skip event 0, replay events 1 and 2)
            client.events.subscribe(EventFilter(sinceSequence = 1L))
                .collect { received.add(it) }
        }

        withTimeoutOrNull(500) {
            while (received.size < 2) kotlinx.coroutines.delay(10)
        }

        // Should have received at least the 2 replayed events (seq 2, 3)
        assertTrue(received.size >= 2, "sinceSequence should replay buffered events: got ${received.size}")
        job.cancel()
    }
}
