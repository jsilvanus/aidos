package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aidos.cookbook.CookbookVerdict
import dev.aidos.kernel.ModelDescriptor
import fi.italeino.aidos.engine.EngineService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * ViewModel for Model Detail and Download (RFC-0103 Phase E).
 */
class ModelDetailViewModel : ViewModel() {

    private val _state = MutableStateFlow(ModelDetailState())
    val state: StateFlow<ModelDetailState> = _state.asStateFlow()

    fun loadModelDetail(modelId: String) {
        val browser = EngineService.instance?.modelBrowser ?: return
        
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val detail = browser.getModelDetail(modelId).getOrThrow()
                
                _state.value = _state.value.copy(
                    model = detail.toUiModel(),
                    isLoading = false
                )
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    error = e.message,
                    isLoading = false
                )
            }
        }
    }

    fun startDownload() {
        val model = _state.value.model ?: return
        
        viewModelScope.launch {
            _state.value = _state.value.copy(isDownloading = true, downloadProgress = 0)
            
            try {
                val modelsDir = File(System.getProperty("user.home"), ".aidos/models")
                modelsDir.mkdirs()
                
                val file = File(modelsDir, "${model.id}.gguf")
                
                for (p in 0..100 step 10) {
                    _state.value = _state.value.copy(downloadProgress = p)
                    kotlinx.coroutines.delay(200)
                }
                
                file.writeText("MOCK GGUF CONTENT")
                
                _state.value = _state.value.copy(isDownloading = false, downloadProgress = 100)
            } catch (e: Exception) {
                _state.value = _state.value.copy(
                    isDownloading = false,
                    error = "Download failed: ${e.message}"
                )
            }
        }
    }

    fun toggleLicenseAccepted(accepted: Boolean) {
        _state.value = _state.value.copy(licenseAccepted = accepted)
    }

    private fun dev.aidos.models.ModelDetail.toUiModel(): ModelDetail {
        val instance = EngineService.instance
        val device = if (instance != null) {
            DeviceProfileProvider(instance).getProfile()
        } else {
            // Default profile if service not available
            dev.aidos.cookbook.DeviceProfile(
                totalRamBytes = 8_000_000_000,
                availableRamBytes = 4_000_000_000,
                storageFreeBytes = 10_000_000_000,
                cpuCoreCount = 8,
                hasAccelerator = false
            )
        }

        val cookbook = dev.aidos.cookbook.CookbookEngine()

        val descriptor = ModelDescriptor(
            id = id,
            name = name,
            kind = kind,
            providerId = provider,
            isLocal = false,
            contextWindow = contextWindow,
            sizeBytes = sizeBytes,
            digest = null
        )

        // Generate context fit table for common lengths
        val contexts = listOf(4096, 8192, 16384, 32768)
        val fitRows = contexts.map { ctx ->
            val v = cookbook.verdict(descriptor, device, ctx)
            val req = dev.aidos.cookbook.ModelRequirements(
                weightsBytesOnDisk = sizeBytes ?: 0L,
                contextWindow = ctx,
                parameterCount = 0, // Placeholder
                quantizationType = "GGUF"
            )
            val mem = cookbook.computeResidentMemory(req, device, ctx)
            ContextFitRow(ctx / 1024, v.toUiVerdict(), (mem / (1024 * 1024)).toInt())
        }

        return ModelDetail(
            id = id,
            name = name,
            description = "Hugging Face model $id",
            sizeMB = ((sizeBytes ?: 0L) / (1024L * 1024L)).toInt(),
            contextFitTable = fitRows
        )
    }

    private fun CookbookVerdict.toUiVerdict(): ModelFitVerdict = when (this) {
        CookbookVerdict.RUNS_WELL -> ModelFitVerdict.RUNS_WELL
        CookbookVerdict.RUNS_TIGHT -> ModelFitVerdict.RUNS_TIGHT
        CookbookVerdict.EXCEEDS_CONTEXT -> ModelFitVerdict.EXCEEDS_CONTEXT
        CookbookVerdict.WILL_NOT_FIT -> ModelFitVerdict.WILL_NOT_FIT
    }
}
