package dev.aidos.downloads

import kotlinx.coroutines.flow.Flow

/**
 * Resumable model/file download abstraction.
 * Implementations download bytes, optionally resume a partial file, and verify SHA-256.
 */
interface DownloadManager {
    fun download(url: String, destination: String, expectedDigest: String?): Flow<DownloadEvent>
    suspend fun canResume(destination: String, url: String): Boolean
    suspend fun delete(destination: String)
}

sealed interface DownloadEvent {
    data class Started(val totalBytes: Long?, val resumeFromBytes: Long = 0) : DownloadEvent
    data class Progress(val bytesDownloaded: Long, val totalBytes: Long?) : DownloadEvent
    data class Completed(
        val finalPath: String,
        val actualDigest: String,
        val sizeBytes: Long = 0,
    ) : DownloadEvent
    data class Failed(val reason: String, val retryable: Boolean) : DownloadEvent
    data class DigestMismatch(val expectedDigest: String, val actualDigest: String) : DownloadEvent
}

data class PartialDownload(
    val modelId: String,
    val url: String,
    val destination: String,
    val bytesDownloaded: Long,
    val totalBytes: Long?,
    val startedAt: String,
    val lastUpdatedAt: String,
)

typealias DownloadProgress = DownloadEvent.Progress
