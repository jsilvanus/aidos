package dev.aidos.policy

import dev.aidos.routes.Route
import dev.aidos.routes.RouteLatency
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * RFC-0108 Phase 2: Per-peer remote-access control.
 *
 * Fail-closed policy:
 * - Peer IDs are strictly validated; blank or malformed IDs are rejected on mutation and denied
 *   on evaluation.
 * - Unknown/unlisted peers are denied remote access by default. When [knownPeers] is provided,
 *   unlisted peers are denied all routes (including LAN).
 * - Explicitly revoked peers are denied all routes (both LAN and remote) and cannot be re-allowed
 *   without explicit [unrevoke].
 */
class RemoteAccessPolicy(
    private val knownPeers: Set<String>? = null,
) {
    init {
        knownPeers?.forEach(::requireValidPeerId)
    }

    private val lock = Any()
    private val _allowedPeers = MutableStateFlow<Set<String>>(emptySet())
    val allowedPeers: StateFlow<Set<String>> = _allowedPeers.asStateFlow()

    private val _revokedPeers = MutableStateFlow<Set<String>>(emptySet())
    val revokedPeers: StateFlow<Set<String>> = _revokedPeers.asStateFlow()

    /**
     * Enable remote access for a peer.
     * Rejects blank/malformed IDs, unknown peers (when [knownPeers] is bounded), and revoked peers.
     */
    fun allow(peerId: String) {
        requireValidPeerId(peerId)
        synchronized(lock) {
            check(peerId !in _revokedPeers.value) { "peer $peerId is revoked" }
            if (knownPeers != null) {
                check(peerId in knownPeers) { "unknown peer: $peerId" }
            }
            _allowedPeers.value = _allowedPeers.value + peerId
        }
    }

    /**
     * Disable remote access for a peer.
     * The peer can only be used over Lan routes (unless revoked).
     */
    fun disallow(peerId: String) {
        requireValidPeerId(peerId)
        synchronized(lock) {
            _allowedPeers.value = _allowedPeers.value - peerId
        }
    }

    /**
     * Revoke a peer completely: removes remote-access permission and blocks all routes
     * (including LAN) until explicitly unrevoked.
     */
    fun revoke(peerId: String) {
        requireValidPeerId(peerId)
        synchronized(lock) {
            _allowedPeers.value = _allowedPeers.value - peerId
            _revokedPeers.value = _revokedPeers.value + peerId
        }
    }

    /**
     * Lift an explicit revocation for [peerId]. Does not automatically grant remote access;
     * caller must still invoke [allow] explicitly.
     */
    fun unrevoke(peerId: String) {
        requireValidPeerId(peerId)
        synchronized(lock) {
            _revokedPeers.value = _revokedPeers.value - peerId
        }
    }

    /** Returns true if [peerId] is valid and currently revoked. */
    fun isRevoked(peerId: String): Boolean {
        if (!isValidPeerId(peerId)) return false
        return peerId in _revokedPeers.value
    }

    /**
     * Check if a peer is allowed to use remote routes.
     * Fails closed for invalid, unknown/unlisted, or revoked peers.
     */
    fun isRemoteAccessAllowed(peerId: String): Boolean {
        if (!isValidPeerId(peerId)) return false
        if (knownPeers != null && peerId !in knownPeers) return false
        return peerId !in _revokedPeers.value && peerId in _allowedPeers.value
    }

    /**
     * Toggle remote access for a peer.
     * Returns the new state (true = allowed). Rejects invalid, unknown, or revoked peers.
     */
    fun toggle(peerId: String): Boolean {
        requireValidPeerId(peerId)
        synchronized(lock) {
            check(peerId !in _revokedPeers.value) { "peer $peerId is revoked" }
            if (knownPeers != null) {
                check(peerId in knownPeers) { "unknown peer: $peerId" }
            }
            val currentlyAllowed = peerId in _allowedPeers.value
            _allowedPeers.value = if (currentlyAllowed) {
                _allowedPeers.value - peerId
            } else {
                _allowedPeers.value + peerId
            }
            return !currentlyAllowed
        }
    }

    /**
     * Given a peer's latencies, decide which routes are actually usable.
     *
     * Fail-closed rules:
     * - Invalid peer ID -> emptyList()
     * - Revoked peer -> emptyList()
     * - Unknown peer when [knownPeers] is bounded -> emptyList()
     * - Explicitly allowed peer -> all latencies
     * - Otherwise -> only [Route.Lan] latencies
     */
    fun filterAllowedRoutes(
        peerId: String,
        latencies: List<RouteLatency>,
    ): List<RouteLatency> {
        if (!isValidPeerId(peerId)) return emptyList()
        if (knownPeers != null && peerId !in knownPeers) return emptyList()
        if (peerId in _revokedPeers.value) return emptyList()

        if (isRemoteAccessAllowed(peerId)) return latencies

        return latencies.filter { it.route == Route.Lan }
    }

    /**
     * Get all peers currently allowed for remote access (excluding any revoked peers).
     */
    fun getAllAllowedPeers(): Set<String> {
        return _allowedPeers.value - _revokedPeers.value
    }

    /**
     * Get all currently revoked peers.
     */
    fun getRevokedPeers(): Set<String> {
        return _revokedPeers.value.toSet()
    }

    /**
     * Clear all remote-access permissions (used on logout or reset).
     * Explicit revocations remain in effect.
     */
    fun clearAll() {
        synchronized(lock) {
            _allowedPeers.value = emptySet()
        }
    }

    companion object {
        const val MAX_PEER_ID_LENGTH = 128

        fun isValidPeerId(peerId: String): Boolean =
            peerId.isNotEmpty() &&
                peerId.length <= MAX_PEER_ID_LENGTH &&
                peerId != "." &&
                peerId != ".." &&
                peerId.all { it.isLetterOrDigit() || it == '-' || it == '_' || it == '.' }

        private fun requireValidPeerId(peerId: String) {
            require(isValidPeerId(peerId)) { "invalid peerId" }
        }
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
    init {
        require(RemoteAccessPolicy.isValidPeerId(peerId)) { "invalid peerId" }
    }

    fun toLog(): String {
        val action = if (allowed) "enabled" else "disabled"
        val peer = peerName ?: peerId
        return "Remote access $action for peer $peer at $timestamp"
    }
}
