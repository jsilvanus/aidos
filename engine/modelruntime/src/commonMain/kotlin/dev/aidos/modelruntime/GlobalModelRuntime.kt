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
 * - Digest is verified before a model is returned. A mismatch means the weights are
 *   corrupt or substituted; the correct response is deletion, not quarantine.
 * - Unload is explicit. The runtime never evicts weights to make room (RFC-0022, user
 *   chooses, no automatic deletion).
 *
 * The actual inference backend (llama.cpp / GGUF) is provided by [InferenceBackend] and is
 * not part of this module — M21 supplies a real backend for a phone, M20 defines the queue
 * and digest contract.
 */
class GlobalModelRuntime(
    private val backend: InferenceBackend,
    // Injectable for tests; real callers get the wall clock. Only tracks *when this process
    // loaded the model*, not anything about the weights themselves (RFC-0103 Phase E's Engine
    // Status screen needs "loaded Nm ago", which is otherwise unknowable -- InferenceBackend has
    // no notion of load time at all).
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) : ModelRuntime {

    // Single global lock — no per-model lock, because loading saturates RAM (RFC-0022).
    private val admissionQueue = Mutex()
    // Immutable snapshot: written only inside admissionQueue, read without the lock.
    // @Volatile guarantees the latest snapshot is visible to any thread, including the
    // non-suspending `loaded()`/`loadedAtMillis()` accessors, which cannot acquire the mutex.
    @Volatile private var loadedModels: Map<String, LoadedModel> = emptyMap()

    private data class LoadedModel(val adapter: ModelAdapter, val loadedAtMillis: Long)

    override suspend fun catalog(): List<ModelDescriptor> = backend.catalog()

    override suspend fun installed(): List<ModelDescriptor> = backend.installed()

    /**
     * Loads a model, serialized through the global admission queue.
     *
     * Verifies the digest before returning; deletes the weights if they don't match.
     */
    override suspend fun load(modelId: String): Result<ModelAdapter> {
        // Fast path: already loaded (checked before entering the queue).
        loadedModels[modelId]?.let { return Result.success(it.adapter) }

        return admissionQueue.withLock {
            // Re-check inside the lock — another load may have completed while we waited.
            loadedModels[modelId]?.let { return@withLock Result.success(it.adapter) }

            backend.installed().find { it.id == modelId }
                ?: return@withLock Result.failure(
                    IllegalStateException("Model $modelId is not installed")
                )

            // Verify digest before loading into memory (RFC-0022, M20).
            //
            // The expected digest comes from the catalog -- the known-good value pinned ahead of
            // any download -- never from installed()'s own descriptor. installed() computes its
            // digest from the file currently on disk, so comparing against it would only ever
            // re-hash the same bytes twice: a same-call race detector, not a corruption/
            // substitution check. Comparing against the catalog's independently-sourced value is
            // what actually lets a mismatch mean something (see DigestMismatchException).
            val catalogDigest = backend.catalog().find { it.id == modelId }?.digest
            if (catalogDigest != null) {
                val actualDigest = backend.computeDigest(modelId)
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

            val adapter = backend.load(modelId).getOrElse { err ->
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

    /**
     * When [modelId] was loaded, as epoch millis -- null if it isn't currently resident.
     * RFC-0103 Phase E's Engine Status screen uses this for "loaded Nm ago" instead of a
     * fabricated placeholder.
     */
    fun loadedAtMillis(modelId: String): Long? = loadedModels[modelId]?.loadedAtMillis

    /**
     * Physically deletes model weights from disk (RFC-0022).
     */
    suspend fun delete(modelId: String) {
        admissionQueue.withLock {
            // Unload first if it's resident
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
 *
 * The MVP backend (M21) uses llama.cpp via JNI/JNA with GGUF weights. This interface
 * exists so [GlobalModelRuntime] can be tested without a real inference binary.
 */
interface InferenceBackend {
    suspend fun catalog(): List<ModelDescriptor>
    suspend fun installed(): List<ModelDescriptor>
    suspend fun computeDigest(modelId: String): String
    suspend fun delete(modelId: String)
    suspend fun load(modelId: String): Result<ModelAdapter>
    suspend fun unload(modelId: String)
}

/**
 * Thrown when a weight file's digest does not match its catalog entry.
 *
 * The runtime deletes the file and throws; there is no quarantine path (RFC-0022).
 */
class DigestMismatchException(
    val modelId: String,
    val expected: String,
    val actual: String,
) : RuntimeException(
    "Digest mismatch for model $modelId: expected $expected but got $actual. " +
            "Weights have been deleted. Re-install the model."
)
