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

enum class ConsumerKind { PROVIDER, MCP_SERVER, GIT_REMOTE }

/** A provider, MCP server or Git remote that may be handed a secret value. */
data class ConsumerRef(val kind: ConsumerKind, val id: String) {
    /** Stable form stored in `allowed_consumers_json`. */
    val key: String get() = "${kind.name}:$id"
}

/** Who is asking for a value, and under which authority (RFC-0035 resolution). */
data class SecretRequest(
    val consumer: ConsumerRef,
    val capabilityId: String? = null,
    val attemptId: String? = null,
    /** True when the Run has read untrusted content; secrets are then denied for its remainder. */
    val tainted: Boolean = false,
)

/** One resolution, as audited in `secret_accesses`. Never contains the value. */
data class SecretAccess(
    val secretId: SecretId,
    val consumer: ConsumerRef,
    val capabilityId: String?,
    val attemptId: String?,
    val occurredAt: Instant,
)

fun interface SecretAccessSink {
    fun record(access: SecretAccess)
}

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

    /**
     * Resolve on behalf of a named consumer (RFC-0035 "Secrets are referenced"). Enforces, in
     * order: the secret exists (`secrets.not_found`), has not expired (`secrets.expired`), the
     * run is not tainted (`secrets.denied_tainted`, RFC-0027), and [request]'s consumer is in the
     * secret's allow-list (`secrets.consumer_not_allowed`). Success is audited, without the value.
     */
    suspend fun resolveFor(id: SecretId, request: SecretRequest): Result<CharArray>

    /** Sets or clears the expiry. After it passes, resolution fails and the user is prompted. */
    suspend fun setExpiry(id: SecretId, expiresAt: Instant?)

    /** Limits which consumers may resolve [id]; empty means only the runtime's own [resolve]. */
    suspend fun setAllowedConsumers(id: SecretId, consumers: List<ConsumerRef>)

    suspend fun get(id: SecretId): Result<SecretEntry>
    suspend fun getByName(name: String): Result<SecretEntry>
    suspend fun list(): List<SecretEntry>
}
