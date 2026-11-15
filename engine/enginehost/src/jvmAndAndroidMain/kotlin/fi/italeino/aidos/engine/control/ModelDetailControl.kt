package fi.italeino.aidos.engine.control

import kotlinx.serialization.Serializable

/**
 * The model detail group of `EngineControl` (RFC-0115, D61): one model's facts, license, download, residency (load and
 * unload), run settings and its self tests. The Engine owns all of it, so a download or a loaded model belongs to a
 * background service and not to the window that asked. Failures are `IllegalArgumentException` (unknown model, bad
 * input) and `IllegalStateException` (not possible now, with a message for the user), mapped to 400 and 409 by the
 * admin API. Deleting a model is [ModelsControl.delete].
 */
interface ModelDetailControl {
    suspend fun detail(modelId: String): ModelDetailInfo

    /** The install on disk: present only when the recorded file or package exists at its recorded size. */
    suspend fun installedState(modelId: String): InstalledModelInfo

    /** True when the model needs no license acceptance, or the user has already accepted its terms. */
    suspend fun licenseAccepted(modelId: String): Boolean
    suspend fun acceptLicense(modelId: String)

    /** Starts installing from Hugging Face and returns at once; progress is in [downloadStatus]. A second start is ignored. */
    suspend fun startDownload(modelId: String)

    /** Null when no download was started (or the last one succeeded and was reported once). */
    suspend fun downloadStatus(modelId: String): DownloadInfo?
    suspend fun cancelDownload(modelId: String)

    suspend fun residency(modelId: String): ResidencyInfo

    /** Loads the installed model. Throws `IllegalStateException` with the reason (Engine not ready, not installed, load failed). */
    suspend fun load(modelId: String)
    suspend fun unload(modelId: String)

    suspend fun runSettings(modelId: String): RunSettingsInfo

    /** Saved at once; the Engine reads them the next time it loads the model. */
    suspend fun setRunSettings(modelId: String, settings: RunSettingsInfo)

    /** Embeds [input] through the Engine's own `/v1/embeddings`. */
    suspend fun embed(modelId: String, input: String): List<Float>

    /** The test for the model's kind (chat, decisions, transcription, vision), through the Engine's own endpoint. */
    suspend fun selfTest(modelId: String, text: String, file: String): SelfTestInfo
}

/** [kind] is a `ModelKind` name; verdicts are `ModelFitVerdict` names. */
@Serializable
data class ModelDetailInfo(
    val id: String,
    val name: String,
    val description: String = "",
    val providerName: String,
    val kind: String,
    val licenseName: String? = null,
    val modelUrl: String? = null,
    val sizeMB: Int,
    val contextFitTable: List<ContextFitInfo> = emptyList(),
    val isRunnable: Boolean = true,
    val format: String = "",
    val deviceTotalRamMB: Int = 0,
    val deviceFreeRamMB: Int = 0,
    val baselineFreeRamMB: Int? = null,
)

@Serializable
data class ContextFitInfo(val contextLength: Int, val verdict: String, val estimatedMemoryMB: Int, val baselineVerdict: String? = null)

@Serializable
data class InstalledModelInfo(val installed: Boolean, val path: String? = null, val sizeBytes: Long? = null, val digest: String? = null)

@Serializable
data class DownloadInfo(val active: Boolean, val percent: Int = 0, val error: String? = null)

@Serializable
data class ResidencyInfo(val loaded: Boolean, val loadedSettings: RunSettingsInfo? = null)

@Serializable
data class RunSettingsInfo(
    val contextLength: Int? = null,
    val threads: Int? = null,
    val batchSize: Int? = null,
    val gpuLayers: Int? = null,
    val temperature: Float? = null,
    val topP: Float? = null,
    val topK: Int? = null,
    val useMmap: Boolean? = null,
)

@Serializable
data class SelfTestInfo(
    val passed: Boolean,
    val title: String,
    val lines: List<String> = emptyList(),
    val durationMs: Long = 0,
    val generatedTokens: Int? = null,
)

@Serializable data class ModelTextRequest(val text: String = "", val file: String = "")
@Serializable data class ModelVectorResponse(val vector: List<Float>)
@Serializable data class ModelFlag(val value: Boolean)
