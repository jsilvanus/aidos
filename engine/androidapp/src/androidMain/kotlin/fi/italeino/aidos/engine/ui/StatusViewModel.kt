package fi.italeino.aidos.engine.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import fi.italeino.aidos.engine.EngineService
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for Engine Status and Control (RFC-0103 Phase E).
 */
class StatusViewModel : ViewModel() {

    private val _isEngineRunning = MutableStateFlow(false)
    val isEngineRunning: StateFlow<Boolean> = _isEngineRunning.asStateFlow()

    private val _residentModels = MutableStateFlow<List<ResidentModel>>(emptyList())
    val residentModels: StateFlow<List<ResidentModel>> = _residentModels.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val service = EngineService.instance
        _isEngineRunning.value = service?.isRunning ?: false

        val runtime = service?.modelRuntime ?: return
        val catalogManager = service.catalogManager

        viewModelScope.launch {
            val loaded = runtime.loaded()
            val installedById = catalogManager?.listInstalled()?.getOrNull()
                .orEmpty()
                .associateBy { it.modelId }
            val now = System.currentTimeMillis()
            _residentModels.value = loaded.map { id ->
                val installed = installedById[id]
                val loadedAt = runtime.loadedAtMillis(id)
                ResidentModel(
                    id = id,
                    displayName = installed?.userLabel ?: id,
                    quantization = installed?.quantization ?: "unknown",
                    loadedAgoMs = loadedAt?.let { now - it } ?: 0L,
                )
            }
        }
    }
}
