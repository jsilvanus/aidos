package dev.aidos.models

import dev.aidos.kernel.ModelKind

/**
 * Model catalog manager (RFC-0022, RFC-0021).
 *
 * Operations on the model_catalog and installed_models tables.
 * Abstracts the database from higher-level model management code.
 */
interface ModelCatalogManager {
    /**
     * Add or update a model in the catalog.
     */
    suspend fun addToCatalog(model: CatalogEntry): Result<Unit>

    /**
     * Get all models in the catalog.
     */
    suspend fun listCatalog(): Result<List<CatalogEntry>>

    /**
     * Find a specific model in the catalog.
     */
    suspend fun getCatalog(modelId: String): Result<CatalogEntry?>

    /**
     * Get all installed models.
     */
    suspend fun listInstalled(): Result<List<InstalledModel>>

    /**
     * Mark a model as installed after download and verification.
     */
    suspend fun markInstalled(
        modelId: String,
        digest: String,
        path: String,
        sizeBytes: Long,
        quantization: String? = null,
    ): Result<Unit>

    /**
     * Remove an installed model.
     */
    suspend fun uninstall(modelId: String): Result<Unit>

    /**
     * Update metadata for an installed model (e.g., labels, last used).
     */
    suspend fun updateInstalledMetadata(
        modelId: String,
        userLabel: String? = null,
        propertiesJson: String? = null,
    ): Result<Unit>
}

/**
 * Catalog entry (model_catalog table).
 */
data class CatalogEntry(
    val id: String,
    val name: String,
    val kind: ModelKind,
    val provider: String,
    val remoteUrl: String? = null,
    val propertiesJson: String = "{}",
    val discoveredAt: String, // ISO 8601
)

/**
 * Installed model (installed_models table).
 */
data class InstalledModel(
    val modelId: String,
    val digest: String,
    val path: String,
    val sizeBytes: Long,
    val quantization: String? = null,
    val installedAt: String, // ISO 8601
    val lastLoadedAt: String? = null,
    val userLabel: String? = null,
    val propertiesJson: String = "{}",
)

/**
 * Model download request parameters.
 */
data class ModelDownloadRequest(
    val modelId: String,
    val quantization: String,
    val downloadUrl: String,
    val expectedDigest: String? = null,
    val destination: String,
)

/**
 * Model installation workflow (RFC-0022).
 *
 * Orchestrates the full flow: download → verify digest → record in installed_models.
 */
interface ModelInstallerWorkflow {
    /**
     * Start a model installation.
     *
     * @param request download parameters
     * @param onProgress callback for download progress
     * @return result of installation (success or error)
     */
    suspend fun install(
        request: ModelDownloadRequest,
        onProgress: suspend (InstallerEvent) -> Unit = { },
    ): Result<InstalledModel>

    /**
     * Attempt to resume a partial download.
     *
     * @param modelId model to resume
     * @param onProgress callback for download progress
     * @return result (success, or explanation why not resumable)
     */
    suspend fun resume(
        modelId: String,
        onProgress: suspend (InstallerEvent) -> Unit = { },
    ): Result<InstalledModel>

    /**
     * Remove an installed model and clean up its files.
     */
    suspend fun uninstall(modelId: String): Result<Unit>
}

/**
 * Events emitted during model installation.
 */
sealed interface InstallerEvent {
    /** Download started. */
    data class DownloadStarted(val modelId: String, val totalBytes: Long?) : InstallerEvent

    /** Download progress. */
    data class DownloadProgress(
        val modelId: String,
        val bytesDownloaded: Long,
        val totalBytes: Long?,
    ) : InstallerEvent

    /** Download completed. */
    data class DownloadCompleted(val modelId: String, val actualDigest: String) : InstallerEvent

    /** Digest verification. */
    data class DigitVerifying(val modelId: String) : InstallerEvent

    /** Digest match verified. */
    data class DigitVerified(val modelId: String) : InstallerEvent

    /** Installation complete. */
    data class InstallationComplete(val modelId: String, val installedAt: String) : InstallerEvent

    /** Installation failed. */
    data class InstallationFailed(val modelId: String, val reason: String) : InstallerEvent
}
