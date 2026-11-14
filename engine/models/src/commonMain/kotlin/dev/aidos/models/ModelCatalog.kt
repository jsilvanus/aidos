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
    /** Optional license text/id shown at download time; when present, inference is gated on acceptance. */
    val licenseText: String? = null,
    /** Stable digest of [licenseText] that keys acceptance records and re-prompts on change. */
    val licenseTextDigest: String? = null,
    /** Model version/revision used with [licenseTextDigest] in acceptance records. */
    val modelVersion: String? = null,
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
    /** Optional license text/id shown at download time; when present, inference is gated on acceptance. */
    val licenseText: String? = null,
    /** Stable digest of [licenseText] that keys acceptance records and re-prompts on change. */
    val licenseTextDigest: String? = null,
    /** Model version/revision used with [licenseTextDigest] in acceptance records. */
    val modelVersion: String? = null,
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
        validateRelativeArtifactPath(request.artifactName)?.let { reason ->
            return Result.failure(IllegalArgumentException("Invalid artifact name '${request.artifactName}': $reason"))
        }
        validateDestinationPath(request.destination)?.let { reason ->
            return Result.failure(IllegalArgumentException("Invalid destination '${request.destination}': $reason"))
        }
        return try {
            val metadata = buildJsonObject {
                put("download_url", request.downloadUrl)
                put("artifact_name", request.artifactName)
                put("format", request.format)
                request.backend?.let { put("backend", it) }
                request.quantization?.let { put("quantization", it) }
                put("destination", request.destination)
                request.expectedDigest?.let { put("sha256", it) }
                request.licenseText?.takeIf { it.isNotBlank() }?.let { put("license_text", it) }
                request.licenseTextDigest?.takeIf { it.isNotBlank() }?.let { put("license_digest", it) }
                request.modelVersion?.takeIf { it.isNotBlank() }?.let { put("version", it) }
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
        validateDestinationPath(request.destinationDir)?.let { reason ->
            return Result.failure(IllegalArgumentException("Invalid package destination directory '${request.destinationDir}': $reason"))
        }
        val names = request.files.map { it.name }
        // Names become path components inside destinationDir; refuse anything that could leave it.
        val badName = names.firstOrNull { !isSafeSingleFileName(it) }
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
                request.licenseText?.takeIf { it.isNotBlank() }?.let { put("license_text", it) }
                request.licenseTextDigest?.takeIf { it.isNotBlank() }?.let { put("license_digest", it) }
                request.modelVersion?.takeIf { it.isNotBlank() }?.let { put("version", it) }
            }.toString()
            catalogManager.addToCatalog(
                CatalogEntry(request.modelId, request.modelId, request.kind, "huggingface", request.files.first().downloadUrl, metadata, "")
            ).getOrThrow()

            val digests = mutableListOf<Pair<String, String>>()
            var total = 0L
            for (file in request.files) {
                var completed: DownloadEvent.Completed? = null
                val destination = resolveCatalogArtifactPath(request.destinationDir, file.name).getOrThrow()
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
            val artifactName = metadata["artifact_name"]?.jsonPrimitive?.content
                ?: destination.substringAfterLast('/').substringAfterLast('\\')
            val format = metadata["format"]?.jsonPrimitive?.content ?: "unknown"
            val backend = metadata["backend"]?.jsonPrimitive?.content
            val quantization = metadata["quantization"]?.jsonPrimitive?.content
            val digest = metadata["sha256"]?.jsonPrimitive?.content
            val licenseText = metadata["license_text"]?.jsonPrimitive?.content
            val licenseDigest = metadata["license_digest"]?.jsonPrimitive?.content
            val modelVersion = metadata["version"]?.jsonPrimitive?.content
            install(
                ModelDownloadRequest(
                    modelId = modelId,
                    artifactName = artifactName,
                    downloadUrl = url,
                    expectedDigest = digest,
                    destination = destination,
                    kind = catalog.kind,
                    format = format,
                    backend = backend,
                    quantization = quantization,
                    licenseText = licenseText,
                    licenseTextDigest = licenseDigest,
                    modelVersion = modelVersion,
                ),
                onProgress,
            )
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

private data class NormalizedPathParts(
    val root: String,
    val segments: List<String>,
    val normalized: String,
)

private fun normalizePathParts(rawPath: String): NormalizedPathParts? {
    if (rawPath.isBlank()) return null
    if (rawPath.any { it == '\u0000' || it.code < 0x20 }) return null

    val slashed = rawPath.replace('\\', '/')
    val root: String
    val remainder: String

    when {
        slashed.startsWith("//") -> {
            val parts = slashed.substring(2).split('/')
            if (parts.size < 2 || parts[0].isEmpty() || parts[1].isEmpty()) return null
            root = "//${parts[0]}/${parts[1]}/"
            remainder = parts.drop(2).joinToString("/")
        }
        slashed.length >= 2 && slashed[0].isLetter() && slashed[1] == ':' -> {
            // Require explicit separator after drive prefix so unanchored `C:foo` is rejected.
            if (slashed.length > 2 && slashed[2] != '/') return null
            root = "${slashed[0].uppercaseChar()}:/"
            remainder = if (slashed.length > 3) slashed.substring(3) else ""
        }
        slashed.startsWith('/') -> {
            root = "/"
            remainder = slashed.substring(1)
        }
        else -> {
            root = ""
            remainder = slashed
        }
    }

    val segments = mutableListOf<String>()
    if (remainder.isNotEmpty()) {
        for (segment in remainder.split('/')) {
            when {
                segment.isEmpty() || segment == "." -> continue
                segment == ".." -> {
                    if (segments.isEmpty()) return null
                    segments.removeAt(segments.lastIndex)
                }
                segment.contains(':') -> return null
                else -> segments.add(segment)
            }
        }
    }

    val normalized = root + segments.joinToString("/")
    return NormalizedPathParts(root = root, segments = segments, normalized = normalized)
}

private fun isSafeSingleFileName(name: String): Boolean {
    if (name.isEmpty() || name == "." || name == "..") return false
    if (name.contains('/') || name.contains('\\') || name.contains(':')) return false
    if (name.any { it == '\u0000' || it.code < 0x20 }) return false
    return true
}

private fun validateRelativeArtifactPath(relativePath: String): String? {
    if (relativePath.isBlank()) return "path is blank"
    if (relativePath.any { it == '\u0000' || it.code < 0x20 }) return "path contains NUL or control characters"
    if (relativePath.contains('\\')) return "backslashes are not permitted"
    if (relativePath.startsWith('/')) return "absolute paths are not permitted"
    if (relativePath.contains(':')) return "drive prefixes and colons are not permitted"
    for (segment in relativePath.split('/')) {
        if (segment.isEmpty() || segment == "." || segment == "..") {
            return "path contains empty or traversal segment '$segment'"
        }
    }
    return null
}

private fun validateDestinationPath(path: String): String? {
    if (path.isBlank()) return "destination path is blank"
    if (path.any { it == '\u0000' || it.code < 0x20 }) return "destination contains NUL or control characters"
    if (path.replace('\\', '/').split('/').any { it == ".." }) {
        return "destination contains parent traversal '..'"
    }
    return null
}

/**
 * Checks whether [candidatePath] normalizes strictly inside [baseDir] using both
 * segment-level prefix equality and trailing-separator boundary verification so sibling prefixes
 * like `models_evil/foo` against `models` cannot pass.
 */
fun isPathWithinBaseDir(baseDir: String, candidatePath: String): Boolean {
    val base = normalizePathParts(baseDir) ?: return false
    val candidate = normalizePathParts(candidatePath) ?: return false
    if (base.root != candidate.root) return false
    if (candidate.segments.size <= base.segments.size) return false
    if (candidate.segments.subList(0, base.segments.size) != base.segments) return false
    if (base.normalized.isEmpty()) return candidate.segments.isNotEmpty()
    val basePrefix = if (base.normalized.endsWith("/")) base.normalized else "${base.normalized}/"
    return candidate.normalized.startsWith(basePrefix)
}

/**
 * Resolves [relativePath] safely inside [modelsDir], rejecting traversal segments (`..`),
 * absolute paths, Windows drive letters, NUL bytes, backslashes, and prefix-sibling escapes.
 */
fun resolveCatalogArtifactPath(modelsDir: String, relativePath: String): Result<String> {
    val base = normalizePathParts(modelsDir)
        ?: return Result.failure(IllegalArgumentException("Invalid models base directory: '$modelsDir'"))
    validateRelativeArtifactPath(relativePath)?.let { reason ->
        return Result.failure(IllegalArgumentException("Invalid catalog relative path '$relativePath': $reason"))
    }
    val segments = relativePath.split('/')
    val resolved = if (base.normalized.isEmpty()) {
        segments.joinToString("/")
    } else {
        "${base.normalized.trimEnd('/')}/${segments.joinToString("/")}"
    }
    if (!isPathWithinBaseDir(modelsDir, resolved)) {
        return Result.failure(IllegalArgumentException("Catalog path '$relativePath' escapes models directory '$modelsDir'"))
    }
    return Result.success(resolved)
}
