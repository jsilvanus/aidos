package fi.italeino.aidos.engine.desktop

import dev.aidos.aidin.LinkRoute
import dev.aidos.aidin.NodeId
import dev.aidos.tcr.engine.PeerRouteUi
import dev.aidos.tcr.engine.RemotePeerUi
import dev.aidos.tcr.engine.RemoteSharingUiState
import dev.aidos.tcr.engine.ToolAllowlistUi
import dev.aidos.tcr.engine.ToolApprovalUi
import fi.italeino.aidos.engine.approval.AppApprovalStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.runBlocking

/**
 * Presenter for the Peers tab (RFC-0107 remote agent hosting, RFC-0108 remote routes). Reads the AIDIN
 * node, [DesktopRemoteAgent] and [dev.aidos.aidin.RemoteRouting] and turns them into what the pane shows; every
 * action is one call on those, followed by a refresh. Nothing here decides anything: the switches are the
 * user's, and what they mean is enforced in the node and the agent runtime.
 */
class DesktopRemotePeersModel(
    private val host: DesktopEngineHost,
    scope: CoroutineScope,
) {
    private val error = MutableStateFlow<String?>(null)
    private val tick = MutableStateFlow(0)

    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private val pendingApprovals = host.remoteAgentFlow.flatMapLatest { it?.approvals?.pending ?: flowOf(emptyList()) }

    val state: StateFlow<RemoteSharingUiState> = combine(
        host.aidin,
        host.remoteAuditLines,
        pendingApprovals,
        tick,
        error,
    ) { node, audit, pending, _, err ->
        val agent = host.remoteAgent
        val routing = host.remoteRouting
        if (node == null || agent == null || routing == null) {
            RemoteSharingUiState(aidinRunning = false, auditLines = audit, error = err)
        } else {
            val preferred = agent.peerCandidates()
            val labels = node.peers.value.associate { it.nodeId.value to (it.label ?: it.nodeId.value.take(8)) }
            RemoteSharingUiState(
                aidinRunning = true,
                peers = node.peers.value.filter { node.pairScopes(it.nodeId) != null }.map { p ->
                    RemotePeerUi(
                        id = p.nodeId.value,
                        label = p.label ?: p.nodeId.value.take(8),
                        connected = p.connected,
                        route = node.routeOf(p.nodeId)?.let(::routeUi),
                        hostsAgentLoops = agent.isHostingFor(p.nodeId),
                        preferredRank = preferred.indexOf(p.nodeId).takeIf { it >= 0 }?.plus(1),
                        remoteAccessAllowed = routing.isRemoteAccessAllowed(p.nodeId),
                        hints = routing.hintsFor(p.nodeId).map { "${it.host}:${it.port}" },
                        toolAllowlists = agent.allowlistsFor(p.nodeId).map { (app, tools) -> ToolAllowlistUi(app, tools) }.sortedBy { it.app },
                    )
                },
                pendingApprovals = pending.map {
                    ToolApprovalUi(it.id, labels[it.peer.value] ?: it.peer.value.take(8), it.app, it.tool)
                },
                auditLines = audit,
                knownApps = runBlocking { host.approvalStore.listAllApprovals() }
                    .filter { it.status == AppApprovalStatus.APPROVED }.map { it.packageName }.sorted(),
                error = err,
            )
        }
    }.stateIn(scope, SharingStarted.Eagerly, RemoteSharingUiState())

    private fun routeUi(r: LinkRoute) = when (r) {
        LinkRoute.LAN -> PeerRouteUi.LAN
        LinkRoute.DIRECT -> PeerRouteUi.DIRECT
        LinkRoute.SSH_TUNNEL -> PeerRouteUi.SSH_TUNNEL
    }

    fun refresh() = tick.update { it + 1 }

    private fun peer(id: String): NodeId? = runCatching { NodeId(id) }.getOrNull()

    private fun guarded(block: () -> Unit) {
        try {
            block()
            error.value = null
        } catch (e: Exception) {
            error.value = e.message ?: e::class.simpleName
        }
        refresh()
    }

    fun setHostsAgentLoops(peerId: String, enabled: Boolean) = guarded {
        val p = peer(peerId) ?: return@guarded
        check(host.remoteAgent?.setHostingFor(p, enabled) == true) { "That device is not paired with this Engine." }
    }

    fun setPreferred(peerId: String, preferred: Boolean) = guarded {
        val agent = host.remoteAgent ?: return@guarded
        val p = peer(peerId) ?: return@guarded
        val now = agent.peerCandidates()
        agent.setPeerCandidates(if (preferred) (now - p) + p else now - p)
    }

    fun setRemoteAccess(peerId: String, allowed: Boolean) = guarded {
        val p = peer(peerId) ?: return@guarded
        host.remoteRouting?.setRemoteAccess(p, allowed)
    }

    fun addHint(peerId: String, hostName: String, port: Int) = guarded {
        val p = peer(peerId) ?: return@guarded
        host.remoteRouting?.addHint(p, hostName, port)
    }

    fun removeHint(peerId: String, hint: String) = guarded {
        val p = peer(peerId) ?: return@guarded
        val i = hint.lastIndexOf(':')
        if (i > 0) host.remoteRouting?.removeHint(p, hint.substring(0, i), hint.substring(i + 1).toInt())
    }

    fun setToolAllowlist(peerId: String, app: String, tools: List<String>) = guarded {
        val p = peer(peerId) ?: return@guarded
        host.remoteAgent?.allowTools(p, app, tools.toSet())
    }

    fun decideToolApproval(approvalId: String, allow: Boolean) = guarded {
        host.remoteAgent?.approvals?.decide(approvalId, allow)
    }
}
