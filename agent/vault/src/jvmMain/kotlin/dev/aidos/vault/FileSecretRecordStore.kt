package dev.aidos.vault

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * [SecretRecordStore] in one JSON file (RFC-0035, amendment 2026-11-19). Used where there is no
 * JDBC SQLite, which is the Android host: the file sits in app-private storage and holds sealed
 * values and metadata only, never a key or a plaintext value.
 *
 * Every change rewrites the file through a temporary file and an atomic rename, so a crash leaves
 * either the old file or the new one. A file that does not parse stops the vault from opening
 * instead of being replaced: overwriting it would lose every secret in it.
 */
class FileSecretRecordStore(private val file: File) : SecretRecordStore {

    @Serializable
    private data class Row(
        val id: String,
        val name: String,
        val kind: String,
        val sealed: String,
        val allowedConsumers: List<String> = emptyList(),
        val createdAt: String,
        val expiresAt: String? = null,
        val lastUsedAt: String? = null,
        val lastRotatedAt: String? = null,
    )

    @Serializable
    private data class Doc(val version: Int = 1, val secrets: List<Row> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private var cache: LinkedHashMap<SecretId, SecretRecord>? = null

    @Synchronized override fun all(): List<SecretRecord> = loaded().values.toList()

    @Synchronized override fun put(record: SecretRecord) {
        val next = LinkedHashMap(loaded())
        next[record.id] = record
        write(next)
    }

    @Synchronized override fun remove(id: SecretId): Boolean {
        val next = LinkedHashMap(loaded())
        if (next.remove(id) == null) return false
        write(next)
        return true
    }

    private fun loaded(): LinkedHashMap<SecretId, SecretRecord> {
        cache?.let { return it }
        val map = LinkedHashMap<SecretId, SecretRecord>()
        if (file.exists()) {
            val doc = try {
                json.decodeFromString(Doc.serializer(), file.readText(Charsets.UTF_8))
            } catch (e: Exception) {
                throw IOException("The vault file ${file.name} cannot be read; it was left unchanged", e)
            }
            for (r in doc.secrets) map[r.id] = r.toRecord()
        }
        cache = map
        return map
    }

    private fun write(records: LinkedHashMap<SecretId, SecretRecord>) {
        file.parentFile?.mkdirs()
        val text = json.encodeToString(Doc.serializer(), Doc(secrets = records.values.map { it.toRow() }))
        val tmp = File(file.parentFile ?: File("."), file.name + ".tmp")
        tmp.writeText(text, Charsets.UTF_8)
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        cache = records
    }

    private fun Row.toRecord() = SecretRecord(
        id = id,
        name = name,
        kind = SecretKind.valueOf(kind),
        sealed = Base64.getDecoder().decode(sealed),
        allowedConsumers = allowedConsumers,
        createdAt = Instant.parse(createdAt),
        expiresAt = expiresAt?.let(Instant::parse),
        lastUsedAt = lastUsedAt?.let(Instant::parse),
        lastRotatedAt = lastRotatedAt?.let(Instant::parse),
    )

    private fun SecretRecord.toRow() = Row(
        id = id,
        name = name,
        kind = kind.name,
        sealed = Base64.getEncoder().encodeToString(sealed),
        allowedConsumers = allowedConsumers,
        createdAt = createdAt.toString(),
        expiresAt = expiresAt?.toString(),
        lastUsedAt = lastUsedAt?.toString(),
        lastRotatedAt = lastRotatedAt?.toString(),
    )
}
