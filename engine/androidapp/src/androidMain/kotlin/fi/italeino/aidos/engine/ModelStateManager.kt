package fi.italeino.aidos.engine

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRuntime
import dev.aidos.modelruntime.GlobalModelRuntime
import dev.aidos.modelruntime.GlobalModelRuntimeFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Manages model loading state and provides access to the global model runtime (RFC-0103, RFC-0022).
 *
 * This singleton tracks which models are currently loaded into memory and provides methods
 * to load/unload models. It acts as a bridge between the UI and GlobalModelRuntime.
 *
 * State is exposed via StateFlow for reactive UI updates.
 */
class ModelStateManager {
    private val runtime: ModelRuntime = GlobalModelRuntimeFactory.create()
    
    private val loadMutex = Mutex()
    
    // Flow of currently loaded model IDs, updated whenever load/unload completes
    private val _loadedModels = MutableStateFlow<List<String>>(emptyList())
    val loadedModels: StateFlow<List<String>> = _loadedModels.asStateFlow()
    
    // Flow of currently loading model ID (null if nothing is loading)
    private val _loadingModel = MutableStateFlow<String?>(null)
    val loadingModel: StateFlow<String?> = _loadingModel.asStateFlow()
    
    // Flow of error messages
    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()
    
    init {
        _loadedModels.value = (runtime as? GlobalModelRuntime)?.loaded() ?: emptyList()
    }
    
    /**
     * Load a model into memory.
     *
     * @param modelId the model identifier
     * @return the ModelAdapter if successful, null if loading failed
     */
    suspend fun loadModel(modelId: String): ModelAdapter? = loadMutex.withLock {
        _loadError.value = null
        _loadingModel.value = modelId
        
        return try {
            val result = (runtime as? GlobalModelRuntime)?.load(modelId)
            if (result?.isSuccess == true) {
                _loadedModels.value = (runtime as? GlobalModelRuntime)?.loaded() ?: emptyList()
                result.getOrNull()
            } else {
                val errorMsg = result?.exceptionOrNull()?.message ?: "Unknown error loading model"
                _loadError.value = errorMsg
                null
            }
        } catch (e: Exception) {
            _loadError.value = e.message ?: "Failed to load model"
            null
        } finally {
            _loadingModel.value = null
        }
    }
    
    /**
     * Unload a model from memory.
     *
     * @param modelId the model identifier
     */
    suspend fun unloadModel(modelId: String) = loadMutex.withLock {
        _loadError.value = null
        
        try {
            (runtime as? GlobalModelRuntime)?.unload(modelId)
            _loadedModels.value = (runtime as? GlobalModelRuntime)?.loaded() ?: emptyList()
        } catch (e: Exception) {
            _loadError.value = e.message ?: "Failed to unload model"
        }
    }
    
    /**
     * Get the currently loaded models.
     */
    fun getLoadedModels(): List<String> = loadedModels.value
    
    /**
     * Get access to the underlying GlobalModelRuntime for inference operations.
     */
    fun getRuntime(): ModelRuntime = runtime
    
    /**
     * Clear any stored error message.
     */
    fun clearError() {
        _loadError.value = null
    }
    
    companion object {
        @Volatile private var instance: ModelStateManager? = null
        
        /**
         * Get or create the singleton instance.
         */
        fun getInstance(): ModelStateManager {
            return instance ?: synchronized(this) {
                instance ?: ModelStateManager().also { instance = it }
            }
        }
    }
}
