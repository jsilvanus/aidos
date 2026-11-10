package fi.italeino.sshtunnel

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.common.config.keys.PublicKeyEntry
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.server.SshServer
import org.apache.sshd.server.forward.AcceptAllForwardingFilter
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.PublicKey
import kotlin.concurrent.thread
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Real SSH, in process, on loopback: no external server (RFC-0108 test rule). */
class MinaIntegrationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val servers = mutableListOf<SshServer>()
    private val echo = mutableListOf<ServerSocket>()

    @AfterTest
    fun cleanup() {
        scope.cancel()
        servers.forEach { it.stop(true) }
        echo.forEach { it.close() }
    }

    private fun startServer(authorized: String?, forwarding: Boolean = true): Pair<SshServer, String> {
        val s = SshServer.setUpDefaultServer()
        s.port = 0
        val hostKeyFile = Files.createTempFile("hostkey", ".ser")
        s.keyPairProvider = SimpleGeneratorHostKeyProvider(hostKeyFile).apply { algorithm = "RSA"; keySize = 2048 }
        val allowed: PublicKey? = authorized?.let { PublicKeyEntry.parsePublicKeyEntry(it).resolvePublicKey(null, emptyMap(), null) }
        s.publickeyAuthenticator = org.apache.sshd.server.auth.pubkey.PublickeyAuthenticator { _, key, _ ->
            allowed != null && KeyUtils.compareKeys(allowed, key)
        }
        if (forwarding) s.forwardingFilter = AcceptAllForwardingFilter.INSTANCE
        s.start()
        servers += s
        val fp = KeyUtils.getFingerPrint(s.keyPairProvider.loadKeys(null).first().public)
        return s to fp
    }

    private fun startEcho(): Int {
        val ss = ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1")); echo += ss
        thread(isDaemon = true) {
            while (!ss.isClosed) runCatching {
                val c = ss.accept()
                thread(isDaemon = true) { c.use { it.getOutputStream().apply { write(it.getInputStream().read()); flush() } } }
            }
        }
        return ss.localPort
    }

    private fun tunnel(spec: TunnelSpec, key: ByteArray?, fp: String) =
        SshTunnel(spec, { key?.copyOf() }, HostKeyPolicy.pinned(fp), MinaTransport(), scope)

    @Test
    fun reverseTunnelCarriesBytesToTarget() = runBlocking {
        val gen = KeyGen.ed25519("aidos-tunnel-test")
        val (srv, fp) = startServer(gen.publicKey)
        val target = startEcho()
        val spec = TunnelSpec("127.0.0.1", "tun", Forward.Reverse(serverPort = 0, targetPort = target), port = srv.port)
        val t = tunnel(spec, gen.privateKey, fp)
        t.start()
        val up = withTimeout(20_000) { t.state.first { it is TunnelState.Up } } as TunnelState.Up
        Socket("127.0.0.1", up.boundPort).use { c ->
            c.getOutputStream().apply { write(42); flush() }
            assertEquals(42, c.getInputStream().read())
        }
        t.stop()
    }

    @Test
    fun wrongHostKeyIsTerminalAndNotTrusted() = runBlocking {
        val gen = KeyGen.ed25519("t")
        val (srv, _) = startServer(gen.publicKey)
        val spec = TunnelSpec("127.0.0.1", "tun", Forward.Reverse(0, 1), port = srv.port)
        val t = tunnel(spec, gen.privateKey, "SHA256:notthekey")
        t.start()
        val st = withTimeout(20_000) { t.state.first { it !is TunnelState.Connecting } }
        assertTrue(st is TunnelState.HostKeyChanged, "got $st")
        t.stop()
    }

    @Test
    fun unknownKeyIsAuthRefused() = runBlocking {
        val authorized = KeyGen.ed25519("a")
        val other = KeyGen.ed25519("b")
        val (srv, fp) = startServer(authorized.publicKey)
        val spec = TunnelSpec("127.0.0.1", "tun", Forward.Reverse(0, 1), port = srv.port)
        val t = tunnel(spec, other.privateKey, fp)
        t.start()
        val st = withTimeout(20_000) { t.state.first { it !is TunnelState.Connecting } }
        assertTrue(st is TunnelState.AuthRefused, "got $st")
        t.stop()
    }

    @Test
    fun forwardRefusedWhenServerForbidsIt() = runBlocking {
        val gen = KeyGen.ed25519("c")
        val (srv, fp) = startServer(gen.publicKey, forwarding = false)
        val spec = TunnelSpec("127.0.0.1", "tun", Forward.Reverse(0, 1), port = srv.port)
        val t = tunnel(spec, gen.privateKey, fp)
        t.start()
        val st = withTimeout(20_000) { t.state.first { it !is TunnelState.Connecting } }
        assertTrue(st is TunnelState.ForwardRefused, "got $st")
        t.stop()
    }

    @Test
    fun reconnectsAfterServerDrops() = runBlocking {
        val gen = KeyGen.ed25519("d")
        val (srv, fp) = startServer(gen.publicKey)
        val port = srv.port
        val target = startEcho()
        val spec = TunnelSpec(
            "127.0.0.1", "tun", Forward.Reverse(0, target), port = port, backoff = Backoff(200, 400),
        )
        val t = tunnel(spec, gen.privateKey, fp)
        t.start()
        withTimeout(20_000) { t.state.first { it is TunnelState.Up } }
        srv.activeSessions.forEach { it.close(true) }
        withTimeout(20_000) { t.state.first { it is TunnelState.Backoff } }
        val again = withTimeout(20_000) { t.state.first { it is TunnelState.Up } } as TunnelState.Up
        Socket("127.0.0.1", again.boundPort).use { c ->
            c.getOutputStream().apply { write(7); flush() }
            assertEquals(7, c.getInputStream().read())
        }
        t.stop()
    }

    @Test
    fun generatedKeyHasAuthorizedLineShape() {
        val g = KeyGen.ed25519("aidos-tunnel-xyz")
        assertTrue(g.publicKey.startsWith("ssh-ed25519 "))
        assertTrue(g.publicKey.endsWith(" aidos-tunnel-xyz"))
        assertTrue(String(g.privateKey).startsWith("-----BEGIN OPENSSH PRIVATE KEY-----"))
        val line = AuthorizedKeys.forReverse(g.publicKey, Forward.Reverse(47100, 47100))
        assertTrue(line.startsWith("restrict,port-forwarding,permitlisten="))
    }
}
