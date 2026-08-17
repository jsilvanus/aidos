package dev.aidos.downloads

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Android implementation of the engine DownloadManager. */
class LocalDownloadManager(private val downloadDir: String) : DownloadManager {
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
            connection.setRequestProperty("User-Agent", "Aidos-Engine-Android")
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
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        file.write(buffer, 0, read)
                        downloaded += read
                        emit(DownloadEvent.Progress(downloaded, totalBytes))
                    }
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
        } catch (e: Exception) {
            emit(DownloadEvent.Failed(e.message ?: e::class.simpleName.orEmpty(), true))
        } finally { connection?.disconnect() }
    }
    override suspend fun canResume(destination: String, url: String): Boolean {
        val target = File(destination); val partial = File("$destination.partial")
        if (!target.exists() || target.length() == 0L || !partial.exists()) return false
        val lines = partial.readLines()
        return lines.firstOrNull() == url && target.length() == lines.getOrNull(1)?.toLongOrNull()
    }
    override suspend fun delete(destination: String) { File(destination).delete(); File("$destination.partial").delete() }
    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) { val read = input.read(buffer); if (read < 0) break; digest.update(buffer, 0, read) }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
