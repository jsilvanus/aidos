package dev.aidos.policy

import dev.aidos.routes.Route
import dev.aidos.routes.RouteLatency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RFC-0108 Phase 2: Per-peer remote-access control.
 *
 * Using a peer over a remote route (non-Lan) requires explicit opt-in from the user, shown in the UI
 * and written to the audit log. This prevents accidental exposure of local models to the internet.
 *
 * A peer is "remote-access allowed" if:
 * - The user has explicitly enabled it (remote-access switch is ON)
 * - AND at least one of its routes is non-Lan (has a remote route available)
 *
 * Queries for a peer's capabilities check: if the peer is requested over a non-Lan route but
 * remote-access is not allowed, the listener rejects the connection (even if the tunnel delivers it).
 */
class RemoteAccessPolicy {
    private val _allowedPeers = MutableStateFlow<Set<String>>(emptySet())
    val allowedPeers: StateFlow<Set<String>> = _allowedPeers.asStateFlow()

    /**
     * Enable remote access for a peer.
     * The peer can now be used over non-Lan routes.
     *
     * @param peerId: Peer's node ID or other identifier
     */
    fun allow(peerId: String) {
        _allowedPeers.value = _allowedPeers.value + peerId
    }

    /**
     * Disable remote access for a peer.
     * The peer can only be used over Lan routes.
     */
    fun disallow(peerId: String) {
        _allowedPeers.value = _allowedPeers.value - peerId
    }

    /**
     * Check if a peer is allowed to use remote routes.
     */
    fun isRemoteAccessAllowed(peerId: String): Boolean {
        return peerId in _allowedPeers.value
    }

    /**
     * Toggle remote access for a peer.
     * Returns the new state (true = allowed).
     */
    fun toggle(peerId: String): Boolean {
        val allowed = isRemoteAccessAllowed(peerId)
        if (allowed) disallow(peerId) else allow(peerId)
        return !allowed
    }

    /**
     * Given a peer's latencies, decide which routes are actually usable.
     *
     * Returns:
     * - All latencies if the peer has no remote routes (only Lan)
     * - All latencies if remote-access is allowed
     * - Only Lan latencies if remote-access is not allowed (filter out non-Lan)
     *
     * Useful for:
     * - Filtering routes before presenting to a dialer
     * - Checking if a peer can be reached at all
     * - Audit logging which routes would be used
     */
    fun filterAllowedRoutes(
        peerId: String,
        latencies: List<RouteLatency>,
    ): List<RouteLatency> {
        val lan = latencies.filter { it.route == Route.Lan }
        val remote = latencies.filter { it.route != Route.Lan }

        // If no remote routes, all latencies are OK
        if (remote.isEmpty()) return latencies

        // If remote-access allowed, all OK
        if (isRemoteAccessAllowed(peerId)) return latencies

        // Remote routes exist but not allowed: filter to Lan only
        return lan
    }

    /**
     * Get all peers currently allowed for remote access.
     */
    fun getAllAllowedPeers(): Set<String> {
        return _allowedPeers.value.toSet()
    }

    /**
     * Clear all remote-access permissions (used on logout or reset).
     */
    fun clearAll() {
        _allowedPeers.value = emptySet()
    }
}

/**
 * Audit event for remote-access policy changes.
 * Recorded whenever a user changes a peer's remote-access switch.
 */
data class RemoteAccessAuditEvent(
    val peerId: String,
    val peerName: String?,  // Human-readable peer name if available
    val allowed: Boolean,  // true = enabled, false = disabled
    val timestamp: Long = System.currentTimeMillis(),
) {
    fun toLog(): String {
        val action = if (allowed) "enabled" else "disabled"
        val peer = peerName ?: peerId
        return "Remote access $action for peer $peer at $timestamp"
    }
}
