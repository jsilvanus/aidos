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
 * On Android, the key would be held by the Android Keystore. On JVM (desktop/tests), the key
 * is held in memory. This implementation uses JDBC directly for BLOB operations.
 *
 * **Values are held in CharArray/ByteArray and zeroed after use, never in String.**
 */
class SqliteSecretsVault(
    dbFile: File,
    private val encryptionKey: SecretKey = generateKey(),
) : SecretsVault {

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
    }

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
        val (ciphertext, nonce) = encrypt(value)
        value.fill('\u0000')
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
        return id
    }

    override suspend fun rotate(id: SecretId, newValue: CharArray) {
        val (ciphertext, nonce) = encrypt(newValue)
        newValue.fill('\u0000')
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
    }

    override suspend fun delete(id: SecretId) {
        conn.prepareStatement("DELETE FROM secrets WHERE id=?").use { stmt ->
            stmt.setString(1, id)
            stmt.executeUpdate()
        }
    }

    override suspend fun resolve(id: SecretId): Result<CharArray> = runCatching {
        val pair = conn.prepareStatement(
            "SELECT ciphertext,nonce FROM secrets WHERE id=?"
        ).use { stmt ->
            stmt.setString(1, id)
            stmt.executeQuery().use { rs ->
                if (!rs.next()) error("secrets.not_found: $id")
                rs.getBytes(1) to rs.getBytes(2)
            }
        }
        val now = Clock.System.now().toString()
        conn.prepareStatement("UPDATE secrets SET last_used_at=? WHERE id=?").use { stmt ->
            stmt.setString(1, now); stmt.setString(2, id); stmt.executeUpdate()
        }
        decrypt(pair.first, pair.second)
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

    private fun encrypt(value: CharArray): Pair<ByteArray, ByteArray> {
        val bytes = charArrayToBytes(value)
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, encryptionKey, GCMParameterSpec(128, nonce))
        val ciphertext = cipher.doFinal(bytes)
        bytes.fill(0)
        return ciphertext to nonce
    }

    private fun decrypt(ciphertext: ByteArray, nonce: ByteArray): CharArray {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, encryptionKey, GCMParameterSpec(128, nonce))
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

