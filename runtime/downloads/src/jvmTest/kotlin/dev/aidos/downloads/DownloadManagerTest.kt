package dev.aidos.downloads

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking

class DownloadManagerTest {

    private val manager = LocalDownloadManager("/tmp/aidos-downloads")

    @Test
    fun testDownloadSuccess() = runBlocking {
        val events = manager.download(
            url = "https://huggingface.co/model.gguf",
            destination = "/tmp/model.gguf",
            expectedDigest = "abc123def456",
        ).toList()

        assert(events.isNotEmpty()) { "Download should emit events" }
        assert(events.first() is DownloadEvent.Started) { "First event should be Started" }
    }

    @Test
    fun testCanResumePartialDownload() = runBlocking {
        val destination = "/tmp/partial-model.gguf"

        // No partial download exists yet
        assert(!manager.canResume(destination, "https://example.com/model.gguf"))
    }

    @Test
    fun testDeletePartialDownload() = runBlocking {
        val destination = "/tmp/partial-model.gguf"
        manager.delete(destination)
        // Should not throw
    }
}
