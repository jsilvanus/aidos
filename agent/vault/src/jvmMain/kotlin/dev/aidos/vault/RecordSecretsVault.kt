package dev.aidos.vault

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.security.GeneralSecurityException
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * One stored secret as a [SecretRecordStore] holds it: metadata plus the sealed value, never the
 * plaintext. [sealed] is what [ValueSealer.seal] produced.
 */
class SecretRecord(
    val id: SecretId,
    val name: String,
    val kind: SecretKind,
    val sealed: ByteArray,
    val allowedConsumers: List<String> = emptyList(),
    val createdAt: Instant,
    val expiresAt: Instant? = null,
    val lastUsedAt: Instant? = null,
    val lastRotatedAt: Instant? = null,
) {
    fun toEntry() = SecretEntry(id, name, kind, allowedConsumers, createdAt, expiresAt, lastUsedAt, lastRotatedAt)

    fun copy(
        sealed: ByteArray = this.sealed,
        allowedConsumers: List<String> = this.allowedConsumers,
        expiresAt: Instant? = this.expiresAt,
        lastUsedAt: Instant? = this.lastUsedAt,
        lastRotatedAt: Instant? = this.lastRotatedAt,
    ) = SecretRecord(id, name, kind, sealed, allowedConsumers, createdAt, expiresAt, lastUsedAt, lastRotatedAt)
}

/**
 * Where a [RecordSecretsVault] keeps its records (RFC-0035, amendment 2026-11-19). The store sees
 * ciphertext and metadata only. [put] inserts or replaces by id.
 */
interface SecretRecordStore {
    fun all(): List<SecretRecord>
    fun put(record: SecretRecord)
    fun remove(id: SecretId): Boolean
}

/** Records in memory only: tests, and a vault that must not outlive the process. */
class InMemorySecretRecordStore : SecretRecordStore {
    private val records = LinkedHashMap<SecretId, SecretRecord>()
    @Synchronized override fun all(): List<SecretRecord> = records.values.toList()
    @Synchronized override fun put(record: SecretRecord) { records[record.id] = record }
    @Synchronized override fun remove(id: SecretId): Boolean = records.remove(id) != null
}

/**
 * AES-256-GCM sealing of one value, bound to its secret id as associated data so a sealed value
 * copied onto another record fails to open.
 *
 * The cipher picks the 12-byte IV itself. That is what an Android Keystore key created with
 * randomized encryption requires (it refuses a caller-supplied IV), and it is equally safe with a
 * software key. Format: `[0x01] || iv(12) || ciphertext+tag(16)`.
 */
object ValueSealer {
    private const val VERSION: Byte = 0x01
    private const val IV_BYTES = 12
    private const val TAG_BITS = 128
    private const val MIN_BYTES = 1 + IV_BYTES + TAG_BITS / 8

    fun seal(key: SecretKey, aad: String, plain: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv ?: throw GeneralSecurityException("The cipher did not choose an IV")
        if (iv.size != IV_BYTES) throw GeneralSecurityException("Unexpected GCM IV length: ${iv.size}")
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        val body = cipher.doFinal(plain)
        return ByteArray(1 + IV_BYTES + body.size).also {
            it[0] = VERSION
            iv.copyInto(it, 1)
            body.copyInto(it, 1 + IV_BYTES)
        }
    }

    /** Fails closed with [GeneralSecurityException] on a short, unknown-version or tampered value. */
    fun open(key: SecretKey, aad: String, sealed: ByteArray): ByteArray {
        if (sealed.size < MIN_BYTES) throw GeneralSecurityException("Sealed value too short (${sealed.size} bytes)")
        if (sealed[0] != VERSION) throw GeneralSecurityException("Unsupported sealed value version ${sealed[0].toInt() and 0xFF}")
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, sealed, 1, IV_BYTES))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return cipher.doFinal(sealed, 1 + IV_BYTES, sealed.size - 1 - IV_BYTES)
    }
}

/**
 * The RFC-0035 vault over a pluggable [SecretRecordStore] (amendment 2026-11-19). Same contract
 * and checks as [SqliteSecretsVault] (allow-lists, taint, expiry, redactor registration, rotation
 * grace, audit of [resolveFor]), for hosts without JDBC. The Android host runs it over a file in
 * app-private storage, with its key in the Android Keystore; the logic lives here so it is tested
 * on the JVM.
 *
 * Calls are short and do not suspend, so the suspend methods run on the caller's dispatcher; the
 * blocking helpers [putValue], [valueOf] and [removeByName] are for host code that is not in a
 * coroutine (settings screens, token providers).
 */
class RecordSecretsVault(
    private val store: SecretRecordStore,
    keyProvider: VaultKeyProvider,
    private val redactor: Redactor? = null,
    private val accessSink: SecretAccessSink? = null,
    private val rotationGraceMillis: Long = 24L * 60 * 60 * 1000,
    private val clock: Clock = Clock.System,
) : SecretsVault {

    private val key: SecretKey = keyProvider.loadOrCreate()

    /** What the host tells the user about how the key is held (RFC-0035). */
    val protection: KeyProtection = keyProvider.protection

    private val lock = Any()
    private inline fun <T> locked(block: () -> T): T = synchronized(lock, block)

    init {
        // RFC-0035: "Every vault value is registered with the redactor at load."
        if (redactor != null) registerAllWith(redactor)
    }

    fun registerAllWith(redactor: Redactor): Unit = locked {
        for (r in store.all()) {
            val v = open(r)
            redactor.register(r.id, r.name, v)
            v.fill('\u0000')
        }
    }

    // ── Blocking helpers for host code ─────────────────────────────────────────

    /** Stores [value] under [name], or rotates it when the name exists. Zeroes [value]. */
    fun putValue(name: String, kind: SecretKind, value: CharArray): SecretId = locked {
        val existing = byName(name)
        if (existing != null) { rotateLocked(existing.id, value); existing.id } else storeLocked(name, kind, value)
    }

    /** The value stored under [name], or null when there is none. Same checks as [resolve]; caller zeroes it. */
    fun valueOf(name: String): CharArray? = locked {
        val r = byName(name) ?: return@locked null
        if (r.allowedConsumers.isNotEmpty()) error("secrets.consumer_required: ${r.id} has an allow-list; use resolveFor")
        load(r)
    }

    /** Deletes the secret stored under [name]; false when there was none. */
    fun removeByName(name: String): Boolean = locked {
        val r = byName(name) ?: return@locked false
        deleteLocked(r.id)
        true
    }

    /**
     * Moves a value a host used to keep in plaintext into the vault (the Android host's Hugging Face
     * token and provider keys before 2026-11-19). A value already in the vault wins: the plaintext
     * copy is stale. Returns true when the caller may now delete its plaintext copy; false only when
     * there was nothing to adopt. Zeroes [plaintext].
     */
    fun adoptPlaintext(name: String, kind: SecretKind, plaintext: CharArray?): Boolean = locked {
        if (plaintext == null || plaintext.isEmpty()) return@locked false
        if (byName(name) == null) storeLocked(name, kind, plaintext) else plaintext.fill('\u0000')
        true
    }

    // ── SecretsVault ───────────────────────────────────────────────────────────

    override suspend fun store(name: String, kind: SecretKind, value: CharArray): SecretId = locked { storeLocked(name, kind, value) }

    override suspend fun rotate(id: SecretId, newValue: CharArray): Unit = locked { rotateLocked(id, newValue) }

    override suspend fun delete(id: SecretId): Unit = locked { deleteLocked(id) }

    override suspend fun resolve(id: SecretId): Result<CharArray> = runCatching {
        locked {
            val r = byId(id)
            // An allow-list means named consumers only; the anonymous resolve() is not a way around it.
            if (r.allowedConsumers.isNotEmpty()) error("secrets.consumer_required: $id has an allow-list; use resolveFor")
            load(r)
        }
    }

    override suspend fun resolveFor(id: SecretId, request: SecretRequest): Result<CharArray> = runCatching {
        locked {
            val r = byId(id)
            if (request.tainted) error("secrets.denied_tainted: a Run that read untrusted content cannot resolve secrets")
            if (r.allowedConsumers.isNotEmpty() && request.consumer.key !in r.allowedConsumers) {
                error("secrets.consumer_not_allowed: ${request.consumer.key} may not resolve $id")
            }
            val value = load(r)
            accessSink?.record(SecretAccess(id, request.consumer, request.capabilityId, request.attemptId, clock.now()))
            value
        }
    }

    override suspend fun setExpiry(id: SecretId, expiresAt: Instant?): Unit = locked {
        store.put(byId(id).copy(expiresAt = expiresAt))
    }

    override suspend fun setAllowedConsumers(id: SecretId, consumers: List<ConsumerRef>): Unit = locked {
        store.put(byId(id).copy(allowedConsumers = consumers.map { it.key }))
    }

    override suspend fun get(id: SecretId): Result<SecretEntry> = runCatching { locked { byId(id).toEntry() } }

    override suspend fun getByName(name: String): Result<SecretEntry> = runCatching {
        locked { (byName(name) ?: error("secrets.not_found name: $name")).toEntry() }
    }

    override suspend fun list(): List<SecretEntry> = locked { store.all().map { it.toEntry() } }

    // ── Internals (lock held) ──────────────────────────────────────────────────

    private fun byId(id: SecretId): SecretRecord = store.all().firstOrNull { it.id == id } ?: error("secrets.not_found: $id")
    private fun byName(name: String): SecretRecord? = store.all().firstOrNull { it.name == name }

    private fun storeLocked(name: String, kind: SecretKind, value: CharArray): SecretId {
        // Names are unique, as in vault.sql.
        if (byName(name) != null) error("secrets.name_taken: $name")
        val id = UUID.randomUUID().toString()
        store.put(SecretRecord(id, name, kind, seal(id, value), createdAt = clock.now()))
        // Registered at once: the value is a known secret before anything resolves it.
        redactor?.register(id, name, value)
        value.fill('\u0000')
        return id
    }

    private fun rotateLocked(id: SecretId, newValue: CharArray) {
        val r = byId(id)
        val old = open(r)
        store.put(r.copy(sealed = seal(id, newValue), lastRotatedAt = clock.now()))
        // The old value leaves the vault but stays filtered for a grace period (RFC-0035).
        redactor?.retire("$id#old", r.name, old, rotationGraceMillis)
        redactor?.register(id, r.name, newValue)
        old.fill('\u0000')
        newValue.fill('\u0000')
    }

    private fun deleteLocked(id: SecretId) {
        store.remove(id)
        redactor?.unregister(id)
    }

    /** Expiry check, decrypt, last-used bookkeeping and redactor registration. */
    private fun load(r: SecretRecord): CharArray {
        val exp = r.expiresAt
        val now = clock.now()
        if (exp != null && now >= exp) error("secrets.expired: ${r.id} expired at $exp")
        val value = open(r)
        store.put(r.copy(lastUsedAt = now))
        redactor?.register(r.id, r.name, value)
        return value
    }

    private fun seal(id: SecretId, value: CharArray): ByteArray {
        val bytes = String(value).toByteArray(Charsets.UTF_8)
        return try { ValueSealer.seal(key, id, bytes) } finally { bytes.fill(0) }
    }

    private fun open(r: SecretRecord): CharArray {
        val bytes = ValueSealer.open(key, r.id, r.sealed)
        return try { String(bytes, Charsets.UTF_8).toCharArray() } finally { bytes.fill(0) }
    }
}
