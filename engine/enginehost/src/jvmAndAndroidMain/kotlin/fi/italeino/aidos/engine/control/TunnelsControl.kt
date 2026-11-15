package fi.italeino.aidos.engine.control

import kotlinx.serialization.Serializable

/**
 * The SSH tunnels group of `EngineControl` (RFC-0115, D61, RFC-0108): reverse tunnels that publish this Engine's
 * remote-ingress listener on a server the user controls. Same two implementations as every group: in-process
 * (`LocalTunnelsControl`) and over the loopback admin API (`HttpTunnelsControl`). Never carries a private key.
 * Failures are `IllegalArgumentException` (bad input) or `IllegalStateException` (not possible now); the admin API
 * maps them to 400 and 409 and the HTTP implementation raises [EngineControlException].
 */
interface TunnelsControl {
    suspend fun list(): TunnelsStatus
    suspend fun create(request: CreateTunnelRequest): String

    /** Connects to the server and returns the host key fingerprint it presented, to be confirmed by the user. */
    suspend fun probeHostKey(id: String): String
    suspend fun confirmHostKey(id: String)
    suspend fun start(id: String)
    suspend fun stop(id: String)

    /** Replaces the tunnel's key and returns the new `authorized_keys` line. */
    suspend fun rotateKey(id: String): String
    suspend fun delete(id: String)
}

/** [status] is a `TunnelStatusUi` name (STOPPED, NEEDS_HOST_KEY, CONNECTING, UP, RECONNECTING, HOST_KEY_CHANGED, AUTH_REFUSED, FORWARD_REFUSED). */
@Serializable
data class TunnelInfo(
    val id: String,
    val name: String,
    val serverHost: String,
    val serverPort: Int,
    val user: String,
    val publishedPort: Int,
    val status: String,
    val detail: String? = null,
    val authorizedKeysLine: String? = null,
    val pinnedHostKey: String? = null,
    val pendingHostKey: String? = null,
)

/** [canPublish] is false while group sharing (AIDIN) is off: tunnels exist but nothing can be published. */
@Serializable
data class TunnelsStatus(val tunnels: List<TunnelInfo>, val canPublish: Boolean)

@Serializable
data class CreateTunnelRequest(val name: String, val host: String, val port: Int, val user: String, val publishedPort: Int)

/** A single string answer (an id, a fingerprint, a key line). */
@Serializable
data class ControlValue(val value: String)
