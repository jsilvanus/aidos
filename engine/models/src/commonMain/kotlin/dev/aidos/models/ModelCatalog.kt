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
    suspend fun reconcile(isValid: suspend (InstalledModel) -> Boolean): Result<List<String>> = Result.success(emptyList())
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

/** One file of a [ModelPackageDownloadRequest], saved as `<destinationDir>/<name>`. */
data class PackageFileRequest(
    val name: String,
    val downloadUrl: String,
    val expectedDigest: String? = null,
)

/**
 * A model made of several files that only work together (an ONNX graph with its external-data
 * sidecar, tokenizer and config), installed into one directory. `install` handles exactly one file.
 */
data class ModelPackageDownloadRequest(
    val modelId: String,
    val files: List<PackageFileRequest>,
    val destinationDir: String,
    val kind: ModelKind,
    val format: String,
    val backend: String? = null,
)

interface ModelInstallerWorkflow {
    /**
     * Downloads every file of [request] into its directory. All-or-nothing: if any file fails, the
     * directory is removed, so a graph is never left without its sidecar. The recorded path is the
     * directory and the recorded digest covers the file names and digests, so it changes if any part does.
     */
    suspend fun installPackage(request: ModelPackageDownloadRequest, onProgress: suspend (InstallerEvent) -> Unit = { }): Result<InstalledModel> =
        Result.failure(UnsupportedOperationException("package installs are not supported by this installer"))

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

    override suspend fun installPackage(
        request: ModelPackageDownloadRequest,
        onProgress: suspend (InstallerEvent) -> Unit,
    ): Result<InstalledModel> {
        val names = request.files.map { it.name }
        // Names become path components inside destinationDir; refuse anything that could leave it.
        val badName = names.firstOrNull { it.isEmpty() || it.contains('/') || it.contains('\\') || it == ".." || it == "." }
        if (badName != null) return Result.failure(IllegalArgumentException("Invalid package file name: '$badName'"))
        if (names.size != names.toSet().size) return Result.failure(IllegalArgumentException("Duplicate package file names for ${request.modelId}"))
        if (request.files.isEmpty()) return Result.failure(IllegalArgumentException("Package ${request.modelId} has no files"))
        return try {
            val metadata = buildJsonObject {
                put("package", "true")
                put("format", request.format)
                request.backend?.let { put("backend", it) }
                put("destination", request.destinationDir)
                put("files", names.joinToString(","))
            }.toString()
            catalogManager.addToCatalog(
                CatalogEntry(request.modelId, request.modelId, request.kind, "huggingface", request.files.first().downloadUrl, metadata, "")
            ).getOrThrow()

            val digests = mutableListOf<Pair<String, String>>()
            var total = 0L
            for (file in request.files) {
                var completed: DownloadEvent.Completed? = null
                val destination = "${request.destinationDir.trimEnd('/')}/${file.name}"
                downloadManager.download(file.downloadUrl, destination, file.expectedDigest).collect { event ->
                    when (event) {
                        is DownloadEvent.Started -> onProgress(InstallerEvent.DownloadStarted(request.modelId, event.totalBytes))
                        is DownloadEvent.Progress -> onProgress(InstallerEvent.DownloadProgress(request.modelId, event.bytesDownloaded, event.totalBytes))
                        is DownloadEvent.Completed -> completed = event
                        is DownloadEvent.DigestMismatch -> throw IllegalStateException("SHA-256 mismatch for ${file.name}: expected ${event.expectedDigest}, got ${event.actualDigest}")
                        is DownloadEvent.Failed -> throw IllegalStateException("${file.name}: ${event.reason}")
                    }
                }
                val done = completed ?: throw IllegalStateException("Download of ${file.name} did not complete")
                digests += file.name to done.actualDigest
                total += done.sizeBytes
            }
            onProgress(InstallerEvent.DigitVerified(request.modelId))
            val digest = packageDigest(digests)
            catalogManager.markInstalled(request.modelId, digest, request.destinationDir, total, null).getOrThrow()
            onProgress(InstallerEvent.InstallationComplete(request.modelId, ""))
            Result.success(InstalledModel(request.modelId, digest, request.destinationDir, total, null, "", propertiesJson = metadata))
        } catch (e: Exception) {
            downloadManager.deleteTree(request.destinationDir)
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
        // A package is installed as a directory; delete() on a non-empty directory is a silent no-op.
        installed?.let { downloadManager.deleteTree(it.path) }
        catalogManager.uninstall(modelId).getOrThrow()
        Result.success(Unit)
    } catch (e: Exception) {
        Result.failure(e)
    }
}

/**
 * Identifies a package by its parts: `name:sha256` lines sorted by name. Not itself a hash because
 * common code has no hashing primitive, and the per-file SHA-256s are what carry the integrity.
 */
internal fun packageDigest(parts: List<Pair<String, String>>): String =
    parts.sortedBy { it.first }.joinToString(";") { (name, digest) -> "$name:$digest" }
