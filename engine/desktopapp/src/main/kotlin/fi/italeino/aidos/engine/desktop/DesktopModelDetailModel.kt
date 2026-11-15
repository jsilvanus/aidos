package fi.italeino.aidos.engine.desktop

import dev.aidos.kernel.ModelKind
import fi.italeino.aidos.engine.control.ModelDetailControl
import fi.italeino.aidos.engine.control.ModelDetailInfo
import fi.italeino.aidos.engine.control.ModelsControl
import fi.italeino.aidos.engine.control.RunSettingsInfo
import fi.italeino.aidos.engine.ui.ContextFitRow
import fi.italeino.aidos.engine.ui.ModelDetail
import fi.italeino.aidos.engine.ui.ModelDetailState
import fi.italeino.aidos.engine.ui.ModelFitVerdict
import fi.italeino.aidos.engine.ui.ModelLoadingState
import fi.italeino.aidos.engine.ui.ModelLoadingStatus
import fi.italeino.aidos.engine.ui.RunSettingsUi
import fi.italeino.aidos.engine.ui.SelfTestOutcome
import fi.italeino.aidos.engine.ui.selfTestKind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Presenter for the model detail panel (RFC-0103 Phase E, RFC-0022). It knows only [ModelDetailControl] and
 * [ModelsControl] (RFC-0115, D61), so the same panel works over the in-process Engine or a background service: the
 * Engine does the downloading, loading and testing, this turns what it reports into screen state. One instance per
 * opened model; [dispose] when the detail panel closes.
 */
class DesktopModelDetailModel(
    private val modelId: String,
    parentScope: CoroutineScope,
    private val control: () -> ModelDetailControl,
    private val models: () -> ModelsControl,
    private val pollMillis: Long = 500,
) {
    private val scope = CoroutineScope(parentScope.coroutineContext + Job(parentScope.coroutineContext[Job]))

    private val _state = MutableStateFlow(ModelDetailState())
    val state: StateFlow<ModelDetailState> = _state.asStateFlow()

    private val _loading = MutableStateFlow(ModelLoadingState(modelId = modelId))
    val loading: StateFlow<ModelLoadingState> = _loading.asStateFlow()

    init {
        loadModelDetail()
        scope.launch { runCatching { control().runSettings(modelId) }.onSuccess { _state.value = _state.value.copy(runSettings = it.toUi()) } }
        // Residency and download progress belong to the Engine and change without UI events, so they are read back.
        scope.launch {
            while (isActive) {
                trackResidency()
                trackDownload()
                delay(pollMillis)
            }
        }
    }

    fun dispose() = scope.cancel()

    private suspend fun trackResidency() {
        val current = _loading.value.status
        if (current == ModelLoadingStatus.LOADING || current == ModelLoadingStatus.UNLOADING) return
        val residency = runCatching { control().residency(modelId) }.getOrNull() ?: return
        val loadedWith = if (residency.loaded) residency.loadedSettings?.toUi() else null
        if (_state.value.loadedRunSettings != loadedWith) _state.value = _state.value.copy(loadedRunSettings = loadedWith)
        _loading.value = _loading.value.copy(
            status = if (residency.loaded) ModelLoadingStatus.LOADED
            else if (current == ModelLoadingStatus.ERROR) ModelLoadingStatus.ERROR
            else ModelLoadingStatus.NOT_LOADED,
        )
    }

    private suspend fun trackDownload() {
        val wasDownloading = _state.value.isDownloading
        val status = runCatching { control().downloadStatus(modelId) }.getOrNull()
        when {
            status != null && status.active -> _state.value = _state.value.copy(isDownloading = true, downloadProgress = status.percent, downloadError = null)
            status != null -> _state.value = _state.value.copy(isDownloading = false, downloadProgress = 0, downloadError = status.error)
            wasDownloading -> {
                // The Engine finished (or was cancelled) and dropped the entry: a finished download is installed now.
                _state.value = _state.value.copy(isDownloading = false, downloadProgress = 100)
                refreshInstalledState()
            }
        }
    }

    private fun loadModelDetail() {
        scope.launch {
            _state.value = _state.value.copy(isLoading = true, error = null)
            try {
                _state.value = _state.value.copy(model = control().detail(modelId).toUi(), isLoading = false)
                refreshInstalledState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message ?: e::class.simpleName, isLoading = false)
            }
        }
    }

    /** Catalog row plus an on-disk file of the recorded size, as the Engine reports it. */
    fun refreshInstalledState() {
        scope.launch {
            val installed = runCatching { control().installedState(modelId) }.getOrNull() ?: return@launch
            _state.value = _state.value.copy(
                isInstalled = installed.installed,
                installedPath = installed.path,
                installedSizeBytes = installed.sizeBytes,
                installedDigest = installed.digest,
            )
        }
    }

    /** Returns true if this model requires no license acceptance or the user has already accepted its terms. */
    suspend fun isLicenseAccepted(): Boolean = control().licenseAccepted(modelId)

    /** Records user acceptance of this model's license terms, if any. */
    fun acceptLicense() {
        scope.launch { acceptLicenseNow() }
    }

    /** Suspend helper recording license acceptance before initiating download/use. */
    suspend fun acceptLicenseNow() = control().acceptLicense(modelId)

    /** Asks the Engine to install the model; it reports progress, and carries on if this window closes. */
    fun startDownload() {
        if (_state.value.isDownloading) return
        _state.value = _state.value.copy(isDownloading = true, downloadProgress = 0, downloadError = null, error = null)
        scope.launch {
            try {
                control().startDownload(modelId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(isDownloading = false, downloadError = "Download failed: ${e.message ?: "Unknown error"}")
            }
        }
    }

    fun cancelDownload() {
        scope.launch { runCatching { control().cancelDownload(modelId) } }
    }

    /** Unload if resident, then remove the install record and the file. */
    fun delete() {
        scope.launch {
            try {
                runCatching { control().cancelDownload(modelId) }
                models().delete(modelId)
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

    /** Embeds through the Engine's own `/v1/embeddings`. */
    fun testEmbeddings() {
        val model = _state.value.model ?: return
        val input = _state.value.embeddingInput.trim()
        if (input.isBlank()) {
            _state.value = _state.value.copy(embeddingError = "Enter text to embed.")
            return
        }
        if (model.kind != ModelKind.EMBEDDING) return

        scope.launch {
            _state.value = _state.value.copy(isEmbeddingTesting = true, embeddingError = null, embeddingVector = emptyList())
            try {
                val vector = control().embed(model.id, input)
                _state.value = _state.value.copy(isEmbeddingTesting = false, embeddingVector = vector, embeddingError = null)
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(isEmbeddingTesting = false)
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(isEmbeddingTesting = false, embeddingError = "Embedding test failed: ${e.message ?: "unknown error"}")
            }
        }
    }

    /** Saved by the Engine at once; it reads them the next time it loads this model. */
    fun setRunSettings(value: RunSettingsUi) {
        scope.launch {
            try {
                control().setRunSettings(modelId, value.toInfo())
                _state.value = _state.value.copy(runSettings = control().runSettings(modelId).toUi())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = "Could not save the settings: ${e.message}")
            }
        }
    }

    /** Unload and load again so a changed setting takes effect. */
    fun reload() {
        if (_loading.value.status != ModelLoadingStatus.LOADED) return
        scope.launch {
            _loading.value = _loading.value.copy(status = ModelLoadingStatus.UNLOADING)
            try {
                control().unload(modelId)
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
        if (model.kind.selfTestKind() == null) return
        val text = _state.value.selfTestText
        val file = _state.value.selfTestFile
        scope.launch {
            _state.value = _state.value.copy(isSelfTesting = true, selfTestOutcome = null)
            val outcome = try {
                control().selfTest(model.id, text, file).let { SelfTestOutcome(it.passed, it.title, it.lines, it.durationMs, it.generatedTokens) }
            } catch (e: CancellationException) {
                _state.value = _state.value.copy(isSelfTesting = false)
                throw e
            } catch (e: Exception) {
                SelfTestOutcome(false, "Test failed: ${e.message ?: "unknown error"}")
            }
            _state.value = _state.value.copy(isSelfTesting = false, selfTestOutcome = outcome)
        }
    }

    /** Same transitions as the Android screen's load button; the Engine says why a load is refused. */
    fun toggleLoad() {
        if (_loading.value.status == ModelLoadingStatus.LOADED) {
            _loading.value = _loading.value.copy(status = ModelLoadingStatus.UNLOADING)
            scope.launch {
                try {
                    control().unload(modelId)
                    _loading.value = _loading.value.copy(status = ModelLoadingStatus.NOT_LOADED)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    _loading.value = _loading.value.copy(status = ModelLoadingStatus.ERROR, error = e.message)
                }
            }
            return
        }
        _loading.value = _loading.value.copy(status = ModelLoadingStatus.LOADING, loadProgress = 0, error = null)
        scope.launch {
            try {
                control().load(modelId)
                _loading.value = _loading.value.copy(status = ModelLoadingStatus.LOADED, loadProgress = 100, loadTimeMs = System.currentTimeMillis())
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (e.message?.startsWith("Model artifact is not installed") == true) refreshInstalledState()
                _loading.value = _loading.value.copy(status = ModelLoadingStatus.ERROR, error = e.message ?: "Unknown error")
            }
        }
    }

    private fun ModelDetailInfo.toUi() = ModelDetail(
        id = id,
        name = name,
        description = description,
        providerName = providerName,
        kind = ModelKind.entries.firstOrNull { it.name == kind } ?: ModelKind.LLM,
        licenseName = licenseName,
        modelUrl = modelUrl,
        sizeMB = sizeMB,
        contextFitTable = contextFitTable.map { ContextFitRow(it.contextLength, it.verdict.toVerdict(), it.estimatedMemoryMB, it.baselineVerdict?.toVerdict()) },
        isRunnable = isRunnable,
        format = format,
        deviceTotalRamMB = deviceTotalRamMB,
        deviceFreeRamMB = deviceFreeRamMB,
        baselineFreeRamMB = baselineFreeRamMB,
    )

    private fun String.toVerdict() = runCatching { ModelFitVerdict.valueOf(this) }.getOrDefault(ModelFitVerdict.WILL_NOT_FIT)
    private fun RunSettingsInfo.toUi() = RunSettingsUi(contextLength, threads, batchSize, gpuLayers, temperature, topP, topK, useMmap)
    private fun RunSettingsUi.toInfo() = RunSettingsInfo(contextLength, threads, batchSize, gpuLayers, temperature, topP, topK, useMmap)
}
