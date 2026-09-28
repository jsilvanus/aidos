package fi.italeino.aidos.engine.loading

import dev.aidos.modelruntime.GlobalModelRuntime
import kotlinx.coroutines.withTimeout

/**
 * Wrapper for GlobalModelRuntime.load() with progress tracking (RFC-0103, Phase E).
 *
 * Provides coroutine-based model loading with real progress updates and can bind a load to the
 * exact installed artifact selected by the model catalog.
 */
class ModelLoader(
    private val modelRuntime: GlobalModelRuntime,
    private val timeoutMs: Long = 30_000L
) {
    /**
     * Load a model into memory with normal runtime model-id resolution.
     */
    suspend fun loadModel(
        modelId: String,
        estimatedSizeMB: Int = 2_400,
        onProgress: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ): Result<Unit> = loadInternal(
        modelId = modelId,
        artifactPath = null,
        estimatedSizeMB = estimatedSizeMB,
        onProgress = onProgress,
        onError = onError,
    )

    /**
     * Load a model from the exact installed artifact recorded by the catalog.
     */
    suspend fun loadModel(
        modelId: String,
        artifactPath: String,
        estimatedSizeMB: Int = 2_400,
        onProgress: (Int) -> Unit = {},
        onError: (String) -> Unit = {}
    ): Result<Unit> = loadInternal(
        modelId = modelId,
        artifactPath = artifactPath,
        estimatedSizeMB = estimatedSizeMB,
        onProgress = onProgress,
        onError = onError,
    )

    /**
     * Shared implementation for normal id resolution and an exact catalog-selected artifact.
     * Keeping the nullable path private avoids exposing a nullable path to UI callers while
     * allowing the normal overload to call [GlobalModelRuntime.load] correctly.
     */
    private suspend fun loadInternal(
        modelId: String,
        artifactPath: String?,
        estimatedSizeMB: Int,
        onProgress: (Int) -> Unit,
        onError: (String) -> Unit,
    ): Result<Unit> {
        return try {
            onProgress(0)
            val loadResult = withTimeout(timeoutMs) {
                modelRuntime.load(modelId, artifactPath)
            }
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

    suspend fun isModelLoaded(modelId: String): Boolean {
        return modelRuntime.loaded().contains(modelId)
    }
}
