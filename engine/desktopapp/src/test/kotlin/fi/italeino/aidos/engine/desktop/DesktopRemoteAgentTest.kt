package fi.italeino.aidos.engine.desktop

import dev.aidos.aidin.NodeId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC-0107 desktop switches: nothing is on by default, and what the user sets survives a restart. */
class DesktopRemoteAgentTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val peer = NodeId("a".repeat(26))

    @Test
    fun `no peers, no tools and no hosting by default`() {
        val dir = Files.createTempDirectory("remote-agent").toFile()
        val ra = DesktopRemoteAgent(dir, aidin = { null }, scope = scope)
        assertTrue(ra.peerCandidates().isEmpty())
        assertTrue(ra.allowedTools(peer, "app").isEmpty())
        assertFalse(ra.isHostingFor(peer))
        assertFalse(ra.setHostingFor(peer, true), "no AIDIN node, nothing to grant")
    }

    @Test
    fun `peers and per-app tool allowlists persist and an empty allowlist removes the entry`() {
        val dir = Files.createTempDirectory("remote-agent").toFile()
        val a = DesktopRemoteAgent(dir, aidin = { null }, scope = scope)
        a.setPeerCandidates(listOf(peer))
        a.allowTools(peer, "dictator", setOf("search", "read_note"))
        val b = DesktopRemoteAgent(dir, aidin = { null }, scope = scope)
        assertEquals(listOf(peer), b.peerCandidates())
        assertEquals(setOf("search", "read_note"), b.allowedTools(peer, "dictator"))
        assertTrue(b.allowedTools(peer, "other-app").isEmpty(), "allowlists are per app")
        b.allowTools(peer, "dictator", emptySet())
        assertTrue(DesktopRemoteAgent(dir, aidin = { null }, scope = scope).allowedTools(peer, "dictator").isEmpty())
    }
}
