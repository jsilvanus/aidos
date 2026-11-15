package fi.italeino.aidos.engine.desktop

import dev.aidos.tcr.engine.SshTunnelUi
import dev.aidos.tcr.engine.SshTunnelsUiState
import dev.aidos.tcr.engine.TunnelStatusUi
import fi.italeino.sshtunnel.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Presenter for the Tunnels tab (RFC-0108). Reads [DesktopTunnels] on a short poll (tunnel state changes on
 * network events, and the screen is only drawn while open) and turns each action into one call on it. The
 * blocking SSH and vault calls run off the UI thread.
 */
class DesktopTunnelsModel(
    private val host: DesktopEngineHost,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 1_000,
) {
    private val _state = MutableStateFlow(SshTunnelsUiState())
    val state: StateFlow<SshTunnelsUiState> = _state.asStateFlow()
    private var error: String? = null

    init {
        scope.launch {
            while (isActive) {
                render()
                delay(pollMillis)
            }
        }
    }

    private fun render() {
        val tunnels = host.tunnels
        _state.value = SshTunnelsUiState(
            tunnels = tunnels?.views().orEmpty().map { v ->
                val (status, detail) = status(v)
                SshTunnelUi(v.id, v.name, "${v.serverHost}:${v.serverPort}", v.user, v.publishedPort, status, detail, v.authorizedKeysLine, v.pinnedHostKey, v.pendingHostKey)
            },
            canPublish = tunnels != null && host.aidin.value != null,
            error = error,
        )
    }

    private fun status(v: TunnelView): Pair<TunnelStatusUi, String?> = when (val s = v.state) {
        TunnelState.Stopped -> (if (v.pinnedHostKey == null) TunnelStatusUi.NEEDS_HOST_KEY else TunnelStatusUi.STOPPED) to null
        TunnelState.Connecting -> TunnelStatusUi.CONNECTING to null
        is TunnelState.Up -> TunnelStatusUi.UP to "Listening on the server at port ${s.boundPort}."
        is TunnelState.Backoff -> TunnelStatusUi.RECONNECTING to "Attempt ${s.attempt}, retrying in ${s.delayMillis / 1000}s: ${s.cause}"
        is TunnelState.HostKeyChanged -> TunnelStatusUi.HOST_KEY_CHANGED to "The server now presents ${s.seenFingerprint}. It will not be trusted automatically; delete the tunnel and set it up again only if you know why."
        is TunnelState.AuthRefused -> TunnelStatusUi.AUTH_REFUSED to "${s.reason} Check that the authorized_keys line is on the server for this user."
        is TunnelState.ForwardRefused -> TunnelStatusUi.FORWARD_REFUSED to "${s.reason} The server's sshd or the authorized_keys line does not allow publishing this port."
    }

    private fun act(block: (DesktopTunnels) -> Unit) {
        scope.launch {
            error = try {
                val tunnels = host.tunnels ?: error("Group sharing is off.")
                withContext(Dispatchers.IO) { block(tunnels) }
                null
            } catch (e: Exception) {
                e.message ?: e::class.simpleName
            }
            render()
        }
    }

    fun create(name: String, hostName: String, port: Int, user: String, publishedPort: Int) = act { it.create(name, hostName, port, user, publishedPort) }
    fun probeHostKey(id: String) = act { it.probeHostKey(id) }
    fun confirmHostKey(id: String) = act { it.confirmHostKey(id) }
    fun start(id: String) = act { it.start(id) }
    fun stop(id: String) = act { it.stop(id) }
    fun rotateKey(id: String) = act { it.rotateKey(id) }
    fun delete(id: String) = act { it.delete(id) }
}
