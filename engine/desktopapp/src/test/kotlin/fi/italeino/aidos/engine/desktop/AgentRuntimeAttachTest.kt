package fi.italeino.aidos.engine.desktop

import dev.aidos.api.MockRuntimeClient
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertSame

class AgentRuntimeAttachTest {
    private val sock = Files.createTempDirectory("attach").resolve("runtime.sock")
    private val tok = sock.resolveSibling("runtime.token")

    @Test
    fun `a daemon that answers is attached to and the in-process runtime is never created`() {
        val daemon = MockRuntimeClient()
        var created = 0
        val c = AgentRuntimeAttach.connect(sock, tok, probe = { _, _ -> daemon }, inProcess = { created++; MockRuntimeClient() })
        assertIs<AgentConnection.Attached>(c)
        assertSame(daemon, c.client)
        assertEquals(0, created, "attached means no project database is opened in this process")
    }

    @Test
    fun `with no daemon the app falls back to its own in-process runtime`() {
        val own = MockRuntimeClient()
        val c = AgentRuntimeAttach.connect(sock, tok, probe = { _, _ -> null }, inProcess = { own })
        assertIs<AgentConnection.InProcess>(c)
        assertSame(own, c.client)
    }

    @Test
    fun `the real probe returns nothing, quickly, when there is no socket or no token`() {
        val t0 = System.currentTimeMillis()
        assertNull(AgentRuntimeAttach.probeDaemon(sock, tok))
        Files.createFile(sock.resolveSibling("runtime.token")) // token but no socket
        assertNull(AgentRuntimeAttach.probeDaemon(sock, tok))
        assert(System.currentTimeMillis() - t0 < 2_000)
    }

    @Test
    fun `the real probe returns nothing for a socket file nobody is listening on`() {
        Files.createFile(sock)
        Files.writeString(tok, "t")
        assertNull(AgentRuntimeAttach.probeDaemon(sock, tok))
    }
}
