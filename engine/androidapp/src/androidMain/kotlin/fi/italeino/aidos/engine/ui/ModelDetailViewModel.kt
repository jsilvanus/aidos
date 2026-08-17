package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aidos.cookbook.CookbookVerdict
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.models.DefaultModelInstallerWorkflow
import dev.aidos.models.ModelDownloadRequest
import fi.italeino.aidos.engine.EngineService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/** ViewModel for Model Detail and Download (RFC-0103 Phase E). */
class ModelDetailViewModel : ViewModel() {
    private val _state = MutableStateFlow(ModelDetailState())
    val state: StateFlow<ModelDetailState> = _state.asStateFlow()

    fun loadModelDetail(modelId: String) {
        val browser = EngineService.instance?.modelBrowser ?: return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val detail = browser.getModelDetail(modelId).getOrThrow()
                _state.value = _state.value.copy(model = detail.toUiModel(), isLoading = false)
            } catch (e: Exception) {
                _state.value = _state.value.copy(error = e.message, isLoading = false)
            }
        }
    }

    /** Install the selected Hugging Face GGUF through the engine's shared workflow. */
    fun startDownload() {
        val model = _state.value.model ?: return
        val service = EngineService.instance ?: return
        val browser = service.modelBrowser ?: return
        val hf = service.hfClient ?: return
        val downloader = service.downloadManager ?: return
        val catalog = service.catalogManager ?: return

        viewModelScope.launch {
            _state.value = _state.value.copy(isDownloading = true, downloadProgress = 0, error = null)
            try {
                val detail = browser.getModelDetail(model.id).getOrThrow()
                val remote = hf.getModel(model.id).getOrThrow()
                val quantization = remote.quantizations
                    .find { it.name.contains("Q4_K_M", ignoreCase = true) }
                    ?: remote.quantizations.firstOrNull { it.sizeBytes > 0 }
                    ?: throw IllegalStateException("No GGUF quantization available for ${model.id}")

                val modelsDir = File(service.filesDir, "models")
                modelsDir.mkdirs()
                val safeModelId = model.id.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val destination = File(modelsDir, "${safeModelId}_${quantization.name}.gguf").absolutePath
                val installer = DefaultModelInstallerWorkflow(downloader, catalog)

                val result = installer.install(
                    ModelDownloadRequest(
                        modelId = model.id,
                        quantization = quantization.name,
                        downloadUrl = quantization.downloadUrl,
                        expectedDigest = quantization.sha256Digest,
                        destination = destination,
                        kind = detail.kind,
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
                            _state.value = _state.value.copy(isDownloading = false, error = event.reason)
                        }
                    }
                }
                result.exceptionOrNull()?.let { throw it }
                _state.value = _state.value.copy(isDownloading = false, downloadProgress = 100)
            } catch (e: Exception) {
                _state.value = _state.value.copy(isDownloading = false, error = "Download failed: ${e.message}")
            }
        }
    }

    fun toggleLicenseAccepted(accepted: Boolean) {
        _state.value = _state.value.copy(licenseAccepted = accepted)
    }

    private fun dev.aidos.models.ModelDetail.toUiModel(): ModelDetail {
        val instance = EngineService.instance
        val device = if (instance != null) DeviceProfileProvider(instance).getProfile() else dev.aidos.cookbook.DeviceProfile(
            totalRamBytes = 8_000_000_000,
            availableRamBytes = 4_000_000_000,
            storageFreeBytes = 10_000_000_000,
            cpuCoreCount = 8,
            hasAccelerator = false,
        )
        val cookbook = dev.aidos.cookbook.CookbookEngine()
        val descriptor = ModelDescriptor(id, name, kind, provider, false, contextWindow, sizeBytes, null)
        val contexts = listOf(4096, 8192, 16384, 32768)
        val fitRows = contexts.map { ctx ->
            val verdict = cookbook.verdict(descriptor, device, ctx)
            val req = dev.aidos.cookbook.ModelRequirements(sizeBytes ?: 0L, ctx, 0, "GGUF")
            val mem = cookbook.computeResidentMemory(req, device, ctx)
            ContextFitRow(ctx / 1024, verdict.toUiVerdict(), (mem / (1024 * 1024)).toInt())
        }
        return ModelDetail(id, name, "Hugging Face model $id", ((sizeBytes ?: 0L) / (1024L * 1024L)).toInt(), fitRows)
    }

    private fun CookbookVerdict.toUiVerdict(): ModelFitVerdict = when (this) {
        CookbookVerdict.RUNS_WELL -> ModelFitVerdict.RUNS_WELL
        CookbookVerdict.RUNS_TIGHT -> ModelFitVerdict.RUNS_TIGHT
        CookbookVerdict.EXCEEDS_CONTEXT -> ModelFitVerdict.EXCEEDS_CONTEXT
        CookbookVerdict.WILL_NOT_FIT -> ModelFitVerdict.WILL_NOT_FIT
    }
}
