package dev.aidos.vault

import kotlinx.datetime.Instant

// ── Secret domain types ────────────────────────────────────────────────────

typealias SecretId = String

enum class SecretKind { API_KEY, TOKEN, PASSWORD, SSH_KEY, GENERIC }

data class SecretEntry(
    val id: SecretId,
    val name: String,
    val kind: SecretKind,
    val allowedConsumers: List<String>,
    val createdAt: Instant,
    val expiresAt: Instant?,
    val lastUsedAt: Instant?,
    val lastRotatedAt: Instant?,
)

/**
 * Vault contract (RFC-0035).
 *
 * The vault is user scope. Values are encrypted at rest. Nothing outside the vault holds a
 * secret value — callers receive either a [SecretEntry] (metadata only) or a resolved
 * [CharArray] (cleared by caller after use). **The resolved value is never logged, published
 * as an event, placed in a prompt, or returned through the Runtime API.**
 *
 * [store] encrypts before writing. [resolve] decrypts in memory, updates [lastUsedAt], and
 * returns a [CharArray] the caller is responsible for zeroing. [delete] wipes ciphertext from
 * the database.
 */
interface SecretsVault {
    /** Store a new secret. Returns the assigned [SecretId]. */
    suspend fun store(name: String, kind: SecretKind, value: CharArray): SecretId

    /** Rotate an existing secret — same ref, new value. Updates [lastRotatedAt]. */
    suspend fun rotate(id: SecretId, newValue: CharArray)

    /** Delete a secret. Consumers referencing it will fail with secrets.not_found. */
    suspend fun delete(id: SecretId)

    /**
     * Resolve a secret to its plaintext value.
     *
     * Caller MUST zero the returned array immediately after use. The array is freshly allocated
     * on each call — the vault holds only ciphertext.
     */
    suspend fun resolve(id: SecretId): Result<CharArray>

    suspend fun get(id: SecretId): Result<SecretEntry>
    suspend fun getByName(name: String): Result<SecretEntry>
    suspend fun list(): List<SecretEntry>
}
