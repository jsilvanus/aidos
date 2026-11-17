package fi.italeino.aidos.engine.desktop

import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.FileAidinStateStore
import dev.aidos.aidin.FileNodeKeyStore
import dev.aidos.aidin.PeerTracker
import dev.aidos.tcr.engine.TunnelStatusUi
import dev.aidos.vault.DesktopVault
import fi.italeino.sshtunnel.Forward
import fi.italeino.sshtunnel.HostKeyPolicy
import fi.italeino.sshtunnel.SshSession
import fi.italeino.sshtunnel.SshTransport
import fi.italeino.sshtunnel.TunnelException
import fi.italeino.sshtunnel.TunnelState
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** An SSH server that only presents a host key and, when the key is accepted, a forward that stays up. */
private class FakeServerTransport(var hostKey: String = "SHA256:serverKeyOne") : SshTransport {
    val connects = java.util.concurrent.atomic.AtomicInteger()
    override suspend fun connect(spec: fi.italeino.sshtunnel.TunnelSpec, privateKey: ByteArray, hostKeys: HostKeyPolicy): SshSession {
        connects.incrementAndGet()
        if (!hostKeys.accept(spec.host, spec.port, hostKey)) throw TunnelException.HostKeyRejected(hostKey)
        val closed = CompletableDeferred<Unit>()
        return object : SshSession {
            override val boundPort = (spec.forward as Forward.Reverse).serverPort
            override suspend fun awaitClosed() { closed.await() }
            override fun close() { closed.complete(Unit) }
        }
    }
}

/** RFC-0108 phase 3 on the desktop: keys in the vault, host keys confirmed not trusted, tunnels aimed at the remote ingress. */
class DesktopTunnelsTest {
    private val root = Files.createTempDirectory("aidos-tunnels").toFile()
    private val home = Files.createTempDirectory("aidos-tunnels-home").toFile()
    private val transport = FakeServerTransport()
    private val host = DesktopEngineHost(
        modelsDir = root.resolve("models").apply { mkdirs() },
        stateDir = root,
        serverPort = { 0 },
        discoveryFile = root.resolve("engine.json"),
        groupSharing = { true },
        aidinFactory = { s ->
            AidinNode(FileNodeKeyStore(root.resolve("aidin/node-key")).loadOrCreate(), FileAidinStateStore(root.resolve("aidin/state.json")), s, "test",
                tracker = PeerTracker(heartbeatIntervalMillis = 100, leaveAfterMillis = 2_000))
        },
        aidinPort = 0,
        tunnelVault = { DesktopVault.open(home = home.path, passphrase = { "test-passphrase".toCharArray() }).vault },
        tunnelTransport = transport,
    )

    @AfterTest
    fun tearDown() {
        runBlocking { host.close() }
        root.deleteRecursively()
        home.deleteRecursively()
    }

    private fun eventually(what: String, check: () -> Boolean) {
        // CI runners are slow under load; the 10 s bound flaked on CI (run 2 of the head commit).
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun tunnels(): DesktopTunnels {
        runBlocking { host.start() }
        assertNotNull(host.aidin.value, "aidin failed: ${host.aidinError.value}")
        return assertNotNull(host.tunnels)
    }

    @Test
    fun `create makes a vault-held key and a restricted authorized_keys line aimed at the remote ingress`() {
        val t = tunnels()
        val id = t.create("home", "server.example.org", 22, "tunnel", 47100)
        val view = t.views().single { it.id == id }
        val node = host.aidin.value!!
        val ingress = assertNotNull(node.remoteIngressPort, "creating a tunnel starts the remote-ingress listener")

        val line = assertNotNull(view.authorizedKeysLine)
        assertTrue(line.startsWith("restrict,port-forwarding,permitlisten=\"127.0.0.1:47100\",command=\"/bin/false\" ssh-ed25519 "))
        assertEquals(TunnelState.Stopped, view.state)
        assertNull(view.pinnedHostKey)
        assertTrue(!java.io.File(root, "tunnels.json").readText().contains("PRIVATE KEY"), "no private key in the config")
        assertTrue(ingress > 0)
    }

    @Test
    fun `confirming the host key is the egress grant for exactly that host and port, and is audited`() {
        val t = tunnels()
        val id = t.create("home", "server.example.org", 22, "tunnel", 47100)
        assertNull(t.views().single().egressGrantedTo, "nothing is granted before the user confirms")
        t.probeHostKey(id)
        assertNull(t.views().single().egressGrantedTo, "probing alone does not grant")
        t.confirmHostKey(id)
        assertEquals("server.example.org:22", t.views().single().egressGrantedTo)
        assertTrue(java.io.File(root, "tunnels-audit.log").readText().contains("egress granted to server.example.org:22"))
    }

    @Test
    fun `a tunnel cannot start until the host key the server showed is confirmed`() {
        val t = tunnels()
        val id = t.create("home", "server.example.org", 22, "tunnel", 47100)
        assertFailsWith<IllegalStateException> { t.start(id) }
        assertEquals(0, transport.connects.get(), "nothing was dialled before the host key was pinned")
        assertFailsWith<IllegalStateException> { t.confirmHostKey(id) } // nothing to confirm yet

        assertEquals("SHA256:serverKeyOne", t.probeHostKey(id))
        assertEquals("SHA256:serverKeyOne", t.views().single().pendingHostKey)
        t.confirmHostKey(id)
        assertEquals("SHA256:serverKeyOne", t.views().single().pinnedHostKey)

        t.start(id)
        eventually("tunnel up") { t.views().single().state is TunnelState.Up }
        assertEquals(47100, (t.views().single().state as TunnelState.Up).boundPort)
        t.stop(id)
        eventually("tunnel stopped") { t.views().single().state == TunnelState.Stopped }
    }

    @Test
    fun `a changed host key stops the tunnel instead of being trusted`() {
        val t = tunnels()
        val id = t.create("home", "server.example.org", 22, "tunnel", 47100)
        t.probeHostKey(id)
        t.confirmHostKey(id)
        transport.hostKey = "SHA256:someoneElse"
        t.start(id)
        eventually("host key changed") { t.views().single().state is TunnelState.HostKeyChanged }
        assertEquals("SHA256:serverKeyOne", t.views().single().pinnedHostKey, "the pin is not overwritten")
    }

    @Test
    fun `rotating the key changes the line to paste, and deleting removes the tunnel`() {
        val t = tunnels()
        val id = t.create("home", "server.example.org", 22, "tunnel", 47100)
        val before = t.views().single().authorizedKeysLine
        val after = t.rotateKey(id)
        assertNotEquals(before, after)
        assertEquals(after, t.views().single().authorizedKeysLine)
        t.delete(id)
        assertTrue(t.views().isEmpty())
    }

    @Test
    fun `tunnels and their pinned host keys survive a restart`() {
        val t = tunnels()
        val id = t.create("home", "server.example.org", 22, "tunnel", 47100)
        t.probeHostKey(id)
        t.confirmHostKey(id)
        val line = t.views().single().authorizedKeysLine

        val again = DesktopTunnels(root, node = { host.aidin.value }, scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            vaultProvider = { DesktopVault.open(home = home.path, passphrase = { "test-passphrase".toCharArray() }).vault }, transport = transport)
        val view = again.views().single()
        assertEquals(id, view.id)
        assertEquals("SHA256:serverKeyOne", view.pinnedHostKey)
        assertEquals(line, view.authorizedKeysLine, "the line can be shown again after a restart")
    }

    @Test
    fun `a tunnel needs group sharing on, because that is the listener it publishes`() {
        // The host has no AIDIN node until group sharing starts.
        val dir = Files.createTempDirectory("aidos-tunnels-off").toFile()
        val t = DesktopTunnels(dir, node = { null }, scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Default),
            vaultProvider = { error("the vault is not opened for this") }, transport = transport)
        assertFailsWith<IllegalStateException> { t.create("home", "s.example.org", 22, "u", 47100) }
        dir.deleteRecursively()
    }

    @Test
    fun `the Tunnels tab walks create, check, confirm and start through to up`() {
        runBlocking { host.start() }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val model = DesktopTunnelsModel(scope, pollMillis = 50) { fi.italeino.aidos.engine.desktop.LocalTunnelsControl(host) }
            eventually("can publish") { model.state.value.canPublish }
            model.create("home", "server.example.org", 22, "tunnel", 47100)
            eventually("listed, needs host key") { model.state.value.tunnels.singleOrNull()?.status == TunnelStatusUi.NEEDS_HOST_KEY }
            val id = model.state.value.tunnels.single().id
            model.probeHostKey(id)
            eventually("fingerprint shown") { model.state.value.tunnels.single().pendingHostKey == "SHA256:serverKeyOne" }
            model.confirmHostKey(id)
            eventually("pinned") { model.state.value.tunnels.single().pinnedHostKey != null }
            model.start(id)
            eventually("up") { model.state.value.tunnels.single().status == TunnelStatusUi.UP }
            model.start("no-such-tunnel")
            eventually("error shown") { model.state.value.error != null }
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `the same walk works through the loopback admin API of a running Engine`() {
        runBlocking { host.start() }
        val running = checkNotNull(EngineAttach.runningEngine(root.resolve("engine.json"), selfPid = -1)) { "engine.json not written" }
        val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
        try {
            val model = DesktopTunnelsModel(scope, pollMillis = 50) { EngineAttach.tunnelsControl(host, running) }
            eventually("can publish") { model.state.value.canPublish }
            model.create("home", "server.example.org", 22, "tunnel", 47100)
            eventually("listed") { model.state.value.tunnels.singleOrNull()?.status == TunnelStatusUi.NEEDS_HOST_KEY }
            val id = model.state.value.tunnels.single().id
            model.probeHostKey(id)
            eventually("fingerprint shown") { model.state.value.tunnels.single().pendingHostKey == "SHA256:serverKeyOne" }
            model.confirmHostKey(id)
            eventually("pinned") { model.state.value.tunnels.single().pinnedHostKey != null }
            model.start(id)
            eventually("up") { model.state.value.tunnels.single().status == TunnelStatusUi.UP }
            model.start("no-such-tunnel")
            eventually("the Engine's message reaches the pane") { model.state.value.error?.contains("no such tunnel") == true }
            model.delete(id)
            eventually("deleted") { model.state.value.tunnels.isEmpty() }
        } finally {
            scope.cancel()
        }
    }
}
