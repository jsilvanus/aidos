package fi.italeino.aidos.engine.control

import kotlinx.serialization.Serializable

/**
 * The models group of `EngineControl` (RFC-0115, D61), first part: what is installed, searching Hugging Face,
 * one-click installs of the suggested models, and deleting. The Engine does the downloading, so an install started
 * from a window carries on in a background service after the window closes. Failures are `IllegalArgumentException`
 * (unknown model or suggestion) and `IllegalStateException`, mapped to 400 and 409 by the admin API.
 */
interface ModelsControl {
    suspend fun installed(): List<ModelInfo>

    /** [kind] is a `ModelKind` name (LLM, EMBEDDING, ...) or null for any. */
    suspend fun searchRemote(query: String?, kind: String?, minContext: Int?, filters: ModelSearchFiltersInfo): List<ModelInfo>

    /** Starts installing a suggested model and returns at once; progress is in [installs]. A second start of the same model is ignored. */
    suspend fun startInstall(suggestionId: String)
    suspend fun installs(): List<InstallStatus>

    /** Unloads the model if it is resident, then removes its file and install record. */
    suspend fun delete(modelId: String)
}

/** [fitVerdict] is a `ModelFitVerdict` name (RUNS_WELL, RUNS_TIGHT, EXCEEDS_CONTEXT, WILL_NOT_FIT). */
@Serializable
data class ModelInfo(
    val id: String,
    val name: String,
    val kind: String,
    val quantization: String,
    val sizeBytes: Long? = null,
    val contextLength: Int,
    val fitVerdict: String,
    val isRunnable: Boolean = true,
)

@Serializable
data class ModelSearchFiltersInfo(
    val minSizeMb: Int? = null,
    val maxSizeMb: Int? = null,
    val fitsDeviceOnly: Boolean = false,
    val runnableOnly: Boolean = false,
)

/** [active] is false once an install has ended; [error] then says why it failed (null after a success, when the entry is dropped). */
@Serializable
data class InstallStatus(val modelId: String, val percent: Int = 0, val error: String? = null, val active: Boolean = true)

@Serializable
data class ModelSearchRequest(
    val query: String? = null,
    val kind: String? = null,
    val minContext: Int? = null,
    val filters: ModelSearchFiltersInfo = ModelSearchFiltersInfo(),
)

@Serializable
data class ModelInstallRequest(val suggestionId: String)
