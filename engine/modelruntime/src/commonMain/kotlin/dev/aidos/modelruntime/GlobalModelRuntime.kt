package dev.aidos.modelruntime

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Global model runtime with admission queue (RFC-0022, M20).
 *
 * Invariants enforced here (RFC-0022):
 * - Weights are user-scope, not per-project. This runtime is a singleton per process.
 * - Loading is **globally serialized** through a single admission queue — only one load
 *   operation runs at a time. This is not a performance decision: one loaded 7B model can
 *   saturate a phone; multiple concurrent loads are impossible, not just unwise.
 * - Digest is verified before a model is returned. A mismatch means the weights are corrupt
 *   or substituted; the correct response is deletion, not quarantine.
 * - Unload is explicit. The runtime never evicts weights to make room (RFC-0022, user
 *   chooses, no automatic deletion).
 *
 * The actual inference backend (llama.cpp / GGUF) is provided by [InferenceBackend] and is
 * not part of this module — M21 supplies a real backend for a phone, M20 defines the queue
 * and digest contract.
 */
class GlobalModelRuntime(
    private val backend: InferenceBackend,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : ModelRuntime {

    private val admissionQueue = Mutex()
    @Volatile private var loadedModels: Map<String, LoadedModel> = emptyMap()

    private data class LoadedModel(val adapter: ModelAdapter, val loadedAtMillis: Long)

    override suspend fun catalog(): List<ModelDescriptor> = backend.catalog()

    override suspend fun installed(): List<ModelDescriptor> = backend.installed()

    /** Loads a model using the backend's normal model-id resolution. */
    override suspend fun load(modelId: String): Result<ModelAdapter> =
        load(modelId, artifactPath = null)

    /**
     * Loads a model from the exact installed artifact selected by the caller.
     *
     * The path is an integrity/identity boundary: the Android UI has already reconciled the
     * persistent installed-model record with the file on disk, so the backend must load that
     * artifact rather than selecting another file merely because it shares the same model id.
     */
    suspend fun load(modelId: String, artifactPath: String?): Result<ModelAdapter> {
        loadedModels[modelId]?.let { return Result.success(it.adapter) }

        return admissionQueue.withLock {
            loadedModels[modelId]?.let { return@withLock Result.success(it.adapter) }

            backend.installed().find { it.id == modelId }
                ?: return@withLock Result.failure(
                    IllegalStateException("Model $modelId is not installed")
                )

            val catalogDigest = backend.catalog().find { it.id == modelId }?.digest
            if (catalogDigest != null) {
                val actualDigest = backend.computeDigest(modelId, artifactPath)
                if (actualDigest != catalogDigest) {
                    backend.delete(modelId)
                    return@withLock Result.failure(
                        DigestMismatchException(
                            modelId = modelId,
                            expected = catalogDigest,
                            actual = actualDigest,
                        )
                    )
                }
            }

            val adapter = backend.load(modelId, artifactPath).getOrElse { err ->
                return@withLock Result.failure(err)
            }
            loadedModels = loadedModels + (modelId to LoadedModel(adapter, nowMillis()))
            Result.success(adapter)
        }
    }

    override suspend fun unload(modelId: String) {
        admissionQueue.withLock {
            loadedModels = loadedModels - modelId
            backend.unload(modelId)
        }
    }

    fun loadedAtMillis(modelId: String): Long? = loadedModels[modelId]?.loadedAtMillis

    suspend fun delete(modelId: String) {
        admissionQueue.withLock {
            if (loadedModels.containsKey(modelId)) {
                loadedModels = loadedModels - modelId
                backend.unload(modelId)
            }
            backend.delete(modelId)
        }
    }

    override fun loaded(): List<String> = loadedModels.keys.toList()

    companion object
}

/**
 * The inference backend that [GlobalModelRuntime] delegates real work to.
 */
interface InferenceBackend {
    suspend fun catalog(): List<ModelDescriptor>
    suspend fun installed(): List<ModelDescriptor>
    suspend fun computeDigest(modelId: String): String
    suspend fun computeDigest(modelId: String, artifactPath: String?): String =
        computeDigest(modelId)
    suspend fun delete(modelId: String)
    suspend fun load(modelId: String): Result<ModelAdapter>
    suspend fun load(modelId: String, artifactPath: String?): Result<ModelAdapter> =
        load(modelId)
    suspend fun unload(modelId: String)
}

/**
 * Thrown when a weight file's digest does not match its catalog entry.
 */
class DigestMismatchException(
    val modelId: String,
    val expected: String,
    val actual: String,
) : RuntimeException(
    "Digest mismatch for model $modelId: expected $expected but got $actual. " +
            "Weights have been deleted. Re-install the model."
)
