package fi.italeino.sshtunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.DisconnectReason
import net.schmizz.sshj.connection.ConnectionException
import net.schmizz.sshj.connection.channel.direct.Parameters
import net.schmizz.sshj.connection.channel.forwarded.RemotePortForwarder
import net.schmizz.sshj.connection.channel.forwarded.SocketForwardingConnectListener
import net.schmizz.sshj.transport.DisconnectListener
import net.schmizz.sshj.transport.verification.HostKeyVerifier
import net.schmizz.sshj.userauth.UserAuthException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.security.MessageDigest
import java.security.PublicKey
import java.util.Base64
import kotlin.concurrent.thread

/**
 * [SshTransport] on sshj. Pure Java, so the same class runs on the desktop JVM and in an Android
 * app (RFC-0108 phase 4). On Android the app must make sure a full Bouncy Castle is on the
 * classpath (Android ships a cut-down "BC" provider that sshj cannot use); see the module README.
 *
 * Reverse forwards only ever connect to the one configured target. A local forward cannot learn
 * up front that the server refuses it (`permitopen` is checked per connection by the server), so
 * [TunnelState.ForwardRefused] is reported only for reverse forwards.
 */
class SshjTransport : SshTransport {
    override suspend fun connect(spec: TunnelSpec, privateKey: ByteArray, hostKeys: HostKeyPolicy): SshSession =
        withContext(Dispatchers.IO) { open(spec, privateKey, hostKeys) }

    private fun open(spec: TunnelSpec, privateKey: ByteArray, hostKeys: HostKeyPolicy): SshSession {
        val client = SSHClient(DefaultConfig())
        var rejected: String? = null
        client.addHostKeyVerifier(object : HostKeyVerifier {
            override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
                val fp = fingerprint(key)
                val ok = hostKeys.accept(spec.host, spec.port, fp)
                if (!ok) rejected = fp
                return ok
            }
            override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()
        })
        client.connectTimeout = spec.connectTimeoutMillis.toInt()
        client.timeout = spec.connectTimeoutMillis.toInt()
        var listener: ServerSocket? = null
        try {
            try {
                client.connect(spec.host, spec.port)
            } catch (e: IOException) {
                rejected?.let { throw TunnelException.HostKeyRejected(it) }
                throw TunnelException.Network("connect failed: ${e.message}", e)
            }
            // Dead paths must surface as a closed session, not a silent hang.
            client.connection.keepAlive.keepAliveInterval = spec.keepAliveSeconds

            val keys = try {
                client.loadKeys(String(privateKey), null, null)
            } catch (e: Exception) {
                throw TunnelException.AuthFailed("unusable private key: ${e.message}", e)
            }
            try {
                client.authPublickey(spec.user, keys)
            } catch (e: UserAuthException) {
                throw TunnelException.AuthFailed("server refused the key: ${e.message}", e)
            } catch (e: IOException) {
                throw TunnelException.Network("connection lost during auth: ${e.message}", e)
            }

            val closed = CompletableDeferred<Unit>()
            client.transport.disconnectListener = DisconnectListener { _: DisconnectReason, _: String -> closed.complete(Unit) }

            val port: Int = when (val f = spec.forward) {
                is Forward.Reverse -> try {
                    val bound = client.remotePortForwarder.bind(
                        RemotePortForwarder.Forward(f.serverBindHost, f.serverPort),
                        SocketForwardingConnectListener(InetSocketAddress(f.targetHost, f.targetPort)),
                    )
                    bound.port
                } catch (e: ConnectionException) {
                    throw TunnelException.ForwardRefused("server refused the forward: ${e.message}", e)
                } catch (e: IOException) {
                    throw TunnelException.Network("connection lost: ${e.message}", e)
                }
                is Forward.Local -> {
                    val ss = ServerSocket()
                    ss.reuseAddress = true
                    ss.bind(InetSocketAddress(f.localBindHost, f.localPort))
                    listener = ss
                    val params = Parameters(f.localBindHost, ss.localPort, f.remoteHost, f.remotePort)
                    thread(isDaemon = true, name = "ssh-tunnel-local-listen") {
                        try { client.newLocalPortForwarder(params, ss).listen() } catch (_: IOException) { /* closed */ }
                        closed.complete(Unit)
                    }
                    ss.localPort
                }
            }
            val owned = listener
            return object : SshSession {
                override val boundPort = port
                override suspend fun awaitClosed() = closed.await()
                override fun close() {
                    runCatching { owned?.close() }
                    runCatching { client.disconnect() }
                    closed.complete(Unit)
                }
            }
        } catch (e: Exception) {
            runCatching { listener?.close() }
            runCatching { client.close() }
            throw e
        }
    }

    /** `SHA256:<base64 no padding>` over the SSH wire encoding, same format as OpenSSH prints. */
    private fun fingerprint(key: PublicKey): String {
        val blob = Buffer.PlainBuffer().putPublicKey(key).compactData
        val d = MessageDigest.getInstance("SHA-256").digest(blob)
        return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(d)
    }
}
