package dev.aidos.downloads

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0022: a mismatched model file is quarantined, bounded, and never left in the store. */
class ModelQuarantineTest {
    private val store = Files.createTempDirectory("aidos-quarantine-test").toFile()
    private var server: HttpServer? = null

    @AfterTest
    fun cleanup() {
        server?.stop(0)
        store.deleteRecursively()
    }

    private fun modelFile(name: String, content: String = "weights"): File =
        File(store, name).apply { writeText(content) }

    @Test
    fun `mismatch moves the file and writes the sidecar`() {
        val file = modelFile("m.gguf")
        val q = assertNotNull(ModelQuarantine.quarantine(file, "m", "sha256:want", "sha256:abcdef0123456789", "https://x/m.gguf"))
        assertFalse(file.exists())
        assertEquals(File(store, "quarantine"), q.parentFile)
        assertEquals("m.gguf.abcdef012345.quarantined", q.name)
        val sidecar = File("${q.path}.json").readText()
        assertTrue("\"modelId\":\"m\"" in sidecar)
        assertTrue("\"expectedDigest\":\"sha256:want\"" in sidecar)
        assertTrue("\"actualDigest\":\"sha256:abcdef0123456789\"" in sidecar)
        assertTrue("\"sourceUrl\":\"https://x/m.gguf\"" in sidecar)
        assertTrue("quarantinedAtMillis" in sidecar)
    }

    @Test
    fun `a failed download with a wrong digest leaves nothing in the store but quarantine`() = runBlocking {
        val body = "not the weights".toByteArray()
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/m.gguf") { ex -> ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
        http.start(); server = http
        val dest = File(store, "m.gguf")
        val events = LocalDownloadManager(store.path)
            .download("http://127.0.0.1:${http.address.port}/m.gguf", dest.path, "0".repeat(64)).toList()
        assertTrue(events.last() is DownloadEvent.DigestMismatch)
        assertFalse(dest.exists())
        assertFalse(File(store, "m.gguf.partial").exists())
        assertEquals(1, File(store, "quarantine").listFiles { f -> f.name.endsWith(".quarantined") }!!.size)
    }

    @Test
    fun `a matching digest is untouched`() = runBlocking {
        val body = "good".toByteArray()
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(body).joinToString("") { "%02x".format(it) }
        val http = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        http.createContext("/m.gguf") { ex -> ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) } }
        http.start(); server = http
        val dest = File(store, "m.gguf")
        val events = LocalDownloadManager(store.path)
            .download("http://127.0.0.1:${http.address.port}/m.gguf", dest.path, digest).toList()
        assertTrue(events.last() is DownloadEvent.Completed)
        assertTrue(dest.exists())
        assertFalse(File(store, "quarantine").exists())
    }

    @Test
    fun `retention keeps the newest two and drops the oldest`() {
        val now = 10_000_000_000L
        val dir = File(store, "quarantine")
        for (i in 1..4) {
            val f = modelFile("m$i.gguf")
            val q = assertNotNull(ModelQuarantine.quarantine(f, "m$i", "e", "a$i", null, nowMillis = now + i * 1000L))
            assertTrue(q.exists())
        }
        val left = dir.listFiles { f -> f.name.endsWith(".quarantined") }!!.map { it.name.substringBefore(".gguf") }.sorted()
        assertEquals(listOf("m3", "m4"), left)
        assertEquals(2, dir.listFiles { f -> f.name.endsWith(".json") }!!.size) // sidecars follow their files
    }

    @Test
    fun `retention drops files older than seven days`() {
        val dir = File(store, "quarantine").apply { mkdirs() }
        val now = 10_000_000_000L
        val old = File(dir, "old.gguf.aaa.quarantined").apply { writeText("x"); setLastModified(now - ModelQuarantine.MAX_AGE_MILLIS - 1000) }
        val fresh = File(dir, "new.gguf.bbb.quarantined").apply { writeText("x"); setLastModified(now - 1000) }
        ModelQuarantine.enforceRetention(dir, now)
        assertFalse(old.exists())
        assertTrue(fresh.exists())
    }

    @Test
    fun `rename failure falls back to deleting the file`() {
        val file = modelFile("m.gguf")
        // A regular file where the quarantine directory should be makes the move impossible.
        val blocker = File(store, "blocked").apply { writeText("not a dir") }
        val result = ModelQuarantine.quarantine(file, "m", "e", "a", null, quarantineDir = blocker)
        assertNull(result)
        assertFalse(file.exists())
    }
}
