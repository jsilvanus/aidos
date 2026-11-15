package fi.italeino.aidos.engine.control

import kotlinx.serialization.Serializable

/**
 * The peers and remote access group of `EngineControl` (RFC-0115, D61; RFC-0107 agent hosting, RFC-0108 routes):
 * paired AIDIN peers, the switches the user owns for each, and the tool approvals waiting on tainted remote runs.
 * Failures are `IllegalArgumentException` / `IllegalStateException`, mapped to 400 / 409 by the admin API and to
 * [EngineControlException] by the HTTP implementation.
 */
interface PeersControl {
    suspend fun status(): PeersStatus
    suspend fun setHostsAgentLoops(peerId: String, enabled: Boolean)
    suspend fun setPreferred(peerId: String, preferred: Boolean)
    suspend fun setRemoteAccess(peerId: String, allowed: Boolean)
    suspend fun addHint(peerId: String, host: String, port: Int)
    suspend fun removeHint(peerId: String, hint: String)
    suspend fun setToolAllowlist(peerId: String, app: String, tools: List<String>)
}

@Serializable
data class ToolAllowlistInfo(val app: String, val tools: List<String>)

/** [route] is a `PeerRouteUi` name (LAN, DIRECT, SSH_TUNNEL) or null while there is no link. */
@Serializable
data class PeerInfo(
    val id: String,
    val label: String,
    val connected: Boolean,
    val route: String? = null,
    val hostsAgentLoops: Boolean,
    val preferredRank: Int? = null,
    val remoteAccessAllowed: Boolean,
    val hints: List<String> = emptyList(),
    val toolAllowlists: List<ToolAllowlistInfo> = emptyList(),
)

/** A tool approval waiting for the user; answered through the approvals feed (`tool:<id>`). Carries no arguments. */
@Serializable
data class PeerToolApprovalInfo(val id: String, val peerLabel: String, val app: String, val tool: String)

@Serializable
data class PeersStatus(
    val aidinRunning: Boolean,
    val peers: List<PeerInfo> = emptyList(),
    val pendingApprovals: List<PeerToolApprovalInfo> = emptyList(),
    val auditLines: List<String> = emptyList(),
    val knownApps: List<String> = emptyList(),
)

@Serializable data class PeerFlagRequest(val enabled: Boolean)
@Serializable data class PeerHintRequest(val host: String, val port: Int)
@Serializable data class PeerHintRemoveRequest(val hint: String)
@Serializable data class PeerToolAllowlistRequest(val app: String, val tools: List<String>)
