package fi.italeino.aidos.engine.desktop

import dev.aidos.tcr.engine.SshTunnelUi
import dev.aidos.tcr.engine.SshTunnelsUiState
import dev.aidos.tcr.engine.TunnelStatusUi
import fi.italeino.aidos.engine.control.CreateTunnelRequest
import fi.italeino.aidos.engine.control.TunnelsControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Presenter for the Tunnels tab (RFC-0108). It knows only [TunnelsControl] (RFC-0115, D61): the same pane works over
 * the in-process Engine or a background Engine service. Polls on a short interval (tunnel state changes on network
 * events, and the screen is only drawn while open) and turns each action into one control call.
 */
class DesktopTunnelsModel(
    private val scope: CoroutineScope,
    private val pollMillis: Long = 1_000,
    private val control: () -> TunnelsControl,
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

    private suspend fun render() {
        try {
            val s = control().list()
            _state.value = SshTunnelsUiState(
                tunnels = s.tunnels.map { v ->
                    val status = runCatching { TunnelStatusUi.valueOf(v.status) }.getOrDefault(TunnelStatusUi.STOPPED)
                    SshTunnelUi(v.id, v.name, "${v.serverHost}:${v.serverPort}", v.user, v.publishedPort, status, v.detail, v.authorizedKeysLine, v.pinnedHostKey, v.pendingHostKey)
                },
                canPublish = s.canPublish,
                error = error,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = error ?: e.message ?: e::class.simpleName)
        }
    }

    private fun act(block: suspend (TunnelsControl) -> Unit) {
        scope.launch {
            error = try {
                block(control())
                null
            } catch (e: Exception) {
                e.message ?: e::class.simpleName
            }
            render()
        }
    }

    fun create(name: String, hostName: String, port: Int, user: String, publishedPort: Int) =
        act { it.create(CreateTunnelRequest(name, hostName, port, user, publishedPort)) }
    fun probeHostKey(id: String) = act { it.probeHostKey(id) }
    fun confirmHostKey(id: String) = act { it.confirmHostKey(id) }
    fun start(id: String) = act { it.start(id) }
    fun stop(id: String) = act { it.stop(id) }
    fun rotateKey(id: String) = act { it.rotateKey(id) }
    fun delete(id: String) = act { it.delete(id) }
}
