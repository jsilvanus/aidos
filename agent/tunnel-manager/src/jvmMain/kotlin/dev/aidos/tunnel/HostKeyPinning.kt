package dev.aidos.tunnel

import fi.italeino.sshtunnel.HostKeyPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Controls how [HostKeyPinningPolicy] behaves when no host key has been pinned yet.
 */
enum class HostKeyTrustMode {
    /**
     * Trust on first use: accept and persist the first observed host key fingerprint, then
     * reject any subsequent change.
     */
    TOFU,

    /**
     * Strict pre-pinned mode: require a host key fingerprint to already be pinned in
     * [TunnelStorage] (or set via [HostKeyPinningPolicy.setPinned]) before connecting.
     */
    STRICT_PRE_PINNED,
}

/**
 * RFC-0108 Phase 3: Host key pinning policy.
 * Loads any previously pinned fingerprint from [TunnelStorage] and persists first-seen keys in
 * [HostKeyTrustMode.TOFU] mode. Any mismatch against the pinned fingerprint is rejected and
 * audited, stopping the tunnel in `TunnelState.HostKeyChanged`.
 */
class HostKeyPinningPolicy(
    private val tunnelId: String,
    private val storage: TunnelStorage,
    private val audit: TunnelAuditLog,
    private val trustMode: HostKeyTrustMode = HostKeyTrustMode.TOFU,
) : HostKeyPolicy {
    private val mutex = Mutex()

    @Volatile
    private var pinnedFingerprint: String? = null

    @Volatile
    private var initialized: Boolean = false

    /** Load any persisted host key fingerprint from [TunnelStorage]. */
    suspend fun loadPinnedKey() {
        mutex.withLock {
            pinnedFingerprint = storage.loadPinnedHostKey(tunnelId)
            initialized = true
        }
    }

    /** Set or clear the pinned fingerprint in memory (e.g. from pre-loaded configuration). */
    fun setPinned(fingerprint: String?) {
        pinnedFingerprint = fingerprint
        initialized = true
    }

    override fun accept(host: String, port: Int, fingerprint: String): Boolean {
        if (fingerprint.isBlank()) return false

        val auditMessage: String?
        val accepted: Boolean

        runBlocking {
            mutex.withLock {
                if (!initialized) {
                    pinnedFingerprint = storage.loadPinnedHostKey(tunnelId)
                    initialized = true
                }

                val pinned = pinnedFingerprint
                when {
                    pinned == null && trustMode == HostKeyTrustMode.STRICT_PRE_PINNED -> {
                        auditMessage = "⚠ Host key rejected for $host:$port: no pre-pinned key configured (got $fingerprint)"
                        accepted = false
                    }
                    pinned == null -> {
                        storage.pinHostKey(tunnelId, fingerprint)
                        pinnedFingerprint = fingerprint
                        auditMessage = "Host key pinned for $host:$port: $fingerprint"
                        accepted = true
                    }
                    pinned == fingerprint -> {
                        auditMessage = null
                        accepted = true
                    }
                    else -> {
                        auditMessage = "⚠ Host key mismatch for $host:$port! Expected $pinned, got $fingerprint"
                        accepted = false
                    }
                }
            }
        }

        if (auditMessage != null) {
            try {
                runBlocking { audit.log(tunnelId, auditMessage) }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                // Audit logging failure must not alter the host-key verification verdict.
            }
        }
        return accepted
    }
}

/**
 * RFC-0108 Phase 3: Audit log for tunnel events.
 * In a real implementation, this would integrate with the Engine's audit trail (RFC-0042).
 */
interface TunnelAuditLog {
    suspend fun log(tunnelId: String, message: String)
}
