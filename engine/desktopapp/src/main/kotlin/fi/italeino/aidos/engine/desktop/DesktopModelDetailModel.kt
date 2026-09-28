package fi.italeino.aidos.engine.desktop

import dev.aidos.cookbook.CookbookEngine
import dev.aidos.cookbook.ModelRequirements
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.models.DefaultModelInstallerWorkflow
import dev.aidos.models.InstallerEvent
import dev.aidos.models.ModelDownloadRequest
import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.ui.ContextFitRow
import fi.italeino.aidos.engine.ui.ModelDetail
import fi.italeino.aidos.engine.ui.ModelDetailState
import fi.italeino.aidos.engine.ui.ModelLoadingState
import fi.italeino.aidos.engine.ui.ModelLoadingStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File

/**
 * Desktop port of Android's ModelDetailViewModel plus the load/unload state machine that the
 * Android ModelDetailScreen keeps inline (RFC-0103 Phase E, RFC-0022). One instance per opened
 * model; [dispose] when the detail panel closes.
 */
class DesktopModelDetailModel(
    private val modelId: String,
    private val host: DesktopEngineHost,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))

    private val _state = MutableStateFlow(ModelDetailState())
    val state: StateFlow<ModelDetailState> = _state.asStateFlow()

    private val _loading = MutableStateFlow(ModelLoadingState(modelId = modelId))
    val loading: StateFlow<ModelLoadingState> = _loading.asStateFlow()

    private var downloadJob: Job? = null

    init {
        loadModelDetail()
        // Track residency like the Android screen: the runtime changes without UI events.
        scope.launch {
            while (true) {
                val runtime = host.modelRuntime
                val current = _loading.value.status
                if (current != ModelLoadingStatus.LOADING && current != ModelLoadingStatus.UNLOADING) {
                    val loaded = runtime != null && modelId in runtime.loaded()
                    _loading.value = _loading.value.copy(
                        status = if (loaded) ModelLoadingStatus.LOADED
                        else if (current == ModelLoadingStatus.ERROR) ModelLoadingStatus.ERROR
                        else ModelLoadingStatus.NOT_LOADED,
                    )
                }
                delay(500)
            }
        }
    }

    fun dispose() = scope.cancel()

    private fun loadModelDetail() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                val detail = withContext(Dispatchers.IO) { host.modelBrowser.getModelDetail(modelId).getOrThrow() }
                val license = if (detail.provider == "huggingface") {
                    withContext(Dispatchers.IO) { host.hfClient.getModel(modelId).getOrNull()?.license }
                } else null
                _state.value = _state.value.copy(model = detail.toUiModel(license), isLoading = false)
                refreshInstalledState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e::class.simpleName, isLoading = false)
            }
        }
    }

    /** Catalog row plus an on-disk file of the recorded size, as on Android. */
    fun refreshInstalledState() {
        scope.launch {
            val installed = withContext(Dispatchers.IO) {
                host.catalogManager.listInstalled().getOrNull()?.firstOrNull { it.modelId == modelId }
            }
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
        _state.value = _state.value.copy(downloadError = null)

        downloadJob = scope.launch {
            _state.value = _state.value.copy(isDownloading = true, downloadProgress = 0, downloadError = null, error = null)
            try {
                val detail = host.modelBrowser.getModelDetail(model.id).getOrThrow()
                val remote = host.hfClient.getModel(model.id).getOrThrow()

                // Prefer Q4_K_M, then any quantization, then any non-empty artifact (as on Android).
                val (artifactBaseName, downloadUrl, expectedDigest) = when {
                    remote.quantizations.isNotEmpty() -> {
                        val q = remote.quantizations.find { it.name.contains("Q4_K_M", ignoreCase = true) }
                            ?: remote.quantizations.first()
                        Triple(q.name, q.downloadUrl, q.sha256Digest)
                    }
                    remote.artifacts.isNotEmpty() -> {
                        val art = remote.artifacts.first { it.sizeBytes > 0L }
                        Triple(art.filename, art.downloadUrl, art.sha256Digest)
                    }
                    else -> throw IllegalStateException("No downloadable artifact available for ${model.id}")
                }

                val safeModelId = model.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val artifactName = if (artifactBaseName.endsWith(".gguf")) artifactBaseName else "${safeModelId}_$artifactBaseName"
                val destination = File(host.artifactDir, artifactName).absolutePath
                val format = if (artifactName.lowercase().endsWith(".gguf")) "gguf" else artifactName.substringAfterLast('.', "bin").lowercase()

                val result = DefaultModelInstallerWorkflow(host.downloadManager, host.catalogManager).install(
                    ModelDownloadRequest(
                        modelId = model.id,
                        artifactName = artifactName,
                        downloadUrl = downloadUrl,
                        expectedDigest = expectedDigest,
                        destination = destination,
                        kind = detail.kind,
                        format = format,
                        backend = if (format == "gguf") "llama.cpp" else "unknown",
                        quantization = if (format == "gguf") artifactBaseName else null,
                    )
                ) { event ->
                    when (event) {
                        is InstallerEvent.DownloadProgress -> {
                            val total = event.totalBytes
                            if (total != null && total > 0) {
                                _state.value = _state.value.copy(
                                    downloadProgress = ((event.bytesDownloaded * 100L) / total).toInt().coerceIn(0, 100)
                                )
                            }
                        }
                        is InstallerEvent.InstallationFailed ->
                            _state.value = _state.value.copy(isDownloading = false, downloadError = event.reason)
                        else -> Unit
                    }
                }
                result.exceptionOrNull()?.let { throw it }
                _state.value = _state.value.copy(isDownloading = false, downloadProgress = 100)
                refreshInstalledState()
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(isDownloading = false, downloadProgress = 0, downloadError = null)
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isDownloading = false,
                    downloadError = "Download failed: ${e.message ?: "Unknown error"}",
                )
                refreshInstalledState()
            } finally {
                downloadJob = null
            }
        }
    }

    fun cancelDownload() {
        downloadJob?.cancel()
    }

    /** Unload if resident, then remove the install record and the file. */
    fun delete() {
        downloadJob?.cancel()
        scope.launch {
            try {
                host.modelRuntime?.let { if (modelId in it.loaded()) it.unload(modelId) }
                withContext(Dispatchers.IO) {
                    DefaultModelInstallerWorkflow(host.downloadManager, host.catalogManager).uninstall(modelId).getOrThrow()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Failed to delete model: ${e.message}")
            }
            refreshInstalledState()
        }
    }

    fun setEmbeddingInput(value: String) {
        _state.value = _state.value.copy(embeddingInput = value)
    }

    /** Android tests embeddings through its own HTTP endpoint via HttpModelClient (androidMain). */
    fun testEmbeddings() {
        _state.value = _state.value.copy(
            embeddingError = "Embeddings test is not available in the desktop debug app yet; " +
                "POST /v1/embeddings to 127.0.0.1:${host.boundPort.value ?: "<port>"} instead.",
        )
    }

    /** Same transitions as the Android screen's load button. */
    fun toggleLoad() {
        val runtime = host.modelRuntime
        if (runtime == null || host.state.value != EngineState.READY) {
            _loading.value = _loading.value.copy(
                status = ModelLoadingStatus.ERROR,
                error = when (host.state.value) {
                    EngineState.FAILED -> "Engine failed to start: ${host.lastError.value ?: "unknown error"}"
                    else -> "Start the Engine before loading a model."
                },
            )
            return
        }
        if (_loading.value.status == ModelLoadingStatus.LOADED) {
            _loading.value = _loading.value.copy(status = ModelLoadingStatus.UNLOADING)
            scope.launch {
                try {
                    withTimeout(LOAD_TIMEOUT_MS) { runtime.unload(modelId) }
                    _loading.value = _loading.value.copy(status = ModelLoadingStatus.NOT_LOADED)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _loading.value = _loading.value.copy(status = ModelLoadingStatus.ERROR, error = e.message)
                }
            }
            return
        }
        val installedPath = _state.value.installedPath
        if (!_state.value.isInstalled || installedPath.isNullOrBlank()) {
            refreshInstalledState()
            _loading.value = _loading.value.copy(
                status = ModelLoadingStatus.ERROR,
                error = "Model artifact is not installed. Download it before loading.",
            )
            return
        }
        _loading.value = _loading.value.copy(status = ModelLoadingStatus.LOADING, loadProgress = 0, error = null)
        scope.launch {
            val result = try {
                withTimeout(LOAD_TIMEOUT_MS) { runtime.load(modelId, installedPath) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Result.failure(e)
            }
            _loading.value = result.fold(
                onSuccess = {
                    _loading.value.copy(status = ModelLoadingStatus.LOADED, loadProgress = 100, loadTimeMs = System.currentTimeMillis())
                },
                onFailure = {
                    _loading.value.copy(status = ModelLoadingStatus.ERROR, error = it.message ?: "Unknown error")
                },
            )
        }
    }

    private fun dev.aidos.models.ModelDetail.toUiModel(license: String?): ModelDetail {
        val device = DesktopDevice.profile()
        val cookbook = CookbookEngine()
        val descriptor = ModelDescriptor(id, name, kind, provider, false, contextWindow, sizeBytes, null)
        val fitRows = listOf(4096, 8192, 16384, 32768).map { ctx ->
            val verdict = cookbook.verdict(descriptor, device, ctx)
            val mem = cookbook.computeResidentMemory(ModelRequirements(sizeBytes ?: 0L, ctx, 0, "GGUF"), device, ctx)
            ContextFitRow(ctx / 1024, verdict.toUiVerdict(), (mem / (1024 * 1024)).toInt())
        }
        val format = formatFromProperties(catalogEntry.propertiesJson)
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

    private companion object {
        /** Same budget as Android's ModelLoader default. */
        const val LOAD_TIMEOUT_MS = 30_000L
    }
}
