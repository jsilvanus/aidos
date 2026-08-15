package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
        val runtime = EngineService.instance?.modelRuntime ?: return
        
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true)
            try {
                val catalog = runtime.catalog()
                val model = catalog.find { it.id == modelId }
                
                if (model != null) {
                    _state.value = _state.value.copy(
                        model = model.toUiModel(),
                        isLoading = false
                    )
                } else {
                    _state.value = _state.value.copy(
                        error = "Model not found in catalog",
                        isLoading = false
                    )
                }
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
            
            // Mock download: create a dummy file so LlamaCppInferenceBackend sees it as installed
            try {
                // Determine models directory (matches LlamaCppInferenceBackend for now)
                val modelsDir = File(System.getProperty("user.home"), ".aidos/models")
                modelsDir.mkdirs()
                
                val file = File(modelsDir, "${model.id}.gguf")
                
                // Simulate progress
                for (p in 0..100 step 10) {
                    _state.value = _state.value.copy(downloadProgress = p)
                    kotlinx.coroutines.delay(200)
                }
                
                // Create a small dummy file with GGUF extension
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

    private fun ModelDescriptor.toUiModel(): ModelDetail {
        return ModelDetail(
            id = id,
            name = name,
            description = "High-performing model from ${providerId}.",
            sizeMB = ((sizeBytes ?: 0L) / (1024L * 1024L)).toInt(),
            contextFitTable = listOf(
                ContextFitRow(4, ModelFitVerdict.RUNS_WELL, 2400),
                ContextFitRow(16, ModelFitVerdict.RUNS_TIGHT, 3300)
            )
        )
    }
}
