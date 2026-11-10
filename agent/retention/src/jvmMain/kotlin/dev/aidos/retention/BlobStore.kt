package dev.aidos.retention

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.executor.LargeOutputStore
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Content-addressed blob storage with reference counting (RFC-0056, "Deduplication").
 *
 * Blobs live at `<dir>/<sha256>` (the daemon uses `<project>/.aidos/blobs`). Identical content
 * stores one file; `blob_refs.ref_count` says how many rows point at it, and the file goes away
 * when the last reference is released. An agent loop that re-reads the same file twenty times
 * therefore pays for it once.
 *
 * Implements [LargeOutputStore]: [externalize] puts a large Attempt output here and returns the
 * small reference JSON that the `attempts` row stores in its place.
 *
 * Crash safety: a file is written (to a temp name, then moved into place) before its row is
 * counted, so a crash leaves at worst an orphan file with no `blob_refs` row, which [sweep]
 * removes. A row is never counted for a file that does not exist.
 */
class BlobStore(
    private val driver: SqlDriver,
    private val dir: File,
    private val nowIso: () -> String,
    /** Outputs smaller than this stay inline in the `attempts` row. */
    private val inlineThresholdBytes: Int = 16 * 1024,
) : LargeOutputStore {

    /** Stores [bytes] (or finds the existing copy) and counts one more reference to it. */
    fun put(bytes: ByteArray): String {
        val hash = sha256Hex(bytes)
        ensureFile(hash, bytes)
        val now = nowIso()
        driver.update(
            "INSERT INTO blob_refs (content_hash, ref_count, size_bytes, last_accessed_at) VALUES (?, 1, ?, ?) " +
                "ON CONFLICT(content_hash) DO UPDATE SET ref_count = ref_count + 1, last_accessed_at = excluded.last_accessed_at",
            parameters = 3,
        ) { bindString(0, hash); bindLong(1, bytes.size.toLong()); bindString(2, now) }
        // A concurrent release of the last other reference may have deleted the file between the
        // write above and the count; writing it again is idempotent.
        ensureFile(hash, bytes)
        return hash
    }

    private fun ensureFile(hash: String, bytes: ByteArray) {
        val target = fileFor(hash)
        if (target.isFile) return
        dir.mkdirs()
        val tmp = File.createTempFile("blob-", TMP_SUFFIX, dir)
        try {
            tmp.writeBytes(bytes)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }

    /** The content for [hash], verified against its hash, or null if missing or corrupt. */
    fun get(hash: String): ByteArray? {
        val f = fileFor(hash)
        if (!f.isFile) return null
        val bytes = try { f.readBytes() } catch (_: IOException) { return null }
        if (sha256Hex(bytes) != hash) return null
        driver.update("UPDATE blob_refs SET last_accessed_at = ? WHERE content_hash = ?", 2) {
            bindString(0, nowIso()); bindString(1, hash)
        }
        return bytes
    }

    fun refCount(hash: String): Long =
        driver.queryLong("SELECT ref_count FROM blob_refs WHERE content_hash = ?", 1) { bindString(0, hash) }

    /** Total bytes of all stored blobs, for the project footprint. */
    fun totalBytes(): Long = driver.queryLong("SELECT COALESCE(SUM(size_bytes), 0) FROM blob_refs")

    /**
     * Drops one reference in the database only. Returns true when it was the last one, in which
     * case its `blob_refs` row is gone and the caller must call [deleteFile] after its own
     * transaction commits. Split from file deletion so a rolled-back transaction never loses a
     * file that is still referenced.
     */
    internal fun releaseRow(hash: String): Boolean {
        driver.update("UPDATE blob_refs SET ref_count = ref_count - 1 WHERE content_hash = ?", 1) { bindString(0, hash) }
        val remaining = driver.queryList(
            "SELECT ref_count FROM blob_refs WHERE content_hash = ?", 1, { bindString(0, hash) },
        ) { it.getLong(0) ?: 0L }.firstOrNull() ?: return false
        if (remaining > 0) return false
        driver.update("DELETE FROM blob_refs WHERE content_hash = ?", 1) { bindString(0, hash) }
        return true
    }

    internal fun deleteFile(hash: String) {
        fileFor(hash).delete()
    }

    /** Drops one reference and deletes the file if it was the last. */
    fun release(hash: String) {
        if (driver.inTransaction { releaseRow(hash) }) deleteFile(hash)
    }

    /**
     * EPHEMERAL cleanup, run on startup: removes temp files from interrupted writes and blob
     * files no `blob_refs` row counts. Returns the number of files removed.
     */
    fun sweep(): Int {
        val files = dir.listFiles() ?: return 0
        val counted = driver.queryList("SELECT content_hash FROM blob_refs") { it.getString(0)!! }.toHashSet()
        var removed = 0
        for (f in files) {
            if (!f.isFile) continue
            if (f.name.endsWith(TMP_SUFFIX) || f.name !in counted) {
                if (f.delete()) removed++
            }
        }
        return removed
    }

    override fun externalize(text: String): String? {
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.size < inlineThresholdBytes) return null
        return reference(put(bytes), bytes.size)
    }

    override fun resolve(stored: String): String {
        val ref = parseReference(stored) ?: return stored
        return get(ref.first)?.toString(Charsets.UTF_8) ?: stored
    }

    private fun fileFor(hash: String): File {
        require(HASH.matches(hash)) { "not a sha-256 hex digest: $hash" }
        return File(dir, hash)
    }

    companion object {
        private const val TMP_SUFFIX = ".tmp"
        private val HASH = Regex("[0-9a-f]{64}")
        private val REFERENCE = Regex("""^\{"blob_ref":"([0-9a-f]{64})","bytes":(\d+)}$""")

        /** The reference stored in an `attempts` column in place of the blob's content. */
        fun reference(hash: String, bytes: Int): String = """{"blob_ref":"$hash","bytes":$bytes}"""

        /** (hash, bytes) if [stored] is a blob reference, else null. */
        fun parseReference(stored: String): Pair<String, Long>? =
            REFERENCE.matchEntire(stored)?.let { it.groupValues[1] to it.groupValues[2].toLong() }

        fun sha256Hex(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
