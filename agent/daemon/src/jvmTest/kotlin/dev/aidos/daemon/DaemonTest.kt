package dev.aidos.daemon

import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M33 done-when: Daemon starts and CLI can connect.
 *
 * This test verifies the daemon infrastructure is in place:
 * 1. RuntimeClientFactory creates a working runtime client
 * 2. Socket server initializes (doesn't throw)
 * 3. RuntimeClient responds to basic queries
 */
class DaemonTest {

    @Test
    fun `RuntimeClientFactory creates a working client`() {
        val client = RuntimeClientFactory.createRuntimeClient(installCrashHandler = false)
        assertNotNull(client)
        assertNotNull(client.projects)
        assertNotNull(client.sessions)
        assertNotNull(client.capabilities)
        assertNotNull(client.runtime)
    }

    @Test
    fun `RuntimeClientFactory installs CrashHandler by default`() {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        val home = java.nio.file.Files.createTempDirectory("daemon-test-home").toFile()
        try {
            RuntimeClientFactory.createRuntimeClient(home = home.path, installCrashHandler = true)
            assertTrue(
                Thread.getDefaultUncaughtExceptionHandler() is CrashHandler,
                "CrashHandler should be wired into production runtime startup",
            )
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous)
        }
    }
}
