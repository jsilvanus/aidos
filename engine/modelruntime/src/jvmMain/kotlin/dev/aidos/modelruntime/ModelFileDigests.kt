package dev.aidos.modelruntime

import java.io.File
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/**
 * SHA-256 of model artifacts, computed once per file version (RFC-0022 digest verification).
 *
 * Hashing a multi-gigabyte GGUF takes seconds and evicts the page cache the loaded model is
 * mapped from. The backends used to hash on every `installed()` / `catalog()` call, and those
 * run several times per inference request (locality check, licence check, `/v1/models`), so a
 * request paid for one or more full reads of every installed model before it started.
 *
 * A digest is remembered for as long as the file's canonical path, length and modification time
 * are unchanged, and only in this process: a restart, a replaced download or a touched file is
 * hashed again. The integrity check is therefore still made against the file's real bytes; it is
 * just not repeated for bytes that have not changed.
 */
object ModelFileDigests {
    private class Entry(val length: Long, val lastModified: Long, val digest: String)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val locks = ConcurrentHashMap<String, Any>()

    /** Lowercase hex SHA-256 of [file], or the empty string when it is not a regular file. */
    fun sha256(file: File): String {
        if (!file.isFile) return ""
        val key = runCatching { file.canonicalPath }.getOrDefault(file.absolutePath)
        cached(key, file)?.let { return it }
        // One hash per file at a time: concurrent callers wait for the first result instead of
        // each reading the whole file.
        synchronized(locks.computeIfAbsent(key) { Any() }) {
            cached(key, file)?.let { return it }
            val length = file.length()
            val lastModified = file.lastModified()
            val digest = hash(file)
            // Only remember the result if the file did not change while it was being read.
            if (file.length() == length && file.lastModified() == lastModified) {
                entries[key] = Entry(length, lastModified, digest)
            }
            return digest
        }
    }

    /** Forgets [file]'s digest (after a delete or an in-place rewrite the caller knows about). */
    fun invalidate(file: File) {
        entries.remove(runCatching { file.canonicalPath }.getOrDefault(file.absolutePath))
    }

    private fun cached(key: String, file: File): String? {
        val entry = entries[key] ?: return null
        return entry.digest.takeIf { entry.length == file.length() && entry.lastModified == file.lastModified() }
    }

    private fun hash(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(256 * 1024)
        file.inputStream().use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
