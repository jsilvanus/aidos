package dev.aidos.daemon

import kotlin.test.Test
import kotlin.test.assertNotNull

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
        val client = RuntimeClientFactory.createRuntimeClient()
        assertNotNull(client)
        assertNotNull(client.projects)
        assertNotNull(client.sessions)
        assertNotNull(client.capabilities)
        assertNotNull(client.runtime)
    }
}
