package dev.aidos.tunnel

import dev.aidos.vault.SecretEntry
import dev.aidos.vault.SecretKind
import dev.aidos.vault.SecretRequest
import dev.aidos.vault.SecretsVault
import fi.italeino.sshtunnel.KeyGen
import fi.italeino.sshtunnel.SshTransport
import fi.italeino.sshtunnel.SshjTransport
import fi.italeino.sshtunnel.TunnelState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * RFC-0108 Phase 3: Orchestrates all SSH reverse/local tunnels for the Engine.
 * Creates and manages [ManagedTunnel] instances, one per configured tunnel.
 */
class TunnelManager(
    private val storage: TunnelStorage,
    private val vault: VaultIntegration,
    private val audit: TunnelAuditLog,
    private val scope: CoroutineScope,
    private val transport: SshTransport = SshjTransport(),
    private val hostKeyTrustMode: HostKeyTrustMode = HostKeyTrustMode.TOFU,
    private val keyRequestProvider: ((StoredTunnelConfig) -> SecretRequest?)? = null,
) {
    private val tunnelsMutex = Mutex()
    private val summaryLock = Any()
    private val tunnels = ConcurrentHashMap<String, ManagedTunnel>()
    private val stateObserverJobs = ConcurrentHashMap<String, Job>()
    private val _summaryState = MutableStateFlow(TunnelsSummary(0, 0, 0, 0))

    /** All managed tunnels by ID. */
    val allTunnels: Map<String, ManagedTunnel> get() = tunnels.toMap()

    /** Returns the managed tunnel for [id], or null if not registered. */
    fun getTunnel(id: String): ManagedTunnel? = tunnels[id]

    /**
     * Aggregate reactive state of all tunnels: number connected, number connecting, number failed.
     * UI can show "2 up, 1 failed" at a glance.
     */
    fun tunnelsSummaryState(): StateFlow<TunnelsSummary> = _summaryState.asStateFlow()

    /** Load stored tunnels and start them. Call once at Engine startup. */
    suspend fun loadAndStart() {
        tunnelsMutex.withLock {
            val configs = storage.loadConfigs()
            for (config in configs) {
                val managed = registerTunnelLocked(config, persistConfig = false)
                managed.start()
            }
            recomputeSummary()
        }
    }

    /** Create a new tunnel. Generates and stores an Ed25519 key if not already present in the vault. */
    suspend fun createTunnel(config: StoredTunnelConfig): ManagedTunnel =
        tunnelsMutex.withLock {
            registerTunnelLocked(config, persistConfig = true)
        }

    /** Start an existing managed tunnel by ID. */
    suspend fun startTunnel(id: String) {
        tunnelsMutex.withLock {
            val tunnel = tunnels[id] ?: throw IllegalArgumentException("tunnel not found: $id")
            tunnel.start()
            recomputeSummary()
        }
    }

    /** Stop an existing managed tunnel by ID. */
    suspend fun stopTunnel(id: String) {
        tunnelsMutex.withLock {
            val tunnel = tunnels[id] ?: throw IllegalArgumentException("tunnel not found: $id")
            tunnel.stop()
            recomputeSummary()
        }
    }

    /** Update whether remote peer connections are allowed over tunnel [id]. */
    suspend fun allowRemoteAccess(id: String, allowed: Boolean) {
        tunnelsMutex.withLock {
            val tunnel = tunnels[id] ?: throw IllegalArgumentException("tunnel not found: $id")
            tunnel.allowRemoteAccess(allowed)
        }
    }

    /** Delete a tunnel, stopping it, removing config from storage, and deleting its vault key. */
    suspend fun deleteTunnel(id: String) {
        tunnelsMutex.withLock {
            val removed = tunnels.remove(id) ?: return@withLock
            stateObserverJobs.remove(id)?.cancel()
            removed.stop()
            storage.deleteTunnelConfig(id)
            vault.deleteKey(removed.config.vaultKeyId)
            recomputeSummary()
            audit.log(id, "Tunnel deleted")
        }
    }

    /**
     * Rotate a tunnel's key. Stops the tunnel, generates a new Ed25519 keypair, stores the new
     * private key in the vault while wiping temporary key bytes, and returns the new public key
     * so the user can update `authorized_keys` on the server.
     */
    suspend fun rotateKey(id: String): String =
        tunnelsMutex.withLock {
            val tunnel = tunnels[id] ?: throw IllegalArgumentException("tunnel not found: $id")
            tunnel.stop()
            val publicKey = generateAndStoreKey(tunnel.config.id, tunnel.config.vaultKeyId)
            tunnel.updatePublicKey(publicKey)
            recomputeSummary()
            audit.log(id, "Tunnel key rotated")
            publicKey
        }

    private suspend fun registerTunnelLocked(
        config: StoredTunnelConfig,
        persistConfig: Boolean,
    ): ManagedTunnel {
        val publicKey = if (!vault.hasKey(config.vaultKeyId)) {
            generateAndStoreKey(config.id, config.vaultKeyId)
        } else {
            null
        }

        if (persistConfig) {
            storage.saveTunnelConfig(config.id, config)
        }

        tunnels.remove(config.id)?.let { previous ->
            stateObserverJobs.remove(config.id)?.cancel()
            previous.stop()
        }

        val keyProvider = VaultKeyProvider(
            vault = vault.vault,
            keyId = config.vaultKeyId,
            request = keyRequestProvider?.invoke(config),
        )
        val managed = ManagedTunnel(
            config = config,
            keyProvider = keyProvider,
            storage = storage,
            audit = audit,
            transport = transport,
            scope = scope,
            hostKeyTrustMode = hostKeyTrustMode,
            initialPublicKey = publicKey,
        )
        tunnels[config.id] = managed
        stateObserverJobs[config.id] = scope.launch {
            managed.state.collect { recomputeSummary() }
        }
        recomputeSummary()
        return managed
    }

    private suspend fun generateAndStoreKey(tunnelId: String, vaultKeyId: String): String {
        val gen = KeyGen.ed25519("aidos-tunnel-$tunnelId")
        try {
            vault.storeKey(vaultKeyId, gen.privateKey, "SSH_TUNNEL_KEY")
        } finally {
            gen.privateKey.fill(0)
        }
        return gen.publicKey
    }

    private fun recomputeSummary() {
        synchronized(summaryLock) {
            _summaryState.value = summarize(tunnels.values.map { it.state.value })
        }
    }

    private fun summarize(states: List<TunnelState>): TunnelsSummary {
        var upCount = 0
        var connectingCount = 0
        var failedCount = 0
        for (state in states) {
            when (state) {
                TunnelState.Stopped -> Unit
                TunnelState.Connecting, is TunnelState.Backoff -> connectingCount++
                is TunnelState.Up -> upCount++
                is TunnelState.HostKeyChanged,
                is TunnelState.AuthRefused,
                is TunnelState.ForwardRefused -> failedCount++
            }
        }
        return TunnelsSummary(
            totalCount = states.size,
            upCount = upCount,
            connectingCount = connectingCount,
            failedCount = failedCount,
        )
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
    val vault: SecretsVault

    suspend fun hasKey(keyId: String): Boolean
    suspend fun storeKey(keyId: String, privateKey: ByteArray, kind: String)
    suspend fun deleteKey(keyId: String)
}

/**
 * Default [VaultIntegration] backed by a [SecretsVault].
 * Stores keys by `name` (or updates an existing entry via [SecretsVault.rotate]) and ensures
 * temporary [CharArray] buffers are zeroed after vault operations.
 */
class SecretsVaultIntegration(
    override val vault: SecretsVault,
) : VaultIntegration {
    private suspend fun findEntry(keyId: String): SecretEntry? =
        vault.get(keyId).getOrNull() ?: vault.getByName(keyId).getOrNull()

    override suspend fun hasKey(keyId: String): Boolean = findEntry(keyId) != null

    override suspend fun storeKey(keyId: String, privateKey: ByteArray, kind: String) {
        val chars = utf8BytesToChars(privateKey)
        try {
            val existing = findEntry(keyId)
            if (existing != null) {
                vault.rotate(existing.id, chars)
            } else {
                val secretKind = SecretKind.entries.firstOrNull { it.name == kind } ?: SecretKind.SSH_KEY
                vault.store(keyId, secretKind, chars)
            }
        } finally {
            chars.fill('\u0000')
        }
    }

    override suspend fun deleteKey(keyId: String) {
        findEntry(keyId)?.let { vault.delete(it.id) }
    }
}
