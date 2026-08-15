package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aidos.modelruntime.GlobalModelRuntime
import dev.aidos.kernel.ModelDescriptor
import fi.italeino.aidos.engine.EngineService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing model catalog and installed models (RFC-0103 Phase E).
 *
 * Bridges the GlobalModelRuntime state to the ModelsScreen UI.
 */
class ModelsViewModel : ViewModel() {

    private val _localModels = MutableStateFlow<List<CookbookModel>>(emptyList())
    val localModels: StateFlow<List<CookbookModel>> = _localModels.asStateFlow()

    private val _cookbookModels = MutableStateFlow<List<CookbookModel>>(emptyList())
    val cookbookModels: StateFlow<List<CookbookModel>> = _cookbookModels.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val runtime = EngineService.instance?.modelRuntime ?: return
        
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Get real installed models
                val installed = runtime.installed()
                _localModels.value = installed.map { it.toUiModel(isLocal = true) }

                // Get real catalog models
                val catalog = runtime.catalog()
                _cookbookModels.value = catalog.map { it.toUiModel(isLocal = false) }
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun ModelDescriptor.toUiModel(isLocal: Boolean): CookbookModel {
        return CookbookModel(
            id = id,
            name = name,
            kind = kind.toString(),
            quantization = "Q4_K_M", // Default for now
            sizeMB = ((sizeBytes ?: 0L) / (1024L * 1024L)).toInt(),
            contextLength = contextWindow,
            fitVerdict = ModelFitVerdict.RUNS_WELL, // Needs calculation from CookbookEngine
            tokensPerSecond = null,
            estimatedVramMB = null
        )
    }
}
