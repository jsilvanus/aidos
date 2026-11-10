package dev.aidos.vault

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import java.io.File
import java.security.SecureRandom
import java.sql.Connection
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * SQLite-backed secrets vault with AES-256-GCM encryption (RFC-0035, M14).
 *
 * The key comes from a [VaultKeyProvider] (OS keystore, passphrase, or owner-only file) and is
 * never written to the database; the database holds ciphertext only. This implementation uses
 * JDBC directly for BLOB operations. Android's Keystore is not provided here: this module is
 * JVM-only and the Android host does not run the vault yet (see STATUS.md).
 *
 * **Values are held in CharArray/ByteArray and zeroed after use, never in String.**
 */
class SqliteSecretsVault(
    dbFile: File,
    private val encryptionKey: SecretKey,
    /**
     * Registered with every value [resolve] decrypts, and unregistered on [delete] (RFC-0035:
     * "the vault calls register on load"). Nullable so a caller that has no [Redactor] to inject
     * (e.g. a test that only cares about the crypto round-trip) still gets the pre-M14 behavior,
     * not a required dependency it doesn't need.
     */
    private val redactor: Redactor? = null,
    /** Audit of successful [resolveFor] calls (RFC-0035 `secret_accesses`). */
    private val accessSink: SecretAccessSink? = null,
    /** How long a rotated-out value stays registered with the redactor (RFC-0035 rotation). */
    private val rotationGraceMillis: Long = 24L * 60 * 60 * 1000,
) : SecretsVault, AutoCloseable {

    /** Opens a vault whose key comes from [keyProvider]; this is what production hosts use. */
    constructor(
        dbFile: File,
        keyProvider: VaultKeyProvider,
        redactor: Redactor? = null,
        accessSink: SecretAccessSink? = null,
    ) : this(dbFile, keyProvider.loadOrCreate(), redactor, accessSink)

    /** Throwaway key held in memory: tests and callers that want a vault that cannot outlive the process. */
    constructor(dbFile: File) : this(dbFile, generateKey())

    private val conn: Connection

    init {
        dbFile.parentFile?.mkdirs()
        Class.forName("org.sqlite.JDBC")
        val props = Properties().apply {
            setProperty("foreign_keys", "true")
        }
        conn = DriverManager.getConnection("jdbc:sqlite:${dbFile.absolutePath}", props)
        conn.createStatement().use { stmt ->
            stmt.execute("PRAGMA journal_mode=WAL")
            stmt.execute("PRAGMA synchronous=NORMAL")
            stmt.execute("PRAGMA foreign_keys=ON")
        }
        applySchema()
        // RFC-0035: "Every vault value is registered with the redactor at load." Without this a
        // secret the runtime has not yet resolved this session could appear in output unredacted.
        if (redactor != null) registerAll(redactor)
    }

    /** Registers every stored value with [redactor] (a per-Run redactor builds its known set this way). */
    fun registerAllWith(redactor: Redactor) = registerAll(redactor)

    private fun registerAll(redactor: Redactor) {
        val rows = mutableListOf<Triple<String, String, Pair<ByteArray, ByteArray>>>()
        conn.prepareStatement("SELECT id,name,ciphertext,nonce FROM secrets").use { st ->
            st.executeQuery().use { rs -> while (rs.next()) rows += Triple(rs.getString(1), rs.getString(2), rs.getBytes(3) to rs.getBytes(4)) }
        }
        for ((id, name, enc) in rows) {
            val v = decrypt(id, enc.first, enc.second)
            redactor.register(id, name, v)
            v.fill('\u0000')
        }
    }

    override fun close() { conn.close() }

    private fun applySchema() {
        val sql = SqliteSecretsVault::class.java.getResourceAsStream("/vault.sql")
            ?.bufferedReader()?.readText()
            ?: error("vault.sql not found on classpath")
        // SQLite executescript doesn't exist in JDBC; split on semicolons.
        for (stmt in sql.split(";")) {
            val trimmed = stmt.trim()
            if (trimmed.isNotEmpty() && !trimmed.startsWith("--") && !trimmed.startsWith("PRAGMA")) {
                try { conn.createStatement().use { it.execute(trimmed) } } catch (_: Exception) {}
            }
        }
    }

    override suspend fun store(name: String, kind: SecretKind, value: CharArray): SecretId {
        val id = UUID.randomUUID().toString()
        val (ciphertext, nonce) = encrypt(id, value)
        val now = Clock.System.now().toString()
        conn.prepareStatement(
            "INSERT INTO secrets(id,name,kind,ciphertext,nonce,allowed_consumers_json,created_at) VALUES(?,?,?,?,?,'[]',?)"
        ).use { stmt ->
            stmt.setString(1, id)
            stmt.setString(2, name)
            stmt.setString(3, kind.name)
            stmt.setBytes(4, ciphertext)
            stmt.setBytes(5, nonce)
            stmt.setString(6, now)
            stmt.executeUpdate()
        }
        // Registered immediately: the value is now a known secret even before anything resolves it.
        redactor?.register(id, name, value)
        value.fill('\u0000')
        return id
    }

    override suspend fun rotate(id: SecretId, newValue: CharArray) {
        val (name, old) = conn.prepareStatement("SELECT name,ciphertext,nonce FROM secrets WHERE id=?").use { st ->
            st.setString(1, id)
            st.executeQuery().use { rs ->
                if (!rs.next()) error("secrets.not_found: $id")
                rs.getString(1) to decrypt(id, rs.getBytes(2), rs.getBytes(3))
            }
        }
        val (ciphertext, nonce) = encrypt(id, newValue)
        val now = Clock.System.now().toString()
        conn.prepareStatement(
            "UPDATE secrets SET ciphertext=?,nonce=?,last_rotated_at=? WHERE id=?"
        ).use { stmt ->
            stmt.setBytes(1, ciphertext)
            stmt.setBytes(2, nonce)
            stmt.setString(3, now)
            stmt.setString(4, id)
            stmt.executeUpdate()
        }
        // The prior value leaves the vault but stays filtered for a grace period (RFC-0035).
        redactor?.retire("$id#old", name, old, rotationGraceMillis)
        redactor?.register(id, name, newValue)
        old.fill('\u0000')
        newValue.fill('\u0000')
    }

    override suspend fun delete(id: SecretId) {
        conn.prepareStatement("DELETE FROM secrets WHERE id=?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate()
        }
        redactor?.unregister(id)
    }

    override suspend fun resolve(id: SecretId): Result<CharArray> = runCatching {
        val e = readEntry(id)
        // A secret with an allow-list is for named consumers only; the runtime's anonymous
        // resolve() must not be a way around it.
        if (e.allowedConsumers.isNotEmpty()) error("secrets.consumer_required: $id has an allow-list; use resolveFor")
        load(e)
    }

    override suspend fun resolveFor(id: SecretId, request: SecretRequest): Result<CharArray> = runCatching {
        val e = readEntry(id)
        if (request.tainted) error("secrets.denied_tainted: a Run that read untrusted content cannot resolve secrets")
        if (e.allowedConsumers.isNotEmpty() && request.consumer.key !in e.allowedConsumers) {
            error("secrets.consumer_not_allowed: ${request.consumer.key} may not resolve $id")
        }
        val value = load(e)
        accessSink?.record(SecretAccess(id, request.consumer, request.capabilityId, request.attemptId, Clock.System.now()))
        value
    }

    override suspend fun setExpiry(id: SecretId, expiresAt: Instant?) {
        update("UPDATE secrets SET expires_at=? WHERE id=?", expiresAt?.toString(), id)
    }

    override suspend fun setAllowedConsumers(id: SecretId, consumers: List<ConsumerRef>) {
        update("UPDATE secrets SET allowed_consumers_json=? WHERE id=?", Json.encodeToString(consumers.map { it.key }), id)
    }

    private fun update(sql: String, a: String?, id: String) {
        val n = conn.prepareStatement(sql).use { st -> st.setString(1, a); st.setString(2, id); st.executeUpdate() }
        if (n == 0) error("secrets.not_found: $id")
    }

    private fun readEntry(id: SecretId): SecretEntry = conn.prepareStatement(
        "SELECT id,name,kind,allowed_consumers_json,created_at,expires_at,last_used_at,last_rotated_at FROM secrets WHERE id=?"
    ).use { st ->
        st.setString(1, id)
        st.executeQuery().use { rs ->
            if (!rs.next()) error("secrets.not_found: $id")
            rowToEntry(rs)
        }
    }

    /** Expiry check, decrypt, last_used bookkeeping and redactor registration. */
    private fun load(e: SecretEntry): CharArray {
        val exp = e.expiresAt
        if (exp != null && Clock.System.now() >= exp) error("secrets.expired: ${e.id} expired at $exp")
        val row = conn.prepareStatement("SELECT ciphertext,nonce FROM secrets WHERE id=?").use { st ->
            st.setString(1, e.id)
            st.executeQuery().use { rs -> rs.next(); rs.getBytes(1) to rs.getBytes(2) }
        }
        update("UPDATE secrets SET last_used_at=? WHERE id=?", Clock.System.now().toString(), e.id)
        val value = decrypt(e.id, row.first, row.second)
        // A copy is registered: the caller zeroes the returned array, which must not zero the redactor's.
        redactor?.register(e.id, e.name, value)
        return value
    }

    override suspend fun get(id: SecretId): Result<SecretEntry> = runCatching {
        conn.prepareStatement(
            "SELECT id,name,kind,allowed_consumers_json,created_at,expires_at,last_used_at,last_rotated_at FROM secrets WHERE id=?"
        ).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) error("secrets.not_found: $id")
                rowToEntry(rs)
            }
        }
    }

    override suspend fun getByName(name: String): Result<SecretEntry> = runCatching {
        conn.prepareStatement(
            "SELECT id,name,kind,allowed_consumers_json,created_at,expires_at,last_used_at,last_rotated_at FROM secrets WHERE name=?"
        ).use { stmt ->
            stmt.setString(1, name)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) error("secrets.not_found name: $name")
                rowToEntry(rs)
            }
        }
    }

    override suspend fun list(): List<SecretEntry> {
        val results = mutableListOf<SecretEntry>()
        conn.prepareStatement(
            "SELECT id,name,kind,allowed_consumers_json,created_at,expires_at,last_used_at,last_rotated_at FROM secrets"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                while (rs.next()) results.add(rowToEntry(rs))
            }
        }
        return results
    }

    // ── Crypto ─────────────────────────────────────────────────────────────────

    private fun encrypt(id: String, value: CharArray): Pair<ByteArray, ByteArray> {
        val bytes = charArrayToBytes(value)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(128, nonce))
        // The secret's id is authenticated, so a ciphertext copied onto another row fails to decrypt.
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val ciphertext = cipher.doFinal(bytes)
        bytes.fill(0)
        return ciphertext to nonce
    }

    private fun decrypt(id: String, ciphertext: ByteArray, nonce: ByteArray): CharArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(128, nonce))
        cipher.updateAAD(id.toByteArray(Charsets.UTF_8))
        val plaintext = cipher.doFinal(ciphertext)
        val chars = bytesToCharArray(plaintext)
        plaintext.fill(0)
        return chars
    }

    private fun charArrayToBytes(chars: CharArray): ByteArray =
        String(chars).toByteArray(Charsets.UTF_8)

    private fun bytesToCharArray(bytes: ByteArray): CharArray =
        String(bytes, Charsets.UTF_8).toCharArray()

    private fun rowToEntry(rs: java.sql.ResultSet): SecretEntry {
        val id = rs.getString(1)
        val name = rs.getString(2)
        val kind = SecretKind.valueOf(rs.getString(3))
        val consumersJson = rs.getString(4) ?: "[]"
        val consumers = Json.decodeFromString<List<String>>(consumersJson)
        val createdAt = Instant.parse(rs.getString(5))
        val expiresAt = rs.getString(6)?.let { Instant.parse(it) }
        val lastUsedAt = rs.getString(7)?.let { Instant.parse(it) }
        val lastRotatedAt = rs.getString(8)?.let { Instant.parse(it) }
        return SecretEntry(id, name, kind, consumers, createdAt, expiresAt, lastUsedAt, lastRotatedAt)
    }

    companion object {
        fun generateKey(): SecretKey {
            val gen = KeyGenerator.getInstance("AES")
            gen.init(256, SecureRandom())
            return gen.generateKey()
        }

        fun keyFromBytes(bytes: ByteArray): SecretKey = SecretKeySpec(bytes, "AES")
    }
}

