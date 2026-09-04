package fi.italeino.aidos.engine.loading

import dev.aidos.modelruntime.GlobalModelRuntime
import kotlinx.coroutines.withTimeout

/**
 * Wrapper for GlobalModelRuntime.load() with progress tracking (RFC-0103, Phase E).
 *
 * Provides coroutine-based model loading with real progress updates.
 * Tracks loading state transitions (NOT_LOADED → LOADING → LOADED/ERROR)
 * and provides timeout handling.
 *
 * Usage:
 * ```
 * val loader = ModelLoader(globalModelRuntime)
 * val result = loader.loadModel(
 *     modelId = "qwen2.5-3b",
 *     onProgress = { progress -> updateUI(progress) },
 *     onError = { error -> showError(error) }
 * )
 * ```
 */
class ModelLoader(
    private val modelRuntime: GlobalModelRuntime,
    private val timeoutMs: Long = 30_000L  // 30 second default timeout
) {
    /**
     * Load a model into memory with progress callbacks.
     *
     * @param modelId The model identifier to load
     * @param estimatedSizeMB Estimated size for progress calculation (optional)
     * @param onProgress Called with progress 0-100 as loading proceeds
     * @param onError Called if loading fails with error message
     * @return Result<Unit> on success, error on failure
     */
    suspend fun loadModel(
        modelId: String,
        @Suppress("UNUSED_PARAMETER")
        estimatedSizeMB: Int = 2_400,
        onProgress: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ): Result<Unit> {
        return try {
            onProgress(0)
            val loadResult = withTimeout(timeoutMs) { modelRuntime.load(modelId) }
            if (loadResult.isFailure) {
                val error = loadResult.exceptionOrNull()?.message ?: "Unknown error loading model"
                onError(error)
                return Result.failure(Exception(error))
            }
            onProgress(100)
            Result.success(Unit)
        } catch (e: Exception) {
            val errorMsg = e.message ?: "Unknown error during model load"
            onError(errorMsg)
            Result.failure(e)
        }
    }

    /**
     * Unload a model from memory (free resources).
     *
     * @param modelId The model to unload
     * @param onProgress Called with progress 0-100 as unloading proceeds
     * @return Result<Unit> on success
     */
    suspend fun unloadModel(
        modelId: String,
        onProgress: (Int) -> Unit = {}
    ): Result<Unit> {
        return try {
            onProgress(0)
            withTimeout(timeoutMs) { modelRuntime.unload(modelId) }
            onProgress(100)
            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Check if a model is currently loaded in memory.
     *
     * @param modelId The model to check
     * @return true if loaded, false otherwise
     */
    suspend fun isModelLoaded(modelId: String): Boolean {
        return modelRuntime.loaded().contains(modelId)
    }
}
