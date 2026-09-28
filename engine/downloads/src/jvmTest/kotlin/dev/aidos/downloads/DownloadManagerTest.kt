package dev.aidos.downloads

import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * `testDownloadSuccess` used to hit a real `https://huggingface.co/model.gguf` URL, which 404s
 * (that path doesn't exist) -- LocalDownloadManager correctly throws on a non-2xx response before
 * emitting Started (RFC-0022: report the HTTP error, don't pretend a 404 body is model weights),
 * so the test's own assertion that the first event is Started could never pass against that URL.
 * A live external dependency in a unit test is also just flaky by nature regardless. Replaced
 * with a local, in-process HTTP server serving a real 200 response, so the test verifies what it
 * says it does without depending on the network at all.
 */
class DownloadManagerTest {

    private val tempDir = Files.createTempDirectory("aidos-downloads-test").toFile()
    private val manager = LocalDownloadManager(tempDir.path)
    private var server: HttpServer? = null

    @AfterTest
    fun cleanup() {
        server?.stop(0)
        tempDir.deleteRecursively()
    }

    private fun startServer(body: ByteArray, status: Int = 200): String {
        val httpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        httpServer.createContext("/model.gguf") { exchange ->
            exchange.sendResponseHeaders(status, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        httpServer.start()
        server = httpServer
        return "http://127.0.0.1:${httpServer.address.port}/model.gguf"
    }

    @Test
    fun testDownloadSuccess() = runBlocking {
        val url = startServer("fake gguf weights".toByteArray())
        val destination = File(tempDir, "model.gguf").path

        val events = manager.download(url = url, destination = destination, expectedDigest = null).toList()

        assert(events.isNotEmpty()) { "Download should emit events" }
        assert(events.first() is DownloadEvent.Started) { "First event should be Started" }
        assert(events.last() is DownloadEvent.Completed) { "Last event should be Completed" }
    }

    @Test
    fun testDownloadReportsHttpErrorsInsteadOfStarting() = runBlocking {
        val url = startServer(ByteArray(0), status = 404)
        val destination = File(tempDir, "missing.gguf").path

        val events = manager.download(url = url, destination = destination, expectedDigest = null).toList()

        assert(events.size == 1) { "A 404 should produce exactly one event" }
        assert(events.first() is DownloadEvent.Failed) { "A 404 must be reported as Failed, not treated as Started" }
    }

    @Test
    fun testCanResumePartialDownload() = runBlocking {
        val destination = File(tempDir, "partial-model.gguf").path

        // No partial download exists yet
        assert(!manager.canResume(destination, "https://example.com/model.gguf"))
    }

    @Test
    fun testDeletePartialDownload() = runBlocking {
        val destination = File(tempDir, "partial-model.gguf").path
        manager.delete(destination)
        // Should not throw
    }
}
