package dev.aidos.aidin

import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketException
import java.net.SocketTimeoutException
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.SSLSocket

/** The other side closed the link during setup because it does not trust us, or we do not trust it. */
class PeerRejectedException(message: String) : IOException(message)

/**
 * One authenticated, admitted connection to a peer (RFC-0105 "Peer link"). Frames are a 4-byte
 * big-endian length and a JSON [LinkMessage]. [send] may be called from any thread; [receive]
 * from one reader.
 */
class PeerLink internal constructor(
    private val socket: SSLSocket,
    val peer: PeerCredentials,
    /** Trust domains the peer was admitted in; per-action scope checks still go to [TrustStore]. */
    val domains: Set<TrustDomain>,
    /**
     * Set on a link admitted for pairing or enrolment (invite secret proven, peer not trusted
     * yet). Such a link carries pairing messages only; [domains] is empty.
     */
    val pairingSecret: ByteArray?,
    // The streams setup already used: a second buffered reader would lose bytes the first had read ahead.
    private val input: DataInputStream,
    private val output: DataOutputStream,
) : Closeable {
    val nodeId: NodeId get() = peer.nodeId
    val isPairing: Boolean get() = pairingSecret != null
    private val writeLock = Any()

    fun send(message: LinkMessage) = synchronized(writeLock) { writeFrame(output, message) }

    /** Next message, or null when the link is closed or the peer sent something unreadable. */
    fun receive(): LinkMessage? = try {
        readFrame(input)
    } catch (e: IOException) {
        null
    }

    val isClosed: Boolean get() = socket.isClosed

    /** Says goodbye if still possible, then closes. Safe to call twice. */
    override fun close() {
        if (socket.isClosed) return
        try {
            send(LinkMessage.Bye("closing"))
        } catch (e: IOException) {
            // Already gone; closing is all that is left.
        }
        socket.close()
    }

    internal companion object {
        fun writeFrame(output: DataOutputStream, message: LinkMessage) {
            val bytes = LinkCodec.encode(message)
            require(bytes.size <= LinkCodec.MAX_FRAME_BYTES) { "frame too large" }
            output.writeInt(bytes.size)
            output.write(bytes)
            output.flush()
        }

        fun readFrame(input: DataInputStream): LinkMessage? {
            val size = try {
                input.readInt()
            } catch (e: EOFException) {
                return null
            }
            if (size < 0 || size > LinkCodec.MAX_FRAME_BYTES) throw IOException("bad frame size $size")
            val bytes = ByteArray(size)
            input.readFully(bytes)
            return LinkCodec.decode(bytes) ?: throw IOException("unreadable frame")
        }
    }
}

/**
 * Opens and accepts peer links for one node.
 *
 * Setup after the TLS handshake: the connecting side sends [LinkMessage.Hello] with its group
 * certificates; the accepting side admits it with [admit] and answers with its own Hello, or with
 * [LinkMessage.Bye] and closes. The connecting side then admits the accepting side the same way.
 * Neither side sends anything else before both have admitted each other.
 *
 * @param admit the caller's trust check, normally `TrustStore.authorizeAny` under the caller's lock.
 * @param ownCertificates this node's current group certificates, sent in Hello.
 */
class PeerLinkEndpoint(
    private val key: SigningKey,
    private val admit: (PeerCredentials) -> TrustDecision,
    private val ownCertificates: () -> List<MembershipCertificate> = { emptyList() },
    private val handshakeTimeoutMillis: Int = 10_000,
    /** Secrets of invites this node is currently showing (QR on screen); usually none. */
    private val pendingInviteSecrets: () -> List<ByteArray> = { emptyList() },
) {
    private val context = PeerTls.context(key)

    fun connect(host: String, port: Int): PeerLink = open(host, port, invite = null)

    /**
     * Connect to the device whose [invite] was scanned, to pair with it or enrol it. The TLS key
     * the device presents must be the invite's key: that is what the QR code authenticates.
     */
    fun connectForPairing(invite: PairingInvite, host: String = invite.addresses.first(), port: Int = invite.port): PeerLink =
        open(host, port, invite)

    private fun open(host: String, port: Int, invite: PairingInvite?): PeerLink {
        val socket = context.socketFactory.createSocket() as SSLSocket
        try {
            socket.connect(InetSocketAddress(host, port), handshakeTimeoutMillis)
            return setUp(socket, connecting = true, invite = invite)
        } catch (e: Exception) {
            socket.close()
            throw e
        }
    }

    /**
     * Listen on [bindAddress]:[port] (0 = ephemeral). Each admitted link is handed to [onLink] on
     * the connection's own thread; rejected or failed setups are dropped (and reported to [onReject]).
     */
    fun listen(
        bindAddress: String = "127.0.0.1",
        port: Int = 0,
        onReject: (String) -> Unit = {},
        onLink: (PeerLink) -> Unit,
    ): PeerLinkServer {
        val server = context.serverSocketFactory.createServerSocket(port, 50, InetAddress.getByName(bindAddress)) as SSLServerSocket
        server.needClientAuth = true
        server.enabledProtocols = arrayOf(PeerTls.PROTOCOL)
        return PeerLinkServer(server) { socket ->
            try {
                onLink(setUp(socket, connecting = false, invite = null))
            } catch (e: Exception) {
                onReject(e.message ?: e.javaClass.simpleName)
                socket.close()
            }
        }
    }

    private fun setUp(socket: SSLSocket, connecting: Boolean, invite: PairingInvite?): PeerLink {
        socket.enabledProtocols = arrayOf(PeerTls.PROTOCOL)
        socket.soTimeout = handshakeTimeoutMillis
        socket.startHandshake()
        val peerKey = PeerTls.peerPublicKey(socket)
        val input = DataInputStream(socket.inputStream.buffered())
        val output = DataOutputStream(socket.outputStream.buffered())
        if (invite != null && !invite.publicKey.contentEquals(peerKey)) {
            throw PeerRejectedException("device at that address is not the one in the invite")
        }
        val proof = invite?.let { PairingProof.of(it.secret, key.publicKey, peerKey) }
        val hello = LinkMessage.Hello(AIDIN_PROTOCOL_VERSION, ownCertificates().map { it.toWire() }, proof)

        if (connecting) PeerLink.writeFrame(output, hello)
        val theirs = try {
            PeerLink.readFrame(input)
        } catch (e: SocketTimeoutException) {
            throw IOException("peer did not say hello in time")
        }
        when (theirs) {
            is LinkMessage.Hello -> Unit
            is LinkMessage.Bye -> throw PeerRejectedException("peer refused the link: ${theirs.reason}")
            else -> throw IOException("expected hello")
        }
        if (theirs.protocolVersion != AIDIN_PROTOCOL_VERSION) {
            refuse(output, "protocol ${theirs.protocolVersion} not supported")
        }
        val credentials = PeerCredentials(peerKey, theirs.certificates.mapNotNull { it.toCertificate() })
        val decision = admit(credentials)
        val link = when {
            invite != null ->
                // We scanned the peer's invite and its TLS key matched: pairing link, whatever the
                // trust store says (re-pairing an already trusted device is allowed).
                PeerLink(socket, credentials, emptySet(), invite.secret, input, output)
            !connecting && theirs.pairingProof != null -> {
                val secret = pendingInviteSecrets().firstOrNull {
                    PairingProof.matches(theirs.pairingProof, it, peerKey, key.publicKey)
                } ?: refuse(output, "invite expired or not shown here")
                PeerLink(socket, credentials, emptySet(), secret, input, output)
            }
            decision is TrustDecision.Allowed -> PeerLink(socket, credentials, decision.domains, null, input, output)
            else -> refuse(output, "not trusted")
        }
        if (!connecting) PeerLink.writeFrame(output, hello)

        socket.soTimeout = 0
        return link
    }

    private fun refuse(output: DataOutputStream, reason: String): Nothing {
        try {
            PeerLink.writeFrame(output, LinkMessage.Bye(reason))
        } catch (e: IOException) {
            // The peer may already be gone; the refusal stands either way.
        }
        throw PeerRejectedException(reason)
    }
}

/** Accept loop for [PeerLinkEndpoint.listen]; one thread per connection during and after setup. */
class PeerLinkServer internal constructor(
    private val server: SSLServerSocket,
    private val handle: (SSLSocket) -> Unit,
) : Closeable {
    val port: Int get() = server.localPort

    private val acceptor = Thread({
        while (!server.isClosed) {
            val socket = try {
                server.accept() as SSLSocket
            } catch (e: SocketException) {
                break
            } catch (e: IOException) {
                continue
            }
            Thread({ handle(socket) }, "aidin-link-${socket.port}").apply { isDaemon = true }.start()
        }
    }, "aidin-accept-${server.localPort}").apply { isDaemon = true; start() }

    override fun close() {
        server.close()
        acceptor.join(2_000)
    }
}
