package fi.italeino.sshtunnel

/** Thrown by a transport; the manager maps each to a [TunnelState]. */
sealed class TunnelException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class HostKeyRejected(val fingerprint: String) : TunnelException("host key rejected: $fingerprint")
    class AuthFailed(message: String, cause: Throwable? = null) : TunnelException(message, cause)
    class ForwardRefused(message: String, cause: Throwable? = null) : TunnelException(message, cause)

    /** Network-level failure: retry with backoff. */
    class Network(message: String, cause: Throwable? = null) : TunnelException(message, cause)
}

/** One established SSH connection with its forward already open. */
interface SshSession {
    /** The port that is listening (server side for reverse, local side for local). */
    val boundPort: Int

    /** Suspends until the connection is gone; returns normally when it closes, throws never. */
    suspend fun awaitClosed()

    fun close()
}

/**
 * The only part that touches an SSH implementation. JVM uses Apache MINA SSHD ([MinaTransport]);
 * an Android transport is chosen separately (RFC-0108 phase 4). Everything else is shared.
 */
fun interface SshTransport {
    /** Connect, authenticate with [privateKey], check [hostKeys], open the forward from [spec]. */
    suspend fun connect(spec: TunnelSpec, privateKey: ByteArray, hostKeys: HostKeyPolicy): SshSession
}
