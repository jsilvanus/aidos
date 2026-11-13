package dev.aidos.tunnel

import fi.italeino.sshtunnel.KeyGen
import fi.italeino.sshtunnel.SshTransport
import fi.italeino.sshtunnel.SshjTransport
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlin.coroutines.EmptyCoroutineContext

/**
 * RFC-0108 Phase 3: Orchestrates all SSH reverse tunnels for the Engine.
 * Creates and manages ManagedTunnel instances, one per configured tunnel.
 * 
 * Responsibilities:
 * - Load tunnels from storage at startup
 * - Generate new Ed25519 keys for tunnels
 * - Apply vault and audit integration
 * - Track tunnel state for UI and peer-access decisions
 * - Provide setup-key installer support (future)
 */
class TunnelManager(
    private val storage: TunnelStorage,
    private val vault: VaultIntegration,
    private val audit: TunnelAuditLog,
    private val scope: CoroutineScope,
) {
    // Use sshj transport (RFC-0108 phase 4 will add Android KeyStore-backed key storage)
    private val transport: SshTransport = SshjTransport()
    private val tunnels = mutableMapOf<String, ManagedTunnel>()

    /** All managed tunnels by ID. */
    val allTunnels: Map<String, ManagedTunnel> get() = tunnels.toMap()

    /**
     * Aggregate state of all tunnels: number connected, number connecting, number failed.
     * UI can show "2 up, 1 failed" at a glance.
     */
    fun tunnelsSummaryState(): StateFlow<TunnelsSummary> = TODO("Implement after initial test")

    /** Load stored tunnels and start them. Call once at Engine startup. */
    suspend fun loadAndStart() {
        val configs = storage.loadConfigs()
        for (config in configs) {
            createTunnel(config)
        }
    }

    /** Create a new tunnel. The user provides host, port, user, and selects reverse or local forward. */
    suspend fun createTunnel(config: StoredTunnelConfig): ManagedTunnel {
        // Generate a key if not already in vault
        if (!vault.hasKey(config.vaultKeyId)) {
            val gen = KeyGen.ed25519("aidos-tunnel-${config.id}")
            vault.storeKey(config.vaultKeyId, gen.privateKey, "SSH_TUNNEL_KEY")
            // User gets shown the public key and the authorized_keys line to paste on the server.
        }

        val keyProvider = VaultKeyProvider(vault.vault, config.vaultKeyId)
        val managed = ManagedTunnel(config, keyProvider, storage, audit, transport, scope)
        tunnels[config.id] = managed
        storage.saveTunnelConfig(config.id, config)
        return managed
    }

    /** Delete a tunnel. */
    suspend fun deleteTunnel(id: String) {
        tunnels.remove(id)?.let {
            it.stop()
            storage.deleteTunnelConfig(id)
            vault.deleteKey(it.config.vaultKeyId)
            audit.log(id, "Tunnel deleted")
        }
    }

    /** Rotate a tunnel's key. The user will need to update authorized_keys on the server. */
    suspend fun rotateKey(id: String) {
        val tunnel = tunnels[id] ?: throw IllegalArgumentException("tunnel not found: $id")
        tunnel.stop()
        val gen = KeyGen.ed25519("aidos-tunnel-${tunnel.config.id}")
        vault.storeKey(tunnel.config.vaultKeyId, gen.privateKey, "SSH_TUNNEL_KEY")
        // User needs to update the server with the new public key
        audit.log(id, "Tunnel key rotated")
    }
}

data class TunnelsSummary(
    val totalCount: Int,
    val upCount: Int,
    val connectingCount: Int,
    val failedCount: Int,
)

/** Integration point with the Engine's vault (RFC-0035). */
interface VaultIntegration {
    val vault: dev.aidos.vault.SecretsVault

    suspend fun hasKey(keyId: String): Boolean
    suspend fun storeKey(keyId: String, privateKey: ByteArray, kind: String)
    suspend fun deleteKey(keyId: String)
}
