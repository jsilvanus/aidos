package dev.aidos.downloads

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

class LocalDownloadManager(private val downloadDir: String, private val tokenProvider: (() -> String?)? = null) : DownloadManager {
    override fun download(url: String, destination: String, expectedDigest: String?): Flow<DownloadEvent> = flow {
        val target = File(destination)
        target.parentFile?.mkdirs()
        File(downloadDir).mkdirs()
        val partialFile = File("$destination.partial")
        var connection: HttpURLConnection? = null
        try {
            val existingBytes = if (target.exists()) target.length() else 0L
            val resumeBytes = if (partialFile.exists() && existingBytes > 0) existingBytes else 0L
            connection = URI(url).toURL().openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 30_000
            connection.readTimeout = 60_000
            connection.setRequestProperty("User-Agent", "Aidos-Engine")
            val token = tokenProvider?.invoke()?.takeIf { it.isNotBlank() }
            if (token != null && (url.contains("huggingface.co") || url.contains("hf.co"))) {
                connection.setRequestProperty("Authorization", "Bearer $token")
            }
            if (resumeBytes > 0) connection.setRequestProperty("Range", "bytes=$resumeBytes-")
            connection.connect()
            val response = connection.responseCode
            if (response !in 200..299 && response != HttpURLConnection.HTTP_PARTIAL) throw IllegalStateException("HTTP $response from $url")
            val resumed = resumeBytes > 0 && response == HttpURLConnection.HTTP_PARTIAL
            val offset = if (resumed) resumeBytes else 0L
            if (!resumed && target.exists()) target.delete()
            val contentLength = connection.contentLengthLong.takeIf { it >= 0 }
            val totalBytes = if (resumed && contentLength != null) offset + contentLength else contentLength
            partialFile.writeText("$url\n$offset\n${totalBytes ?: -1}")
            emit(DownloadEvent.Started(totalBytes, offset))
            RandomAccessFile(target, "rw").use { file ->
                file.seek(offset)
                connection.inputStream.use { input ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var downloaded = offset
                    var lastEmit = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        file.write(buffer, 0, read)
                        downloaded += read
                        // One event per 8 KB buffer is thousands a second on a fast link; the UI only needs a few per second.
                        val now = System.nanoTime()
                        if (now - lastEmit >= PROGRESS_INTERVAL_NANOS) {
                            lastEmit = now
                            emit(DownloadEvent.Progress(downloaded, totalBytes))
                        }
                    }
                    emit(DownloadEvent.Progress(downloaded, totalBytes))
                }
            }
            val actualDigest = sha256(target)
            if (expectedDigest != null && !actualDigest.equals(expectedDigest, ignoreCase = true)) {
                target.delete(); partialFile.delete()
                emit(DownloadEvent.DigestMismatch(expectedDigest, actualDigest))
                return@flow
            }
            partialFile.delete()
            emit(DownloadEvent.Completed(target.absolutePath, actualDigest, target.length()))
        } catch (e: CancellationException) {
            // Intentional: keep the target and marker so a later download can resume.
            throw e
        } catch (e: Exception) {
            emit(DownloadEvent.Failed(e.message ?: e::class.simpleName.orEmpty(), true))
        } finally { connection?.disconnect() }
    }
        // Blocking socket, file and SHA-256 work: without this, collecting from the desktop UI's Swing
        // coroutine runs all of it on the event dispatch thread and the window freezes while a model downloads.
        .flowOn(Dispatchers.IO)
    override suspend fun canResume(destination: String, url: String): Boolean {
        val target = File(destination); val partial = File("$destination.partial")
        if (!target.exists() || target.length() == 0L || !partial.exists()) return false
        val lines = partial.readLines()
        return lines.firstOrNull() == url && target.length() == lines.getOrNull(1)?.toLongOrNull()
    }
    override suspend fun delete(destination: String) { File(destination).delete(); File("$destination.partial").delete() }
    override suspend fun deleteTree(destination: String) { File(destination).deleteRecursively() }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
    private companion object { const val PROGRESS_INTERVAL_NANOS = 100_000_000L }
}
