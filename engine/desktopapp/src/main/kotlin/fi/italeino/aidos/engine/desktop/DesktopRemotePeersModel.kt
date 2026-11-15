package fi.italeino.aidos.engine.desktop

import dev.aidos.tcr.engine.PeerRouteUi
import dev.aidos.tcr.engine.RemotePeerUi
import dev.aidos.tcr.engine.RemoteSharingUiState
import dev.aidos.tcr.engine.ToolAllowlistUi
import dev.aidos.tcr.engine.ToolApprovalUi
import fi.italeino.aidos.engine.control.ApprovalsControl
import fi.italeino.aidos.engine.control.PeersControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Presenter for the Peers tab (RFC-0107 remote agent hosting, RFC-0108 remote routes). It knows only
 * [PeersControl] and [ApprovalsControl] (RFC-0115, D61): the same pane works over the in-process Engine or a
 * background Engine service. It polls the status on a short interval (peers connect and links change on network
 * events; the screen is only drawn while open); every action is one control call followed by a refresh.
 * Nothing here decides anything: the switches are the user's, and what they mean is enforced in the node and
 * the agent runtime.
 */
class DesktopRemotePeersModel(
    private val scope: CoroutineScope,
    private val pollMillis: Long = 700,
    private val peers: () -> PeersControl,
    private val approvals: () -> ApprovalsControl,
) {
    private val _state = MutableStateFlow(RemoteSharingUiState())
    val state: StateFlow<RemoteSharingUiState> = _state.asStateFlow()
    private var error: String? = null

    /** Actions run one at a time, in the order the user made them: they all edit the same peer configuration. */
    private val actions = Mutex()

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
            val s = peers().status()
            _state.value = RemoteSharingUiState(
                aidinRunning = s.aidinRunning,
                peers = s.peers.map { p ->
                    RemotePeerUi(
                        id = p.id, label = p.label, connected = p.connected,
                        route = p.route?.let { runCatching { PeerRouteUi.valueOf(it) }.getOrNull() },
                        hostsAgentLoops = p.hostsAgentLoops, preferredRank = p.preferredRank,
                        remoteAccessAllowed = p.remoteAccessAllowed, hints = p.hints,
                        toolAllowlists = p.toolAllowlists.map { ToolAllowlistUi(it.app, it.tools) },
                    )
                },
                pendingApprovals = s.pendingApprovals.map { ToolApprovalUi(it.id, it.peerLabel, it.app, it.tool) },
                auditLines = s.auditLines,
                knownApps = s.knownApps,
                error = error,
            )
        } catch (e: Exception) {
            _state.value = _state.value.copy(error = error ?: e.message ?: e::class.simpleName)
        }
    }

    fun refresh() { scope.launch { render() } }

    private fun act(block: suspend () -> Unit) {
        scope.launch {
            actions.withLock { error = try {
                block()
                null
            } catch (e: Exception) {
                e.message ?: e::class.simpleName
            } }
            render()
        }
    }

    fun setHostsAgentLoops(peerId: String, enabled: Boolean) = act { peers().setHostsAgentLoops(peerId, enabled) }
    fun setPreferred(peerId: String, preferred: Boolean) = act { peers().setPreferred(peerId, preferred) }
    fun setRemoteAccess(peerId: String, allowed: Boolean) = act { peers().setRemoteAccess(peerId, allowed) }
    fun addHint(peerId: String, hostName: String, port: Int) = act { peers().addHint(peerId, hostName, port) }
    fun removeHint(peerId: String, hint: String) = act { peers().removeHint(peerId, hint) }
    fun setToolAllowlist(peerId: String, app: String, tools: List<String>) = act { peers().setToolAllowlist(peerId, app, tools) }
    fun decideToolApproval(approvalId: String, allow: Boolean) = act { approvals().decide("tool:$approvalId", allow) }
}
