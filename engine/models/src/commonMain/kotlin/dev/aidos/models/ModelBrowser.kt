package dev.aidos.models

import dev.aidos.cookbook.CookbookEngine
import dev.aidos.cookbook.CookbookVerdict
import dev.aidos.cookbook.DeviceProfile
import dev.aidos.cookbook.PerformanceMeasurement
import dev.aidos.huggingface.HuggingFaceClient
import dev.aidos.huggingface.HuggingFaceModel
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind

/**
 * Model discovery and browser service (RFC-0022, RFC-0021, RFC-0020).
 *
 * Combines catalog, cookbook, and installed models to provide the view
 * shown in the UI model browser. Handles filtering, sorting, and cookbook verdicts.
 */
class ModelBrowser(
    private val catalogManager: ModelCatalogManager,
    private val hfClient: HuggingFaceClient,
    private val cookbookEngine: CookbookEngine,
    private val deviceProfile: DeviceProfile,
    private val defaultContextWindow: Int = 4096,
) {
    /**
     * Browse models in the catalog with cookbook verdicts and installed status.
     */
    suspend fun browse(
        kind: ModelKind? = null,
        query: String? = null,
        onlyInstalled: Boolean = false,
    ): Result<List<BrowsableModel>> = runCatching {
        val catalog = catalogManager.listCatalog().getOrThrow()
        val installed = catalogManager.listInstalled().getOrThrow()
        val installedMap = installed.associateBy { it.modelId }

        catalog
            .asSequence()
            .filter { kind == null || it.kind == kind }
            .filter { query == null || it.name.contains(query, ignoreCase = true) }
            .filter { !onlyInstalled || it.id in installedMap }
            .map { catalogEntry ->
                val installedModel = installedMap[catalogEntry.id]
                
                // Convert CatalogEntry to ModelDescriptor for cookbook verdict computation
                val descriptor = ModelDescriptor(
                    id = catalogEntry.id,
                    name = catalogEntry.name,
                    kind = catalogEntry.kind,
                    providerId = catalogEntry.provider,
                    isLocal = true,
                    contextWindow = defaultContextWindow,
                    sizeBytes = extractSizeBytes(catalogEntry),
                    digest = extractDigest(catalogEntry),
                )
                
                val verdict = cookbookEngine.verdict(descriptor, deviceProfile, defaultContextWindow)

                BrowsableModel(
                    catalogEntry = catalogEntry,
                    verdict = verdict,
                    installedModel = installedModel,
                    readableVerdict = verdict.humanReadable(),
                    contextWindow = defaultContextWindow,
                    sizeBytes = descriptor.sizeBytes,
                )
            }
            .sortedWith(compareBy(
                { it.verdict.ordinal }, // Fit verdict first
                { it.catalogEntry.name }, // Then by name
            ))
            .toList()
    }

    /**
     * Search models on Hugging Face with cookbook verdicts.
     */
    suspend fun searchRemote(
        query: String? = null,
        kind: ModelKind? = null,
        minContext: Int? = null,
    ): Result<List<BrowsableModel>> = runCatching {
        val hfFilter = mutableListOf("library:gguf")
        
        val searchResult = hfClient.search(
            query = query,
            filter = hfFilter.joinToString(","),
            sort = if (query.isNullOrBlank()) "trendingScore" else "downloads",
            limit = 30, // Increased limit for better discovery
        ).getOrThrow()

        val installed = catalogManager.listInstalled().getOrThrow()
        val installedMap = installed.associateBy { it.modelId }
        
        searchResult.models
            .asSequence()
            .map { hfModel ->
                // Pick best quantization (Q4_K_M or first available)
                val quant = hfModel.quantizations.find { it.name.contains("Q4_K_M") }
                    ?: hfModel.quantizations.firstOrNull()
                
                val modelKind = hfClient.inferModelKind(hfModel.tags, hfModel.pipeline)
                val contextWindow = hfModel.contextLength ?: defaultContextWindow
                
                val descriptor = ModelDescriptor(
                    id = hfModel.modelId,
                    name = hfModel.displayName ?: hfModel.modelId,
                    kind = modelKind,
                    providerId = "huggingface",
                    isLocal = false,
                    contextWindow = contextWindow,
                    sizeBytes = quant?.sizeBytes,
                    digest = quant?.sha256Digest,
                )
                
                val verdict = cookbookEngine.verdict(descriptor, deviceProfile, contextWindow)
                val installedModel = installedMap[hfModel.modelId]
                
                BrowsableModel(
                    catalogEntry = CatalogEntry(
                        id = hfModel.modelId,
                        name = hfModel.displayName ?: hfModel.modelId,
                        kind = modelKind,
                        provider = "huggingface",
                        remoteUrl = "https://huggingface.co/${hfModel.modelId}",
                        discoveredAt = "", // Transient
                    ),
                    verdict = verdict,
                    installedModel = installedModel,
                    readableVerdict = verdict.humanReadable(),
                    contextWindow = contextWindow,
                    sizeBytes = quant?.sizeBytes,
                )
            }
            .filter { kind == null || it.kind == kind }
            .filter { minContext == null || it.contextWindow >= minContext }
            .sortedWith(compareBy(
                { it.verdict.ordinal },
                { it.catalogEntry.name },
            ))
            .toList()
    }

    /**
     * Get detailed information about a specific model.
     */
    suspend fun getModelDetail(modelId: String): Result<ModelDetail> = runCatching {
        val catalogEntry = catalogManager.getCatalog(modelId).getOrThrow()
            ?: return@runCatching throw IllegalArgumentException("Model not found: $modelId")

        val installedModel = catalogManager.listInstalled().getOrThrow()
            .firstOrNull { it.modelId == modelId }

        val descriptor = ModelDescriptor(
            id = catalogEntry.id,
            name = catalogEntry.name,
            kind = catalogEntry.kind,
            providerId = catalogEntry.provider,
            isLocal = true,
            contextWindow = defaultContextWindow,
            sizeBytes = extractSizeBytes(catalogEntry),
            digest = extractDigest(catalogEntry),
        )
        
        val verdict = cookbookEngine.verdict(descriptor, deviceProfile, defaultContextWindow)

        ModelDetail(
            catalogEntry = catalogEntry,
            installedModel = installedModel,
            verdict = verdict,
            readableVerdict = verdict.humanReadable(),
            contextWindow = defaultContextWindow,
            sizeBytes = descriptor.sizeBytes,
        )
    }

    /**
     * Update the user-visible label for a model.
     */
    suspend fun setModelLabel(modelId: String, label: String): Result<Unit> =
        catalogManager.updateInstalledMetadata(modelId, userLabel = label)

    private fun extractSizeBytes(entry: CatalogEntry): Long? {
        // Extract size_bytes from propertiesJson if available
        // For now, return null to indicate we don't have the information
        // Real implementation would parse JSON
        return null
    }

    private fun extractDigest(entry: CatalogEntry): String? {
        // Extract digest from propertiesJson if available
        // For now, return null
        // Real implementation would parse JSON
        return null
    }
}

/**
 * A model as displayed in the browser.
 */
data class BrowsableModel(
    val catalogEntry: CatalogEntry,
    val verdict: CookbookVerdict,
    val installedModel: InstalledModel?,
    val readableVerdict: String,
    val contextWindow: Int,
    val sizeBytes: Long? = null,
) {
    val id: String get() = catalogEntry.id
    val name: String get() = catalogEntry.name
    val kind: ModelKind get() = catalogEntry.kind
    val provider: String get() = catalogEntry.provider
    val isInstalled: Boolean get() = installedModel != null
    val userLabel: String? get() = installedModel?.userLabel
}

/**
 * Detailed view of a model (browser detail pane).
 */
data class ModelDetail(
    val catalogEntry: CatalogEntry,
    val installedModel: InstalledModel?,
    val verdict: CookbookVerdict,
    val readableVerdict: String,
    val contextWindow: Int,
    val sizeBytes: Long? = null,
) {
    val id: String get() = catalogEntry.id
    val name: String get() = catalogEntry.name
    val kind: ModelKind get() = catalogEntry.kind
    val provider: String get() = catalogEntry.provider
    val remoteUrl: String? get() = catalogEntry.remoteUrl
    val isInstalled: Boolean get() = installedModel != null
    val userLabel: String? get() = installedModel?.userLabel
}

/**
 * Human-readable label for a cookbook verdict.
 */
private fun CookbookVerdict.humanReadable(): String = when (this) {
    CookbookVerdict.RUNS_WELL -> "Runs smoothly (>30% headroom)"
    CookbookVerdict.RUNS_TIGHT -> "Runs with caution (10-30% headroom)"
    CookbookVerdict.EXCEEDS_CONTEXT -> "Weights fit, context window too large"
    CookbookVerdict.WILL_NOT_FIT -> "Insufficient RAM for this model"
}
