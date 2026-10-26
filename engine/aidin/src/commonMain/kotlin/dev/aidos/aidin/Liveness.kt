package dev.aidos.aidin

import kotlinx.serialization.Serializable

/**
 * RFC-0105 "Peer link" heartbeat, sent every [PeerTracker.heartbeatIntervalMillis] over the
 * authenticated peer link. Carries what peers need to plan and route; never names, prompts or
 * content. Readers ignore unknown fields.
 */
@Serializable
data class Heartbeat(
    val protocolVersion: Int,
    val engineVersion: String,
    /** Pinned llama.cpp build of the split-inference binaries, null when this node has none. */
    val llamaCppBuild: String? = null,
    val models: List<ModelAdvert> = emptyList(),
    /** RAM this node offers to the group now (its contribution budget), bytes. */
    val contributionRamBytes: Long = 0,
    val batteryPercent: Int? = null,
    val charging: Boolean? = null,
    /** Android `PowerManager` thermal status name, or null where the platform has none. */
    val thermal: String? = null,
    val roles: List<String> = emptyList(),
    /** Highest revocation-list serial held, per group id, so peers know whom to send a newer list. */
    val revocationSerials: Map<String, Long> = emptyMap(),
    val draining: Boolean = false,
)

@Serializable
data class ModelAdvert(
    val id: String,
    val kind: String,
    /** Content digest of the model artifact; equal ids are not proof of equal weights. */
    val digest: String? = null,
    /** RFC-0104 placement as served by this node: local, cluster or remote. */
    val placement: String,
    val loaded: Boolean = false,
)

/** aidin.md / RFC-0105 node states, as one node sees another. */
enum class PeerState { JOINING, READY, ACTIVE, DRAINING, SUSPECTED, LEFT }

data class PeerTransition(val nodeId: NodeId, val from: PeerState, val to: PeerState, val atMillis: Long)

/**
 * Tracks peers' liveness from heartbeats, on a clock the caller supplies (tests drive it
 * directly; RFC-0105 asks for a deterministic layer). A single late heartbeat changes nothing:
 * a peer becomes SUSPECTED after [missedBeforeSuspect] intervals without one and LEFT after
 * [leaveAfterMillis], so a dropped packet does not tear down a split model.
 *
 * ACTIVE is set by the caller ([markActive]) when the peer takes part in a plan or serves
 * requests; heartbeats alone only make a peer READY.
 */
class PeerTracker(
    val heartbeatIntervalMillis: Long = 3_000,
    val missedBeforeSuspect: Int = 3,
    val leaveAfterMillis: Long = 20_000,
) {
    // lastSeenMillis: last heartbeat, or the connect time while JOINING, so a link that never sends
    // a heartbeat also times out.
    private class Entry(var state: PeerState, var lastSeenMillis: Long, var active: Boolean = false)

    private val peers = LinkedHashMap<NodeId, Entry>()

    init {
        require(heartbeatIntervalMillis > 0 && missedBeforeSuspect > 0)
        require(leaveAfterMillis > heartbeatIntervalMillis * missedBeforeSuspect) { "leave must come after suspect" }
    }

    fun state(nodeId: NodeId): PeerState? = peers[nodeId]?.state

    fun states(): Map<NodeId, PeerState> = peers.mapValues { it.value.state }

    /** The authenticated link is up; no heartbeat yet. */
    fun onConnected(nodeId: NodeId, nowMillis: Long): List<PeerTransition> {
        val entry = peers[nodeId]
        if (entry != null && entry.state != PeerState.LEFT) return emptyList()
        peers[nodeId] = Entry(PeerState.JOINING, nowMillis)
        return listOf(PeerTransition(nodeId, entry?.state ?: PeerState.LEFT, PeerState.JOINING, nowMillis))
    }

    fun onHeartbeat(nodeId: NodeId, heartbeat: Heartbeat, nowMillis: Long): List<PeerTransition> {
        val entry = peers.getOrPut(nodeId) { Entry(PeerState.LEFT, nowMillis) }
        if (entry.state == PeerState.LEFT) entry.active = false
        entry.lastSeenMillis = nowMillis
        val next = when {
            heartbeat.draining -> PeerState.DRAINING
            entry.active -> PeerState.ACTIVE
            else -> PeerState.READY
        }
        return move(nodeId, entry, next, nowMillis)
    }

    fun markActive(nodeId: NodeId, active: Boolean, nowMillis: Long): List<PeerTransition> {
        val entry = peers[nodeId] ?: return emptyList()
        entry.active = active
        val next = when (entry.state) {
            PeerState.READY -> if (active) PeerState.ACTIVE else return emptyList()
            PeerState.ACTIVE -> if (active) return emptyList() else PeerState.READY
            else -> return emptyList()
        }
        return move(nodeId, entry, next, nowMillis)
    }

    /** The link closed cleanly (peer left on purpose). */
    fun onDisconnected(nodeId: NodeId, nowMillis: Long): List<PeerTransition> {
        val entry = peers[nodeId] ?: return emptyList()
        entry.active = false
        return move(nodeId, entry, PeerState.LEFT, nowMillis)
    }

    /** Apply timeouts. Call at least once per heartbeat interval. */
    fun tick(nowMillis: Long): List<PeerTransition> {
        val out = mutableListOf<PeerTransition>()
        for ((nodeId, entry) in peers) {
            if (entry.state == PeerState.LEFT) continue
            val since = nowMillis - entry.lastSeenMillis
            val next = when {
                since > leaveAfterMillis -> PeerState.LEFT
                since > heartbeatIntervalMillis * missedBeforeSuspect -> PeerState.SUSPECTED
                else -> continue
            }
            if (next == PeerState.LEFT) entry.active = false
            out += move(nodeId, entry, next, nowMillis)
        }
        return out
    }

    private fun move(nodeId: NodeId, entry: Entry, to: PeerState, nowMillis: Long): List<PeerTransition> {
        if (entry.state == to) return emptyList()
        val from = entry.state
        entry.state = to
        return listOf(PeerTransition(nodeId, from, to, nowMillis))
    }
}
