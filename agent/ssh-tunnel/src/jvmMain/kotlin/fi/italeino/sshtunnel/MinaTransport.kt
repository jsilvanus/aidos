package fi.italeino.sshtunnel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.sshd.client.SshClient
import org.apache.sshd.client.session.ClientSession
import org.apache.sshd.common.NamedResource
import org.apache.sshd.common.config.keys.KeyUtils
import org.apache.sshd.server.forward.ForwardingFilter
import org.apache.sshd.common.session.Session
import org.apache.sshd.common.session.SessionListener
import org.apache.sshd.common.util.net.SshdSocketAddress
import org.apache.sshd.common.util.security.SecurityUtils
import org.apache.sshd.core.CoreModuleProperties
import java.io.ByteArrayInputStream
import java.io.IOException
import java.security.PublicKey
import java.time.Duration
import java.util.concurrent.TimeUnit

/** JVM [SshTransport] on Apache MINA SSHD. */
class MinaTransport : SshTransport {
    override suspend fun connect(spec: TunnelSpec, privateKey: ByteArray, hostKeys: HostKeyPolicy): SshSession =
        withContext(Dispatchers.IO) { open(spec, privateKey, hostKeys) }

    private fun open(spec: TunnelSpec, privateKey: ByteArray, hostKeys: HostKeyPolicy): SshSession {
        val client = SshClient.setUpDefaultClient()
        var rejected: String? = null
        client.serverKeyVerifier = org.apache.sshd.client.keyverifier.ServerKeyVerifier { _, _, key: PublicKey ->
            val fp = KeyUtils.getFingerPrint(key)
            val ok = hostKeys.accept(spec.host, spec.port, fp)
            if (!ok) rejected = fp
            ok
        }
        // Defense in depth: even if a server asks, only carry traffic to the one configured target.
        client.forwardingFilter = TargetOnlyFilter(spec.forward)
        // Keepalive: a dead network path must surface as a closed session, not a silent hang.
        CoreModuleProperties.HEARTBEAT_INTERVAL.set(client, Duration.ofSeconds(spec.keepAliveSeconds.toLong()))
        CoreModuleProperties.HEARTBEAT_NO_REPLY_MAX.set(client, 3)
        client.start()
        var session: ClientSession? = null
        try {
            session = try {
                client.connect(spec.user, spec.host, spec.port).verify(spec.connectTimeoutMillis, TimeUnit.MILLISECONDS).session
            } catch (e: IOException) {
                rejected?.let { throw TunnelException.HostKeyRejected(it) }
                throw TunnelException.Network("connect failed: ${e.message}", e)
            }
            val pairs = try {
                SecurityUtils.loadKeyPairIdentities(
                    session, NamedResource.ofName("aidos-tunnel"), ByteArrayInputStream(privateKey), null,
                )?.toList().orEmpty()
            } catch (e: Exception) {
                throw TunnelException.AuthFailed("unusable private key: ${e.message}", e)
            }
            if (pairs.isEmpty()) throw TunnelException.AuthFailed("no private key found")
            pairs.forEach { session.addPublicKeyIdentity(it) }
            try {
                session.auth().verify(spec.connectTimeoutMillis, TimeUnit.MILLISECONDS)
            } catch (e: IOException) {
                rejected?.let { throw TunnelException.HostKeyRejected(it) }
                if (session.isClosed) throw TunnelException.Network("connection lost during auth: ${e.message}", e)
                throw TunnelException.AuthFailed("server refused the key: ${e.message}", e)
            }
            val closed = CompletableDeferred<Unit>()
            session.addSessionListener(object : SessionListener {
                override fun sessionClosed(s: Session) { closed.complete(Unit) }
            })
            val bound = try {
                when (val f = spec.forward) {
                    is Forward.Reverse -> session.startRemotePortForwarding(
                        SshdSocketAddress(f.serverBindHost, f.serverPort),
                        SshdSocketAddress(f.targetHost, f.targetPort),
                    )
                    is Forward.Local -> session.startLocalPortForwarding(
                        SshdSocketAddress(f.localBindHost, f.localPort),
                        SshdSocketAddress(f.remoteHost, f.remotePort),
                    )
                }
            } catch (e: IOException) {
                if (session.isClosed) throw TunnelException.Network("connection lost: ${e.message}", e)
                throw TunnelException.ForwardRefused("server refused the forward: ${e.message}", e)
            }
            val port = bound.port
            val owned = session
            return object : SshSession {
                override val boundPort = port
                override suspend fun awaitClosed() = closed.await()
                override fun close() {
                    owned.close(true)
                    client.stop()
                }
            }.also { owned.addSessionListener(object : SessionListener {
                override fun sessionClosed(s: Session) { client.stop() }
            }) }
        } catch (e: Exception) {
            session?.close(true)
            client.stop()
            throw e
        }
    }
}

/**
 * Allows the client side to open connections only to the reverse forward's target, and never to
 * accept agent, X11 or stream-local forwards. For a local forward the client listens, so listening
 * on the configured bind address is allowed and nothing is connected from here.
 */
private class TargetOnlyFilter(private val forward: Forward) : ForwardingFilter {
    override fun canForwardAgent(session: Session, requestType: String) = false
    override fun canForwardX11(session: Session, requestType: String) = false
    override fun canListen(address: SshdSocketAddress, session: Session) =
        forward is Forward.Local && address.hostName == forward.localBindHost
    override fun canConnect(type: org.apache.sshd.server.forward.TcpForwardingFilter.Type, address: SshdSocketAddress, session: Session) =
        forward is Forward.Reverse && address.hostName == forward.targetHost && address.port == forward.targetPort
}
