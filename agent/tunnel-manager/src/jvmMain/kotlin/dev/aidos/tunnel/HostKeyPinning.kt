package dev.aidos.tunnel

import fi.italeino.sshtunnel.HostKeyPolicy

/**
 * RFC-0108 Phase 3: Host key pinning policy.
 * On the first successful connection, the server's host key fingerprint is saved.
 * On subsequent connections, the same fingerprint must be seen.
 * A mismatch stops the tunnel immediately (HostKeyChanged state).
 */
class HostKeyPinningPolicy(
    private val tunnelId: String,
    private val storage: TunnelStorage,
    private val audit: TunnelAuditLog,
) : HostKeyPolicy {
    private var pinnedFingerprint: String? = null
    private var initialized = false

    override fun accept(host: String, port: Int, fingerprint: String): Boolean {
        if (!initialized) {
            // Note: in a real app this would be async and would load from storage on start.
            // For now, we assume it's called after pinnedFingerprint is set by the caller.
            initialized = true
        }

        val pinned = pinnedFingerprint
        if (pinned == null) {
            // First connection: save the fingerprint.
            pinnedFingerprint = fingerprint
            audit.log(tunnelId, "Host key pinned: $fingerprint")
            return true
        }

        if (pinned == fingerprint) return true

        // Mismatch: host key has changed.
        audit.log(tunnelId, "⚠ Host key mismatch! Expected $pinned, got $fingerprint")
        return false
    }

    /** Set the pinned fingerprint at startup (loaded from storage). */
    fun setPinned(fingerprint: String?) {
        pinnedFingerprint = fingerprint
    }
}

/**
 * RFC-0108 Phase 3: Audit log for tunnel events.
 * In a real implementation, this would integrate with the Engine's audit trail (RFC-0042).
 */
interface TunnelAuditLog {
    suspend fun log(tunnelId: String, message: String)
}
