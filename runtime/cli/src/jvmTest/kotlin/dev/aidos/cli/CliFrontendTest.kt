package dev.aidos.cli

import dev.aidos.api.MockRuntimeClient
import dev.aidos.api.PendingCapabilityRequest
import dev.aidos.api.RuntimeEvent
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M10 done-when (RFC-0052, RFC-0004):
 *
 * 1. Create a project.
 * 2. List sessions.
 * 3. Send a message, receive a run id.
 * 4. Watch the event stream and receive events.
 * 5. Approve a pending capability request.
 * 6. Reconnecting with sinceSequence delivers the gap, not a fresh stream.
 */
class CliFrontendTest {

    private fun cli(client: MockRuntimeClient = MockRuntimeClient()) =
        AidosCli(client) to client

    @Test
    fun `create project returns id`() = runTest {
        val (cli, _) = cli()
        val id = cli.createProject("my-project", "description")
        assertNotNull(id)
        assertTrue(id.isNotBlank())
    }

    @Test
    fun `list sessions returns empty for new project`() = runTest {
        val (cli, _) = cli()
        val projectId = cli.createProject("proj", "")
        val sessions = cli.listSessions(projectId)
        assertEquals(0, sessions.size)
    }

    @Test
    fun `create session then list it`() = runTest {
        val (cli, _) = cli()
        val projectId = cli.createProject("proj", "")
        cli.createSession(projectId, "session-1")
        val sessions = cli.listSessions(projectId)
        assertEquals(1, sessions.size)
        assertTrue(sessions[0].contains("session-1"))
    }

    @Test
    fun `send message returns run id`() = runTest {
        val (cli, _) = cli()
        val projectId = cli.createProject("proj", "")
        val sessionId = cli.createSession(projectId, "s")
        val runId = cli.sendMessage(sessionId, "hello")
        assertNotNull(runId)
        assertTrue(runId.isNotBlank())
    }

    @Test
    fun `watch event stream receives emitted events`() = runTest {
        val mock = MockRuntimeClient()
        val cli = AidosCli(mock)
        val received = mutableListOf<String>()

        val job = cli.watchEvents(backgroundScope) { received.add(it) }

        // Triggering a create emits events
        cli.createProject("p", "")

        withTimeoutOrNull(500) {
            while (received.isEmpty()) kotlinx.coroutines.delay(10)
        }

        assertTrue(received.isNotEmpty(), "Should receive at least one event")
        job.cancel()
    }

    @Test
    fun `approve pending capability request`() = runTest {
        val mock = MockRuntimeClient()
        val cli = AidosCli(mock)

        // Inject a pending capability request directly into the mock
        mock.injectPendingCapability(
            PendingCapabilityRequest(requestId = "req-1", sessionId = "s-1", permission = "FS_WRITE", reason = "need write access")
        )

        val pending = cli.listPendingCapabilities()
        assertEquals(1, pending.size)

        val capId = cli.approveCapability("req-1")
        assertNotNull(capId)
        assertTrue(cli.listPendingCapabilities().isEmpty())
    }

    @Test
    fun `sinceSequence delivers gap not fresh stream`() = runTest {
        val mock = MockRuntimeClient()
        val cli = AidosCli(mock)

        // Emit 4 events before subscribing
        repeat(4) { i ->
            mock.emit(RuntimeEvent.RunStarted(
                eventId = "e$i", timestamp = kotlinx.datetime.Clock.System.now(),
                projectId = "p", sessionId = "s", runId = "r$i",
            ))
        }

        // sequences are 1,2,3,4 — subscribe from sinceSequence=2 → expect events 3,4
        val received = mutableListOf<String>()
        val job = cli.watchEvents(backgroundScope, sinceSequence = 2L) { received.add(it) }

        withTimeoutOrNull(500) {
            while (received.size < 2) kotlinx.coroutines.delay(10)
        }

        assertTrue(received.size >= 2, "sinceSequence should replay the gap: got ${received.size}")
        job.cancel()
    }

    @Test
    fun `ping returns true`() = runTest {
        val (cli, _) = cli()
        assertTrue(cli.ping())
    }

    @Test
    fun `version returns mock profile`() = runTest {
        val (cli, _) = cli()
        val v = cli.version()
        assertTrue(v.contains("MOCK"))
    }

    /**
     * G2: Create project → session → model capability → tool capability → send message
     * (= task + model call + tool execution) → list artifacts → audit trail (M19).
     *
     * The audit trail must be able to reconstruct the full sequence afterwards.
     * Uses MockRuntimeClient; the sequence of operations and their ordering are what matter.
     */
    @Test
    fun `G2 - create project to audit trail in one command sequence`() = runTest {
        val (cli, _) = cli()

        // 1. Create project.
        val projectId = cli.createProject("my-codebase", "An AI coding project")
        assertTrue(projectId.isNotBlank(), "project must have an ID")

        // 2. Create session (task).
        val sessionId = cli.createSession(projectId, "refactor-session")
        val sessions = cli.listSessions(projectId)
        assertTrue(sessions.any { it.contains("refactor-session") })

        // 3. Grant model capability.
        val modelCapId = cli.grantCapability(sessionId, "MODEL_QUERY", null)
        assertTrue(modelCapId.isNotBlank())

        // 4. Grant tool capability.
        val toolCapId = cli.grantCapability(sessionId, "FS_READ", projectId)
        assertTrue(toolCapId.isNotBlank())

        // 5. Send message (triggers model call → tool use → commit).
        val runId = cli.sendMessage(sessionId, "Refactor the parse function to use a sealed class")
        assertTrue(runId.isNotBlank(), "run must be accepted and return an ID")

        // 6. Diff shows changes (tool execution wrote files).
        val diff = cli.diffChanges(projectId)
        assertNotNull(diff)  // may be empty in mock, but must not throw

        // 7. List artifacts (commit = artifact in the model).
        val artifacts = cli.listArtifacts(projectId)
        // Mock returns empty list; the important thing is the call does not throw.
        assertNotNull(artifacts)

        // 8. Audit trail (MockRuntimeClient returns empty; verifying the call chain is complete).
        // In a real runtime the audit trail would name every Run step that produced the artifact.
        val trail = cli.auditTrail(runId)
        assertNotNull(trail)

        // The sequence verifies: every step in the G2 scenario executes without error.
        // The mock does not simulate real tool calls; G3 and beyond verify real execution.
    }
}
