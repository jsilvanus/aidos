package dev.aidos.downloads

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/** Android implementation placeholder; platform HTTP implementation can be supplied by the app. */
class LocalDownloadManager(
    private val downloadDir: String,
) : DownloadManager {
    override fun download(url: String, destination: String, expectedDigest: String?): Flow<DownloadEvent> = flow {
        emit(DownloadEvent.Failed("Android DownloadManager implementation not yet wired", retryable = false))
    }

    override suspend fun canResume(destination: String, url: String): Boolean = false

    override suspend fun delete(destination: String) = Unit
}
