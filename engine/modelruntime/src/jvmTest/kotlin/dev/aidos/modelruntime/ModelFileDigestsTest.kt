package dev.aidos.modelruntime

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/** RFC-0022 digest verification: the digest is of the file's real bytes, and is not recomputed while they are unchanged. */
class ModelFileDigestsTest {
    private val dir: File = Files.createTempDirectory("aidos-digests").toFile()

    @AfterTest
    fun cleanup() {
        dir.deleteRecursively()
    }

    private fun expected(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun sha256_matchesTheFileContent() {
        val file = File(dir, "a.gguf").apply { writeBytes(ByteArray(700_000) { (it % 251).toByte() }) }

        assertEquals(expected(file.readBytes()), ModelFileDigests.sha256(file))
    }

    @Test
    fun sha256_ofMissingFileIsEmpty() {
        assertEquals("", ModelFileDigests.sha256(File(dir, "missing.gguf")))
    }

    @Test
    fun sha256_isNotRecomputedWhileLengthAndModificationTimeAreUnchanged() {
        val file = File(dir, "b.gguf").apply { writeBytes("first".toByteArray()) }
        val stamp = file.lastModified()
        val first = ModelFileDigests.sha256(file)

        // Same length, same timestamp, different bytes: only a cache can still answer "first".
        // This is the documented limit of the cache, and how the test sees that it is one.
        file.writeBytes("other".toByteArray())
        file.setLastModified(stamp)

        assertEquals(first, ModelFileDigests.sha256(file))
    }

    @Test
    fun sha256_isRecomputedWhenTheFileChanges() {
        val file = File(dir, "c.gguf").apply { writeBytes("first".toByteArray()) }
        val first = ModelFileDigests.sha256(file)

        file.writeBytes("a replaced download".toByteArray())

        val second = ModelFileDigests.sha256(file)
        assertNotEquals(first, second)
        assertEquals(expected("a replaced download".toByteArray()), second)
    }

    @Test
    fun invalidate_forcesARehash() {
        val file = File(dir, "d.gguf").apply { writeBytes("first".toByteArray()) }
        val stamp = file.lastModified()
        ModelFileDigests.sha256(file)
        file.writeBytes("other".toByteArray())
        file.setLastModified(stamp)

        ModelFileDigests.invalidate(file)

        assertEquals(expected("other".toByteArray()), ModelFileDigests.sha256(file))
    }
}
