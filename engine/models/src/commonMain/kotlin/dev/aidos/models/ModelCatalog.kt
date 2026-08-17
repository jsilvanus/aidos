package dev.aidos.models

import dev.aidos.downloads.DownloadEvent
import dev.aidos.downloads.DownloadManager
import dev.aidos.kernel.ModelKind
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

interface ModelCatalogManager {
    suspend fun addToCatalog(model: CatalogEntry): Result<Unit>
    suspend fun listCatalog(): Result<List<CatalogEntry>>
    suspend fun getCatalog(modelId: String): Result<CatalogEntry?>
    suspend fun listInstalled(): Result<List<InstalledModel>>
    suspend fun markInstalled(modelId: String, digest: String, path: String, sizeBytes: Long, quantization: String? = null): Result<Unit>
    suspend fun uninstall(modelId: String): Result<Unit>
    suspend fun updateInstalledMetadata(modelId: String, userLabel: String? = null, propertiesJson: String? = null): Result<Unit>
}

data class CatalogEntry(
    val id: String,
    val name: String,
    val kind: ModelKind,
    val provider: String,
    val remoteUrl: String? = null,
    val propertiesJson: String = "{}",
    val discoveredAt: String,
)

data class InstalledModel(
    val modelId: String,
    val digest: String,
    val path: String,
    val sizeBytes: Long,
    val quantization: String? = null,
    val installedAt: String,
    val lastLoadedAt: String? = null,
    val userLabel: String? = null,
    val propertiesJson: String = "{}",
)

/** A downloadable model artifact independent of its inference runtime. */
data class ModelDownloadRequest(
    val modelId: String,
    val artifactName: String,
    val downloadUrl: String,
    val expectedDigest: String? = null,
    val destination: String,
    val kind: ModelKind = ModelKind.LLM,
    val format: String = "unknown",
    val backend: String? = null,
    /** Kept for compatibility with the existing GGUF-oriented Android UI. */
    val quantization: String? = null,
)

interface ModelInstallerWorkflow {
    suspend fun install(request: ModelDownloadRequest, onProgress: suspend (InstallerEvent) -> Unit = { }): Result<InstalledModel>
    suspend fun resume(modelId: String, onProgress: suspend (InstallerEvent) -> Unit = { }): Result<InstalledModel>
    suspend fun uninstall(modelId: String): Result<Unit>
}

sealed interface InstallerEvent {
    data class DownloadStarted(val modelId: String, val totalBytes: Long?) : InstallerEvent
    data class DownloadProgress(val modelId: String, val bytesDownloaded: Long, val totalBytes: Long?) : InstallerEvent
    data class DownloadCompleted(val modelId: String, val actualDigest: String) : InstallerEvent
    data class DigitVerifying(val modelId: String) : InstallerEvent
    data class DigitVerified(val modelId: String) : InstallerEvent
    data class InstallationComplete(val modelId: String, val installedAt: String) : InstallerEvent
    data class InstallationFailed(val modelId: String, val reason: String) : InstallerEvent
}

/** Shared installation workflow for Android, CLI, and future engine hosts. */
class DefaultModelInstallerWorkflow(
    private val downloadManager: DownloadManager,
    private val catalogManager: ModelCatalogManager,
) : ModelInstallerWorkflow {
    override suspend fun install(
        request: ModelDownloadRequest,
        onProgress: suspend (InstallerEvent) -> Unit,
    ): Result<InstalledModel> {
        return try {
            val metadata = buildJsonObject {
                put("download_url", request.downloadUrl)
                put("artifact_name", request.artifactName)
                put("format", request.format)
                request.backend?.let { put("backend", it) }
                request.quantization?.let { put("quantization", it) }
                put("destination", request.destination)
                request.expectedDigest?.let { put("sha256", it) }
            }.toString()
            catalogManager.addToCatalog(
                CatalogEntry(request.modelId, request.modelId, request.kind, "huggingface", request.downloadUrl, metadata, "")
            ).getOrThrow()

            var completed: DownloadEvent.Completed? = null
            downloadManager.download(request.downloadUrl, request.destination, request.expectedDigest).collect { event ->
                when (event) {
                    is DownloadEvent.Started -> onProgress(InstallerEvent.DownloadStarted(request.modelId, event.totalBytes))
                    is DownloadEvent.Progress -> onProgress(InstallerEvent.DownloadProgress(request.modelId, event.bytesDownloaded, event.totalBytes))
                    is DownloadEvent.Completed -> {
                        onProgress(InstallerEvent.DigitVerifying(request.modelId))
                        completed = event
                        onProgress(InstallerEvent.DownloadCompleted(request.modelId, event.actualDigest))
                        onProgress(InstallerEvent.DigitVerified(request.modelId))
                    }
                    is DownloadEvent.DigestMismatch -> throw IllegalStateException("SHA-256 mismatch: expected ${event.expectedDigest}, got ${event.actualDigest}")
                    is DownloadEvent.Failed -> throw IllegalStateException(event.reason)
                }
            }

            val result = completed ?: throw IllegalStateException("Download did not complete")
            val installed = InstalledModel(
                modelId = request.modelId,
                digest = result.actualDigest,
                path = result.finalPath,
                sizeBytes = result.sizeBytes,
                quantization = request.quantization,
                installedAt = "",
                propertiesJson = metadata,
            )
            catalogManager.markInstalled(
                installed.modelId,
                installed.digest,
                installed.path,
                installed.sizeBytes,
                installed.quantization,
            ).getOrThrow()
            onProgress(InstallerEvent.InstallationComplete(request.modelId, installed.installedAt))
            Result.success(installed)
        } catch (e: Exception) {
            onProgress(InstallerEvent.InstallationFailed(request.modelId, e.message ?: "Installation failed"))
            Result.failure(e)
        }
    }

    override suspend fun resume(modelId: String, onProgress: suspend (InstallerEvent) -> Unit): Result<InstalledModel> {
        val catalog = catalogManager.getCatalog(modelId).getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalArgumentException("No download metadata for $modelId"))
        return try {
            val metadata = Json.parseToJsonElement(catalog.propertiesJson).jsonObject
            val url = metadata["download_url"]?.jsonPrimitive?.content ?: error("No download URL for $modelId")
            val destination = metadata["destination"]?.jsonPrimitive?.content ?: error("No destination for $modelId")
            val artifactName = metadata["artifact_name"]?.jsonPrimitive?.content ?: destination.substringAfterLast('/')
            val format = metadata["format"]?.jsonPrimitive?.content ?: "unknown"
            val backend = metadata["backend"]?.jsonPrimitive?.content
            val quantization = metadata["quantization"]?.jsonPrimitive?.content
            val digest = metadata["sha256"]?.jsonPrimitive?.content
            install(ModelDownloadRequest(modelId, artifactName, url, digest, destination, catalog.kind, format, backend, quantization), onProgress)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun uninstall(modelId: String): Result<Unit> = try {
        val installed = catalogManager.listInstalled().getOrThrow().firstOrNull { it.modelId == modelId }
        installed?.let { downloadManager.delete(it.path) }
        catalogManager.uninstall(modelId).getOrThrow()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}
