package dev.aidos.daemon

import dev.aidos.cli.AidosCli
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.AfterTest
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M33 Integration: Daemon + CLI working together.
 *
 * This test demonstrates the foundation for frontends (CLI, web UI)
 * to communicate with the daemon via RuntimeClient interface (RFC-0052).
 *
 * The daemon provides a RuntimeClient implementation, which the CLI
 * uses to create projects, sessions, send messages, etc.
 *
 * Full socket transport is deferred to Phase 4.5; this verifies
 * the architecture is sound by using in-process RuntimeClient.
 */
class DaemonCliIntegrationTest {

    // RuntimeClientFactory now opens real storage under `home` (RFC-0010/RFC-0040) -- a temp
    // directory keeps this test from reading or writing ~/.aidos on whatever machine runs it.
    private val tempHome = Files.createTempDirectory("daemon-cli-test").toFile()

    @AfterTest
    fun cleanup() {
        tempHome.deleteRecursively()
    }

    @Test
    fun `daemon provides RuntimeClient that CLI can use`() = runTest {
        // Create daemon's runtime client
        val runtimeClient = RuntimeClientFactory.createRuntimeClient(home = tempHome.path)

        // CLI connects to daemon (in this test, directly to the in-process client)
        val cli = AidosCli(runtimeClient)

        // Basic workflow: create project → create session
        val projectId = cli.createProject("test-project", "Integration test")
        assertNotNull(projectId)
        assertTrue(projectId.isNotBlank())

        val sessionId = cli.createSession(projectId, "test-session")
        assertNotNull(sessionId)
        assertTrue(sessionId.isNotBlank())

        // This test verifies the architecture, not the full workflow.
        // Real integration tests would:
        // 1. Start daemon in a subprocess
        // 2. Connect CLI via socket
        // 3. Run full G3 workflow (project → session → models → tools)
        // See RFC-0052 for complete contract.
    }

    @Test
    fun `daemon can grant voice capabilities via CLI`() = runTest {
        val runtimeClient = RuntimeClientFactory.createRuntimeClient(home = tempHome.path)
        val cli = AidosCli(runtimeClient)

        // Create project and session
        val projectId = cli.createProject("voice-test", "")
        val sessionId = cli.createSession(projectId, "voice-session")

        // Grant voice capabilities (M33)
        val sttCapId = cli.grantCapability(sessionId, "STT_QUERY", null)
        assertNotNull(sttCapId)
        assertTrue(sttCapId.isNotBlank())

        val ttsCapId = cli.grantCapability(sessionId, "TTS_QUERY", null)
        assertNotNull(ttsCapId)
        assertTrue(ttsCapId.isNotBlank())

        // Session can now use voice input/output via daemon
        // Full implementation in Phase 4.5 socket transport
    }
}
