package fi.italeino.aidos.engine.desktop

import fi.italeino.aidos.engine.control.ControlValue
import fi.italeino.aidos.engine.control.CreateTunnelRequest
import fi.italeino.aidos.engine.control.TunnelInfo
import fi.italeino.aidos.engine.control.TunnelsControl
import fi.italeino.aidos.engine.control.TunnelsStatus
import fi.italeino.sshtunnel.TunnelState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** [TunnelsControl] over this process's [DesktopTunnels]: the in-process path, and what the admin API serves from. */
class LocalTunnelsControl(private val host: DesktopEngineHost) : TunnelsControl {
    private fun tunnels(): DesktopTunnels = host.tunnels ?: throw IllegalStateException("Group sharing is off.")

    override suspend fun list(): TunnelsStatus {
        val t = host.tunnels
        return TunnelsStatus(
            tunnels = t?.views().orEmpty().map { v ->
                val (status, detail) = statusOf(v)
                TunnelInfo(v.id, v.name, v.serverHost, v.serverPort, v.user, v.publishedPort, status, detail, v.authorizedKeysLine, v.pinnedHostKey, v.pendingHostKey)
            },
            canPublish = t != null && host.aidin.value != null,
        )
    }

    private suspend fun <T> io(block: (DesktopTunnels) -> T): T = withContext(Dispatchers.IO) { block(tunnels()) }

    override suspend fun create(request: CreateTunnelRequest): String =
        io { it.create(request.name, request.host, request.port, request.user, request.publishedPort) }
    override suspend fun probeHostKey(id: String): String = io { it.probeHostKey(id) }
    override suspend fun confirmHostKey(id: String) = io { it.confirmHostKey(id) }
    override suspend fun start(id: String) = io { it.start(id) }
    override suspend fun stop(id: String) = io { it.stop(id) }
    override suspend fun rotateKey(id: String): String = io { it.rotateKey(id) }
    override suspend fun delete(id: String) = io { it.delete(id) }

    /** The status name (a `TunnelStatusUi` constant) and the sentence that explains it. */
    private fun statusOf(v: TunnelView): Pair<String, String?> = when (val s = v.state) {
        TunnelState.Stopped -> (if (v.pinnedHostKey == null) "NEEDS_HOST_KEY" else "STOPPED") to null
        TunnelState.Connecting -> "CONNECTING" to null
        is TunnelState.Up -> "UP" to "Listening on the server at port ${s.boundPort}."
        is TunnelState.Backoff -> "RECONNECTING" to "Attempt ${s.attempt}, retrying in ${s.delayMillis / 1000}s: ${s.cause}"
        is TunnelState.HostKeyChanged -> "HOST_KEY_CHANGED" to "The server now presents ${s.seenFingerprint}. It will not be trusted automatically; delete the tunnel and set it up again only if you know why."
        is TunnelState.AuthRefused -> "AUTH_REFUSED" to "${s.reason} Check that the authorized_keys line is on the server for this user."
        is TunnelState.ForwardRefused -> "FORWARD_REFUSED" to "${s.reason} The server's sshd or the authorized_keys line does not allow publishing this port."
    }
}
