package fi.italeino.aidos.engine.desktop

import dev.aidos.cookbook.CookbookEngine
import dev.aidos.decisions.LayaPackage
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.modelruntime.ModelRunSettings
import dev.aidos.models.DefaultModelInstallerWorkflow
import dev.aidos.models.InstallerEvent
import dev.aidos.models.ModelDownloadRequest
import fi.italeino.aidos.engine.EngineState
import fi.italeino.aidos.engine.ui.ContextFitRow
import fi.italeino.aidos.engine.ui.ModelDetail
import fi.italeino.aidos.engine.ui.ModelDetailState
import fi.italeino.aidos.engine.ui.ModelLoadingState
import fi.italeino.aidos.engine.ui.ModelLoadingStatus
import fi.italeino.aidos.engine.ui.RunSettingsUi
import fi.italeino.aidos.engine.ui.SelfTestOutcome
import fi.italeino.aidos.engine.ui.runModelSelfTest
import fi.italeino.aidos.engine.ui.selfTestKind
import fi.italeino.aidos.sdk.client.EmbeddingsRequest
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
    private val prefs: DesktopPreferences,
    parentScope: CoroutineScope,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))

    private val _state = MutableStateFlow(ModelDetailState(runSettings = prefs.runSettingsFor(modelId).toUi()))
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
                    val loadedWith = if (loaded) runtime?.loadedSettings(modelId)?.toUi() else null
                    if (_state.value.loadedRunSettings != loadedWith) _state.value = _state.value.copy(loadedRunSettings = loadedWith)
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
            // A GGUF is one file of the recorded size; an ONNX decision model is a package directory.
            val valid = installed != null && file != null && installed.sizeBytes > 0L && when {
                file.isFile -> file.length() == installed.sizeBytes
                file.isDirectory -> (LayaPackage.isPackage(file) || dev.aidos.stt.WhisperPackage.isPackage(file) || dev.aidos.vision.VisionPackage.isPackage(file)) &&
                    file.listFiles { f -> f.isFile }.orEmpty().sumOf { it.length() } == installed.sizeBytes
                else -> false
            }
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

                val safeModelId = model.id.replace(Regex("[^A-Za-z0-9._-]"), "_")

                // A GGUF quantization is the single-file path (prefer Q4_K_M, as on Android). Failing
                // that, an ONNX decision package (graph + tokenizer + config) or a Whisper speech-to-text package installs as a directory.
                // Anything else (a lone .onnx, safetensors, ...) has no backend here, so installing it
                // would leave a file the Engine can never load.
                val onnxPackage = dev.aidos.models.PackageInstall.choose(remote.packages)
                if (remote.quantizations.isEmpty() && onnxPackage != null) {
                    installOnnxPackage(model.id, safeModelId, onnxPackage)
                    return@launch
                }
                // A SmolVLM-style vision model is its three graphs and configs installed as one unit; the
                // projector-pairing problem of GGUF vision models does not arise on ONNX.
                val visionPackage = remote.packages.firstOrNull { it.isVisionPackage }
                if (remote.quantizations.isEmpty() && visionPackage != null) {
                    installOnnxPackage(model.id, safeModelId, visionPackage)
                    return@launch
                }
                if (remote.quantizations.isEmpty()) {
                    val found = remote.artifacts.map { it.format.name }.distinct().joinToString().ifEmpty { "none" }
                    throw IllegalStateException(
                        "No GGUF file, ONNX decision, Whisper or vision package in ${model.id} (formats found: $found); this Engine cannot run it.",
                    )
                }
                val (artifactBaseName, downloadUrl, expectedDigest) = remote.quantizations.let { qs ->
                    val q = qs.find { it.name.contains("Q4_K_M", ignoreCase = true) } ?: qs.first()
                    Triple(q.name, q.downloadUrl, q.sha256Digest)
                }

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

    /** Downloads every file of [pkg] (a decision, speech or vision model) into `<models>/<safeId>/`; the graph last, so a partial install is never a loadable package. */
    private suspend fun installOnnxPackage(modelId: String, safeId: String, pkg: dev.aidos.huggingface.ModelPackage) {
        val result = DefaultModelInstallerWorkflow(host.downloadManager, host.catalogManager).installPackage(
            dev.aidos.models.PackageInstall.toRequest(modelId, pkg, File(host.artifactDir, safeId).absolutePath)
        ) { event ->
            when (event) {
                is InstallerEvent.DownloadProgress -> {
                    val total = event.totalBytes
                    if (total != null && total > 0) {
                        _state.value = _state.value.copy(downloadProgress = ((event.bytesDownloaded * 100L) / total).toInt().coerceIn(0, 100))
                    }
                }
                is InstallerEvent.InstallationFailed -> _state.value = _state.value.copy(isDownloading = false, downloadError = event.reason)
                else -> Unit
            }
        }
        result.exceptionOrNull()?.let { throw it }
        _state.value = _state.value.copy(isDownloading = false, downloadProgress = 100)
        refreshInstalledState()
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

    /** Same as Android: embeds through Engine's own `/v1/embeddings`, via the Aidos SDK client. */
    fun testEmbeddings() {
        val model = _state.value.model ?: return
        val input = _state.value.embeddingInput.trim()
        if (input.isBlank()) {
            _state.value = _state.value.copy(embeddingError = "Enter text to embed.")
            return
        }
        if (model.kind != dev.aidos.kernel.ModelKind.EMBEDDING) return

        scope.launch {
            _state.value = _state.value.copy(isEmbeddingTesting = true, embeddingError = null, embeddingVector = emptyList())
            try {
                val client = host.createEngineClient() ?: throw IllegalStateException("Start the Engine first.")
                val response = try {
                    client.embeddings(EmbeddingsRequest(model = model.id, input = listOf(input)))
                } finally {
                    client.close()
                }
                if (response == null) throw IllegalStateException("Engine did not return an embedding")
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

    /** Saves to preferences at once; the runtime reads them the next time it loads this model. */
    fun setRunSettings(value: RunSettingsUi) {
        prefs.setRunSettings(modelId, value.toRuntime())
        _state.value = _state.value.copy(runSettings = prefs.runSettingsFor(modelId).toUi())
    }

    /** Unload and load again so a changed setting takes effect. */
    fun reload() {
        val runtime = host.modelRuntime ?: return
        if (modelId !in runtime.loaded()) return
        scope.launch {
            _loading.value = _loading.value.copy(status = ModelLoadingStatus.UNLOADING)
            try {
                withTimeout(LOAD_TIMEOUT_MS) { runtime.unload(modelId) }
                _loading.value = _loading.value.copy(status = ModelLoadingStatus.NOT_LOADED)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _loading.value = _loading.value.copy(status = ModelLoadingStatus.ERROR, error = e.message)
                return@launch
            }
            toggleLoad()
        }
    }

    fun setSelfTestText(value: String) {
        _state.value = _state.value.copy(selfTestText = value)
    }

    fun setSelfTestFile(value: String) {
        _state.value = _state.value.copy(selfTestFile = value)
    }

    /** The test for this model's kind (chat, decisions, transcription, vision), through the Engine's own endpoint. */
    fun runSelfTest() {
        val model = _state.value.model ?: return
        val kind = model.kind.selfTestKind() ?: return
        val text = _state.value.selfTestText
        val file = _state.value.selfTestFile
        scope.launch {
            _state.value = _state.value.copy(isSelfTesting = true, selfTestOutcome = null)
            val outcome = try {
                val client = host.createEngineClient() ?: throw IllegalStateException("Start the Engine first.")
                try {
                    withContext(Dispatchers.IO) { runModelSelfTest(client, kind, model.id, text, file) }
                } finally {
                    client.close()
                }
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(isSelfTesting = false)
                throw e
            } catch (e: Exception) {
                SelfTestOutcome(false, "Test failed: ${e.message ?: "unknown error"}")
            }
            _state.value = _state.value.copy(isSelfTesting = false, selfTestOutcome = outcome)
        }
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
        val device = host.deviceProfile()
        val cookbook = CookbookEngine()
        val descriptor = ModelDescriptor(id, name, kind, provider, false, contextWindow, sizeBytes, null)
        val fitRows = listOf(4096, 8192, 16384, 32768).map { ctx ->
            val fit = cookbook.ramFit(descriptor, device, ctx)
            ContextFitRow(ctx / 1024, fit.currentVerdict.toUiVerdict(), (fit.neededBytes / (1024 * 1024)).toInt(), fit.baselineVerdict?.toUiVerdict())
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
            isRunnable = dev.aidos.models.PackageInstall.isRunnable(format, kind),
            format = format,
            deviceTotalRamMB = (device.totalRamBytes / (1024L * 1024L)).toInt(),
            deviceFreeRamMB = (device.availableRamBytes / (1024L * 1024L)).toInt(),
            baselineFreeRamMB = device.baselineAvailableRamBytes?.let { (it / (1024L * 1024L)).toInt() },
        )
    }

    private fun ModelRunSettings.toUi() = RunSettingsUi(contextLength, threads, batchSize, gpuLayers, temperature, topP, topK)

    private fun RunSettingsUi.toRuntime() = ModelRunSettings(contextLength, threads, batchSize, gpuLayers, temperature, topP, topK)

    private companion object {
        /** Same budget as Android's ModelLoader default. */
        const val LOAD_TIMEOUT_MS = 30_000L
    }
}
