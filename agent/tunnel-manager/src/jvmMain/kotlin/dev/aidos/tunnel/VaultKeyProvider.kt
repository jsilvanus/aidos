package dev.aidos.tunnel

import dev.aidos.vault.SecretsVault
import fi.italeino.sshtunnel.KeyProvider

/**
 * RFC-0108 Phase 3: Vault-backed KeyProvider for tunnel management.
 * The vault holds the private key encrypted. This provider asks the vault for it
 * on every connection attempt, never stores it, and never keeps it in memory
 * longer than needed for the SSH connection.
 * 
 * The vault never returns the key if the vault is locked, or if this consumer
 * doesn't have permission (handled by the vault itself via allowedConsumers).
 */
class VaultKeyProvider(
    private val vault: SecretsVault,
    private val keyId: String,
) : KeyProvider {
    override suspend fun privateKey(): ByteArray? {
        // In the real implementation, this would resolve the SSH_TUNNEL_KEY secret
        // from the vault. The vault's permission model ensures only tunnel manager
        // can read it (RFC-0035 allowedConsumers).
        return try {
            vault.resolve(keyId)?.takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null // Vault locked or key not found
        }
    }
}
