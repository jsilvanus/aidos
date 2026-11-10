package fi.italeino.sshtunnel

/** Which port is forwarded, and in which direction. */
sealed interface Forward {
    /**
     * Reverse (`ssh -R`): the server listens on [serverBindHost]:[serverPort] and every connection
     * to it is carried back to [targetHost]:[targetPort] on this machine.
     */
    data class Reverse(
        val serverPort: Int,
        val targetPort: Int,
        val serverBindHost: String = "127.0.0.1",
        val targetHost: String = "127.0.0.1",
    ) : Forward

    /**
     * Local (`ssh -L`): this machine listens on [localBindHost]:[localPort] (0 picks a free port)
     * and every connection is carried to [remoteHost]:[remotePort] as seen from the server.
     */
    data class Local(
        val remoteHost: String,
        val remotePort: Int,
        val localPort: Int = 0,
        val localBindHost: String = "127.0.0.1",
    ) : Forward
}

/** Capped exponential backoff between reconnect attempts. */
data class Backoff(
    val initialMillis: Long = 1_000,
    val maxMillis: Long = 60_000,
    val factor: Double = 2.0,
) {
    init {
        require(initialMillis > 0 && maxMillis >= initialMillis && factor >= 1.0) { "invalid backoff" }
    }

    /** Delay before reconnect attempt [attempt] (1-based). */
    fun delayMillis(attempt: Int): Long {
        require(attempt >= 1) { "attempt is 1-based" }
        var d = initialMillis.toDouble()
        repeat(attempt - 1) {
            d *= factor
            if (d >= maxMillis) return maxMillis
        }
        return d.toLong().coerceAtMost(maxMillis)
    }
}

/** Everything needed to hold one tunnel. No secrets: the key comes from a [KeyProvider]. */
data class TunnelSpec(
    val host: String,
    val user: String,
    val forward: Forward,
    val port: Int = 22,
    val keepAliveSeconds: Int = 15,
    val connectTimeoutMillis: Long = 15_000,
    val backoff: Backoff = Backoff(),
) {
    init {
        require(host.isNotBlank() && user.isNotBlank()) { "host and user are required" }
        require(port in 1..65535) { "invalid port" }
        require(keepAliveSeconds >= 1) { "keepAliveSeconds must be positive" }
    }
}

/**
 * Hands over the private key (OpenSSH format, unencrypted) for one connection attempt. The library
 * never stores it and asks again on every reconnect, so a vault can refuse at any time.
 * Return null to say "no key available": the tunnel stops in [TunnelState.AuthRefused].
 */
fun interface KeyProvider {
    suspend fun privateKey(): ByteArray?
}

/**
 * Decides whether to trust a server's host key. [fingerprint] is `SHA256:<base64>` like OpenSSH
 * prints. Pin at setup; a mismatch must return false. A false result stops the tunnel in
 * [TunnelState.HostKeyChanged]; the library never re-trusts on its own.
 */
fun interface HostKeyPolicy {
    fun accept(host: String, port: Int, fingerprint: String): Boolean

    companion object {
        /** Trust exactly this fingerprint. */
        fun pinned(fingerprint: String) = HostKeyPolicy { _, _, fp -> fp == fingerprint }
    }
}

/** Observable lifecycle. */
sealed interface TunnelState {
    data object Stopped : TunnelState
    data object Connecting : TunnelState

    /** Forward established. [boundPort] is the port that is listening (server side for reverse, local for local). */
    data class Up(val boundPort: Int) : TunnelState

    /** Waiting to reconnect after a network failure; [attempt] is 1-based. */
    data class Backoff(val attempt: Int, val delayMillis: Long, val cause: String) : TunnelState

    /** The pinned host key did not match. Terminal until restarted; never auto-trusted. */
    data class HostKeyChanged(val seenFingerprint: String) : TunnelState

    /** The server refused our key, or no key was available. Terminal until restarted. */
    data class AuthRefused(val reason: String) : TunnelState

    /** The server refused the forward (e.g. `permitlisten` mismatch). Terminal until restarted. */
    data class ForwardRefused(val reason: String) : TunnelState
}
