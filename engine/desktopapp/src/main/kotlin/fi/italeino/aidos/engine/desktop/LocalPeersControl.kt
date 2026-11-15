package fi.italeino.aidos.engine.desktop

import dev.aidos.aidin.LinkRoute
import dev.aidos.aidin.NodeId
import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.control.PeerInfo
import fi.italeino.aidos.engine.control.PeerToolApprovalInfo
import fi.italeino.aidos.engine.control.PeersControl
import fi.italeino.aidos.engine.control.PeersStatus
import fi.italeino.aidos.engine.control.ToolAllowlistInfo

/**
 * [PeersControl] over this process's AIDIN node, [DesktopRemoteAgent] and remote routing (RFC-0107, RFC-0108): the
 * in-process path, and what the admin API serves from. Nothing here decides anything; the switches are the user's
 * and the node and agent runtime enforce what they mean.
 */
class LocalPeersControl(private val host: DesktopEngineHost) : PeersControl {
    override suspend fun status(): PeersStatus {
        val node = host.aidin.value
        val agent = host.remoteAgent
        val routing = host.remoteRouting
        val audit = host.remoteAuditLines.value
        if (node == null || agent == null || routing == null) return PeersStatus(aidinRunning = false, auditLines = audit)
        val preferred = agent.peerCandidates()
        val labels = node.peers.value.associate { it.nodeId.value to (it.label ?: it.nodeId.value.take(8)) }
        return PeersStatus(
            aidinRunning = true,
            peers = node.peers.value.filter { node.pairScopes(it.nodeId) != null }.map { p ->
                PeerInfo(
                    id = p.nodeId.value,
                    label = p.label ?: p.nodeId.value.take(8),
                    connected = p.connected,
                    route = node.routeOf(p.nodeId)?.let(::routeName),
                    hostsAgentLoops = agent.isHostingFor(p.nodeId),
                    preferredRank = preferred.indexOf(p.nodeId).takeIf { it >= 0 }?.plus(1),
                    remoteAccessAllowed = routing.isRemoteAccessAllowed(p.nodeId),
                    hints = routing.hintsFor(p.nodeId).map { "${it.host}:${it.port}" },
                    toolAllowlists = agent.allowlistsFor(p.nodeId).map { (app, tools) -> ToolAllowlistInfo(app, tools.toList()) }.sortedBy { it.app },
                )
            },
            pendingApprovals = agent.approvals.pending.value.map {
                PeerToolApprovalInfo(it.id, labels[it.peer.value] ?: it.peer.value.take(8), it.app, it.tool)
            },
            auditLines = audit,
            knownApps = host.approvalStore.listAllApprovals().filter { it.status == AppApprovalStatus.APPROVED }.map { it.packageName }.sorted(),
        )
    }

    private fun routeName(r: LinkRoute) = when (r) {
        LinkRoute.LAN -> "LAN"
        LinkRoute.DIRECT -> "DIRECT"
        LinkRoute.SSH_TUNNEL -> "SSH_TUNNEL"
    }

    private fun peer(id: String): NodeId = runCatching { NodeId(id) }.getOrNull() ?: throw IllegalArgumentException("not a peer id")

    override suspend fun setHostsAgentLoops(peerId: String, enabled: Boolean) {
        val agent = host.remoteAgent ?: throw IllegalStateException("Group sharing is off.")
        check(agent.setHostingFor(peer(peerId), enabled)) { "That device is not paired with this Engine." }
    }

    override suspend fun setPreferred(peerId: String, preferred: Boolean) {
        val agent = host.remoteAgent ?: throw IllegalStateException("Group sharing is off.")
        val p = peer(peerId)
        val now = agent.peerCandidates()
        agent.setPeerCandidates(if (preferred) (now - p) + p else now - p)
    }

    override suspend fun setRemoteAccess(peerId: String, allowed: Boolean) {
        (host.remoteRouting ?: throw IllegalStateException("Group sharing is off.")).setRemoteAccess(peer(peerId), allowed)
    }

    override suspend fun addHint(peerId: String, host: String, port: Int) {
        (this.host.remoteRouting ?: throw IllegalStateException("Group sharing is off.")).addHint(peer(peerId), host, port)
    }

    override suspend fun removeHint(peerId: String, hint: String) {
        val routing = host.remoteRouting ?: throw IllegalStateException("Group sharing is off.")
        val i = hint.lastIndexOf(':')
        require(i > 0) { "a hint is host:port" }
        routing.removeHint(peer(peerId), hint.substring(0, i), hint.substring(i + 1).toIntOrNull() ?: throw IllegalArgumentException("a hint is host:port"))
    }

    override suspend fun setToolAllowlist(peerId: String, app: String, tools: List<String>) {
        (host.remoteAgent ?: throw IllegalStateException("Group sharing is off.")).allowTools(peer(peerId), app, tools.toSet())
    }
}
