package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dev.aidos.cookbook.CookbookVerdict
import dev.aidos.kernel.ModelKind
import dev.aidos.models.BrowsableModel
import fi.italeino.aidos.engine.EngineService
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for managing model catalog and installed models (RFC-0103 Phase E).
 *
 * Bridges the GlobalModelRuntime and ModelBrowser state to the ModelsScreen UI.
 */
class ModelsViewModel : ViewModel() {

    private val _localModels = MutableStateFlow<List<CookbookModel>>(emptyList())
    val localModels: StateFlow<List<CookbookModel>> = _localModels.asStateFlow()

    private val _cookbookModels = MutableStateFlow<List<CookbookModel>>(emptyList())
    val cookbookModels: StateFlow<List<CookbookModel>> = _cookbookModels.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSearching = MutableStateFlow(false)
    val isSearching: StateFlow<Boolean> = _isSearching.asStateFlow()

    // Tracks which local models are "enabled" (UI only for now)
    private val _enabledModelIds = MutableStateFlow<Set<String>>(emptySet())
    val enabledModelIds: StateFlow<Set<String>> = _enabledModelIds.asStateFlow()

    private var searchJob: Job? = null

    init {
        refresh()
    }

    fun refresh() {
        val browser = EngineService.instance?.modelBrowser ?: return
        
        viewModelScope.launch {
            _isLoading.value = true
            try {
                // Get installed models from browser (browsing local only)
                val installed = browser.browse(onlyInstalled = true).getOrThrow()
                _localModels.value = installed.map { it.toUiModel() }

                // Initial catalog (curated set)
                val catalog = browser.browse().getOrThrow()
                _cookbookModels.value = catalog.map { it.toUiModel() }
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun searchRemote(query: String, kind: ModelKind? = null, minContext: Int? = null) {
        if (query.isEmpty() && kind == null) {
            refresh()
            return
        }

        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            if (query.isNotEmpty()) {
                delay(500) // Debounce only for text search
            }
            
            val browser = EngineService.instance?.modelBrowser ?: return@launch
            
            _isSearching.value = true
            try {
                val results = browser.searchRemote(query, kind, minContext).getOrThrow()
                _cookbookModels.value = results.map { it.toUiModel() }
            } catch (e: Exception) {
                // Handle error
            } finally {
                _isSearching.value = false
            }
        }
    }

    fun toggleModelEnabled(modelId: String, enabled: Boolean) {
        val current = _enabledModelIds.value.toMutableSet()
        if (enabled) current.add(modelId) else current.remove(modelId)
        _enabledModelIds.value = current
    }

    fun deleteModel(modelId: String) {
        val runtime = EngineService.instance?.modelRuntime ?: return
        viewModelScope.launch {
            try {
                runtime.delete(modelId)
                refresh() // Refresh list after deletion
            } catch (e: Exception) {
                // Handle error
            }
        }
    }

    private fun BrowsableModel.toUiModel(): CookbookModel {
        return CookbookModel(
            id = id,
            name = name,
            kind = kind.toString(),
            quantization = "GGUF", // We only support GGUF currently
            sizeMB = ((sizeBytes ?: 0L) / (1024L * 1024L)).toInt(),
            contextLength = contextWindow,
            fitVerdict = verdict.toUiVerdict(),
            tokensPerSecond = null,
            estimatedVramMB = null
        )
    }

    private fun CookbookVerdict.toUiVerdict(): ModelFitVerdict = when (this) {
        CookbookVerdict.RUNS_WELL -> ModelFitVerdict.RUNS_WELL
        CookbookVerdict.RUNS_TIGHT -> ModelFitVerdict.RUNS_TIGHT
        CookbookVerdict.EXCEEDS_CONTEXT -> ModelFitVerdict.EXCEEDS_CONTEXT
        CookbookVerdict.WILL_NOT_FIT -> ModelFitVerdict.WILL_NOT_FIT
    }
}
