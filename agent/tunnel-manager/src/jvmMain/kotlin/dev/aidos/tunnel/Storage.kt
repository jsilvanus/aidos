package dev.aidos.tunnel

import fi.italeino.sshtunnel.TunnelState

/** Persists tunnel configuration and state. */
interface TunnelStorage {
    /** Load all saved tunnel configurations. */
    suspend fun loadConfigs(): List<StoredTunnelConfig>
    
    /** Save a tunnel configuration. */
    suspend fun saveTunnelConfig(id: String, config: StoredTunnelConfig)
    
    /** Delete a tunnel configuration. */
    suspend fun deleteTunnelConfig(id: String)
    
    /** Load the pinned host key fingerprint for a tunnel, or null if never verified. */
    suspend fun loadPinnedHostKey(tunnelId: String): String?
    
    /** Save the pinned host key fingerprint after verification. */
    suspend fun pinHostKey(tunnelId: String, fingerprint: String)
    
    /** Load the most recent tunnel state. */
    suspend fun loadTunnelState(tunnelId: String): TunnelState?
    
    /** Save the current tunnel state. */
    suspend fun saveTunnelState(tunnelId: String, state: TunnelState)
}

/** A tunnel configuration that persists: host, user, ports, and where the key is kept in the vault. */
data class StoredTunnelConfig(
    val id: String,
    val name: String,
    val host: String,
    val port: Int = 22,
    val user: String,
    val forward: StoredForward,
    val vaultKeyId: String,
    val remoteAccessAllowed: Boolean = false,
    val keepAliveSeconds: Int = 15,
    val connectTimeoutMillis: Long = 15_000,
)

sealed interface StoredForward {
    data class Reverse(
        val serverPort: Int,
        val targetPort: Int,
        val serverBindHost: String = "127.0.0.1",
        val targetHost: String = "127.0.0.1",
    ) : StoredForward

    data class Local(
        val remoteHost: String,
        val remotePort: Int,
        val localPort: Int = 0,
        val localBindHost: String = "127.0.0.1",
    ) : StoredForward
}
