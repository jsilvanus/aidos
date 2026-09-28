package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aidos.cookbook.CookbookVerdict
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.models.DefaultModelInstallerWorkflow
import dev.aidos.models.ModelDownloadRequest
import fi.italeino.aidos.engine.EngineService
import kotlinx.coroutines.CancellationException
import io.ktor.client.HttpClient
import io.ktor.client.engine.android.Android
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import fi.italeino.aidos.engine.http.AndroidEffectBroker
import dev.aidos.kernel.BasicResourceHandle
import dev.aidos.kernel.CapabilityId
import dev.aidos.huggingface.HuggingFaceClient
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import app.cash.sqldelight.db.AfterVersion
import dev.aidos.models.DatabaseModelCatalogManager
import dev.aidos.cookbook.CookbookEngine
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** ViewModel for Model Detail and Download (RFC-0103 Phase E). */
class ModelDetailViewModel : ViewModel() {
    private val _state = MutableStateFlow(ModelDetailState())
    val state: StateFlow<ModelDetailState> = _state.asStateFlow()
    private var downloadJob: Job? = null

    fun loadModelDetail(modelId: String) {
        val service = EngineService.instance

        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                // Use Engine's ModelBrowser when available; otherwise construct a local one
                if (service != null && service.modelBrowser != null) {
                    val detail = withContext(Dispatchers.IO) { service.modelBrowser!!.getModelDetail(modelId).getOrThrow() }
                    val license = if (detail.provider == "huggingface") {
                        withContext(Dispatchers.IO) { service.hfClient?.getModel(modelId)?.getOrNull()?.license }
                    } else null
                    _state.value = _state.value.copy(model = detail.toUiModel(license), isLoading = false)
                } else {
                    // Fallback: call Hugging Face directly and build a UI ModelDetail from the HF metadata
                    val localHttp = HttpClient(Android) { install(ContentNegotiation) { json() } }
                    val localBroker = AndroidEffectBroker(localHttp)
                    val hfHandle = BasicResourceHandle(CapabilityId("huggingface"))
                    val localHf = HuggingFaceClient(localBroker, hfHandle)

                    val hfModel = withContext(Dispatchers.IO) { localHf.getModel(modelId).getOrThrow() }
                    val device = dev.aidos.cookbook.DeviceProfile(
                        totalRamBytes = 8_000_000_000,
                        availableRamBytes = 4_000_000_000,
                        storageFreeBytes = 10_000_000_000,
                        cpuCoreCount = 8,
                        hasAccelerator = false,
                    )
                    val cookbook = CookbookEngine()
                    val modelSize = hfModel.modelSize
                    val contextWindow = hfModel.contextLength ?: 4096

                    val descriptor = ModelDescriptor(
                        hfModel.modelId,
                        hfModel.displayName ?: hfModel.modelId,
                        dev.aidos.kernel.ModelKind.LLM,
                        "huggingface",
                        false,
                        contextWindow,
                        modelSize,
                        null,
                    )

                    val contexts = listOf(4096, 8192, 16384, 32768)
                    val fitRows = contexts.map { ctx ->
                        val verdict = cookbook.verdict(descriptor, device, ctx)
                        val req = dev.aidos.cookbook.ModelRequirements(modelSize ?: 0L, ctx, 0, "GGUF")
                        val mem = cookbook.computeResidentMemory(req, device, ctx)
                        ContextFitRow(ctx / 1024, verdict.toUiVerdict(), (mem / (1024 * 1024)).toInt())
                    }

                    val inferredKind = dev.aidos.huggingface.HuggingFaceClient.Companion.inferModelKind(hfModel.tags, hfModel.pipeline)
                    val uiDetail = ModelDetail(
                        id = hfModel.modelId,
                        name = hfModel.displayName ?: hfModel.modelId,
                        description = hfModel.description ?: "",
                        providerName = "huggingface",
                        kind = dev.aidos.kernel.ModelKind.valueOf(inferredKind.name),
                        licenseName = hfModel.license,
                        modelUrl = "https://huggingface.co/${hfModel.modelId}",
                        sizeMB = ((modelSize ?: 0L) / (1024L * 1024L)).toInt(),
                        contextFitTable = fitRows,
                        isRunnable = hfModel.quantizations.isNotEmpty() || hfModel.artifacts.any { it.format == dev.aidos.huggingface.ModelFormat.GGUF },
                    )

                    _state.value = _state.value.copy(model = uiDetail, isLoading = false)
                }
                refreshInstalledState(modelId)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    /**
     * Reconcile the UI's installed state against the persistent catalog and filesystem.
     *
     * The catalog is authoritative for which artifact belongs to a model, but a catalog row
     * alone is not enough: the artifact must still exist and have the recorded size. This makes
     * the UI recover correctly after app restarts, manual file deletion, or interrupted installs.
     */
    fun refreshInstalledState(modelId: String) {
        val catalog = EngineService.instance?.catalogManager ?: return
        viewModelScope.launch {
            val installed = catalog.listInstalled()
                .getOrNull()
                ?.firstOrNull { it.modelId == modelId }
            val file = installed?.path?.let(::File)
            val valid = installed != null && file != null && file.isFile &&
                file.length() == installed.sizeBytes && installed.sizeBytes > 0L

            _state.value = _state.value.copy(
                isInstalled = valid,
                installedPath = if (valid) installed?.path else null,
                installedSizeBytes = if (valid) installed?.sizeBytes else null,
                installedDigest = if (valid) installed?.digest else null,
            )
        }
    }

    /** Install the selected Hugging Face GGUF through the engine's shared workflow. */
    fun startDownload() {
        if (downloadJob?.isActive == true) return

        val model = _state.value.model ?: return
        val service = EngineService.instance ?: return
        val browser = service.modelBrowser ?: return
        val hf = service.hfClient ?: return
        val downloader = service.downloadManager ?: return
        val catalog = service.catalogManager ?: return

        downloadJob = viewModelScope.launch {
            _state.value = _state.value.copy(
                isDownloading = true,
                downloadProgress = 0,
                downloadError = null,
                error = null,
            )
            try {
                val detail = browser.getModelDetail(model.id).getOrThrow()
                val remote = hf.getModel(model.id).getOrThrow()

                // Prefer known GGUF quantizations (Q4_K_M), but fall back to any available artifact
                val chosenArtifact = when {
                    remote.quantizations.isNotEmpty() -> {
                        // Prefer Q4_K_M when present
                        val q = remote.quantizations.find { it.name.contains("Q4_K_M", ignoreCase = true) }
                            ?: remote.quantizations.first()
                        // Build a pseudo-ModelArtifact-equivalent for installer compatibility
                        Triple(q.name, q.downloadUrl, q.sha256Digest)
                    }
                    remote.artifacts.isNotEmpty() -> {
                        val art = remote.artifacts.first { it.sizeBytes > 0L }
                        Triple(art.filename, art.downloadUrl, art.sha256Digest)
                    }
                    else -> throw IllegalStateException("No downloadable artifact available for ${model.id}")
                }

                val modelsDir = File(service.filesDir, "models")
                modelsDir.mkdirs()
                val safeModelId = model.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val (artifactBaseName, downloadUrl, expectedDigest) = chosenArtifact
                val artifactName = if (artifactBaseName.endsWith(".gguf")) artifactBaseName else "${safeModelId}_$artifactBaseName"
                val destination = File(modelsDir, artifactName).absolutePath
                val installer = DefaultModelInstallerWorkflow(downloader, catalog)

                val format = if (artifactName.lowercase().endsWith(".gguf")) "gguf" else artifactName.substringAfterLast('.', "bin").lowercase()
                val backend = if (format == "gguf") "llama.cpp" else "unknown"

                val result = installer.install(
                    ModelDownloadRequest(
                        modelId = model.id,
                        artifactName = artifactName,
                        downloadUrl = downloadUrl,
                        expectedDigest = expectedDigest,
                        destination = destination,
                        kind = detail.kind,
                        format = format,
                        backend = backend,
                        quantization = if (format == "gguf") artifactBaseName else null,
                    )
                ) { event ->
                    when (event) {
                        is dev.aidos.models.InstallerEvent.DownloadStarted -> Unit
                        is dev.aidos.models.InstallerEvent.DownloadProgress -> {
                            val total = event.totalBytes
                            val progress = if (total != null && total > 0) {
                                ((event.bytesDownloaded * 100L) / total).toInt().coerceIn(0, 100)
                            } else _state.value.downloadProgress
                            _state.value = _state.value.copy(downloadProgress = progress)
                        }
                        is dev.aidos.models.InstallerEvent.DownloadCompleted,
                        is dev.aidos.models.InstallerEvent.DigitVerifying,
                        is dev.aidos.models.InstallerEvent.DigitVerified -> Unit
                        is dev.aidos.models.InstallerEvent.InstallationComplete -> {
                            _state.value = _state.value.copy(isDownloading = false, downloadProgress = 100)
                        }
                        is dev.aidos.models.InstallerEvent.InstallationFailed -> {
                            _state.value = _state.value.copy(
                                isDownloading = false,
                                downloadError = event.reason,
                            )
                        }
                    }
                }
                result.exceptionOrNull()?.let { throw it }
                _state.value = _state.value.copy(isDownloading = false, downloadProgress = 100)
                refreshInstalledState(model.id)
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadProgress = 0,
                    downloadError = null,
                )
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadError = "Download failed: ${e.message ?: "Unknown error"}",
                )
                refreshInstalledState(model.id)
            } finally {
                downloadJob = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    fun setEmbeddingInput(value: String) {
        _state.value = _state.value.copy(embeddingInput = value)
    }

    fun testEmbeddings() {
        val model = _state.value.model ?: return
        val input = _state.value.embeddingInput.trim()
        if (input.isBlank()) {
            _state.value = _state.value.copy(embeddingError = "Enter text to embed.")
            return
        }
        if (model.kind != dev.aidos.kernel.ModelKind.EMBEDDING) return

        viewModelScope.launch {
            _state.value = _state.value.copy(isEmbeddingTesting = true, embeddingError = null, embeddingVector = emptyList())
            try {
                val service = EngineService.instance ?: throw IllegalStateException("Engine is not running")
                val client = service.createHttpModelClient() ?: throw IllegalStateException("Engine endpoint is unavailable")
                val response = try {
                    withContext(Dispatchers.IO) { client.embeddings(model.id, listOf(input)) }
                } finally {
                    client.close()
                }
                val vector = response.data.firstOrNull()?.embedding.orEmpty()
                if (vector.isEmpty()) throw IllegalStateException("Embedding model returned an empty vector")
                _state.value = _state.value.copy(isEmbeddingTesting = false, embeddingVector = vector, embeddingError = null)
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(isEmbeddingTesting = false)
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isEmbeddingTesting = false,
                    embeddingError = "Embedding test failed: ${e.message ?: "unknown error"}",
                )
            }
        }
    }

    /** Delete the installed model artifact and remove catalog entry if present. */
    fun deleteInstalledModel(modelId: String) {
        // If a download is active, cancel it first.
        downloadJob?.cancel()

        viewModelScope.launch {
            val service = EngineService.instance
            val installedPath = _state.value.installedPath
            try {
                // Remove DB record when catalog manager is available
                service?.catalogManager?.uninstall(modelId)

                // Delete file on disk if present
                if (!installedPath.isNullOrBlank()) {
                    try {
                        val f = File(installedPath)
                        if (f.exists()) f.delete()
                    } catch (_: Exception) {
                        // best-effort
                    }
                }

                // Refresh installed state
                _state.value = _state.value.copy(
                    isInstalled = false,
                    installedPath = null,
                    installedSizeBytes = null,
                    installedDigest = null,
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Failed to delete model: ${e.message}")
            }
        }
    }

    fun clearDownloadError() {
        _state.value = _state.value.copy(downloadError = null)
    }

    private fun dev.aidos.models.ModelDetail.toUiModel(license: String?): ModelDetail {
        val instance = EngineService.instance
        val device = if (instance != null) DeviceProfileProvider(instance).getProfile() else dev.aidos.cookbook.DeviceProfile(
            totalRamBytes = 8_000_000_000,
            availableRamBytes = 4_000_000_000,
            storageFreeBytes = 10_000_000_000,
            cpuCoreCount = 8,
            hasAccelerator = false,
        )
        val cookbook = dev.aidos.cookbook.CookbookEngine()
        val descriptor = ModelDescriptor(
            id,
            name,
            kind,
            provider,
            false,
            contextWindow,
            sizeBytes,
            null,
        )
        val contexts = listOf(4096, 8192, 16384, 32768)
        val fitRows = contexts.map { ctx ->
            val verdict = cookbook.verdict(descriptor, device, ctx)
            val req = dev.aidos.cookbook.ModelRequirements(sizeBytes ?: 0L, ctx, 0, "GGUF")
            val mem = cookbook.computeResidentMemory(req, device, ctx)
            ContextFitRow(ctx / 1024, verdict.toUiVerdict(), (mem / (1024 * 1024)).toInt())
        }
        val format = try {
            Json.parseToJsonElement(catalogEntry.propertiesJson).jsonObject["format"]?.jsonPrimitive?.content
        } catch (_: Exception) {
            null
        } ?: "gguf"
        return ModelDetail(
            id = id,
            name = name,
            description = "${kind.name} · ${format.uppercase()} · from $provider",
            providerName = provider,
            kind = kind,
            licenseName = license,
            modelUrl = remoteUrl?.takeIf { it.startsWith("https://huggingface.co/") && !it.contains("/resolve/") }
                ?: if (provider == "huggingface") "https://huggingface.co/$id" else null,
            sizeMB = ((sizeBytes ?: 0L) / (1024L * 1024L)).toInt(),
            contextFitTable = fitRows,
            isRunnable = format == "gguf",
        )
    }

    private fun CookbookVerdict.toUiVerdict(): ModelFitVerdict = when (this) {
        CookbookVerdict.RUNS_WELL -> ModelFitVerdict.RUNS_WELL
        CookbookVerdict.RUNS_TIGHT -> ModelFitVerdict.RUNS_TIGHT
        CookbookVerdict.EXCEEDS_CONTEXT -> ModelFitVerdict.EXCEEDS_CONTEXT
        CookbookVerdict.WILL_NOT_FIT -> ModelFitVerdict.WILL_NOT_FIT
    }
}
