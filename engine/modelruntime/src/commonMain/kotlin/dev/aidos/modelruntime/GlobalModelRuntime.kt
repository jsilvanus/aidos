package dev.aidos.modelruntime

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Global model runtime with admission queue and LRU eviction (RFC-0022, M20, PIPELINE.md §2.2).
 *
 * Invariants enforced here:
 * - Weights are user-scope, not per-project. This runtime is a singleton per process.
 * - Loading is globally serialized through a single admission queue.
 * - Digest is verified before a normal model-id load is returned.
 * - LRU context eviction bounds simultaneously resident models while protecting active requests.
 * - Deterministic native disposal: evicted models are unloaded immediately.
 */
class GlobalModelRuntime(
    private val backend: InferenceBackend,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val runSettings: RunSettingsSource = RunSettingsSource.Defaults,
    private val maxResidentModels: Int = Int.MAX_VALUE,
    private val isModelActive: (String) -> Boolean = { false },
    private val memoryAdmissionCheck: (suspend (modelId: String, descriptor: ModelDescriptor?) -> Result<Unit>)? = null,
) : ModelRuntime {

    private val admissionQueue = Mutex()
    @Volatile private var loadedModels: Map<String, LoadedModel> = emptyMap()

    private data class LoadedModel(
        val adapter: ModelAdapter,
        val loadedAtMillis: Long,
        val settings: ModelRunSettings,
        val lastAccessedAtMillis: Long = loadedAtMillis,
    )

    override suspend fun catalog(): List<ModelDescriptor> = backend.catalog()

    override suspend fun installed(): List<ModelDescriptor> = backend.installed()

    /** Loads a model using the backend's normal model-id resolution and digest verification. */
    override suspend fun load(modelId: String): Result<ModelAdapter> =
        load(modelId, artifactPath = null)

    /**
     * Loads a model from the exact installed artifact selected by the caller.
     *
     * Android's persistent model catalog can point to a quantized artifact whose filename is
     * not identical to the logical model id. In that case requiring `installed()` to contain the
     * logical id would reject a valid catalog entry before the backend gets the exact path.
     */
    suspend fun load(modelId: String, artifactPath: String?): Result<ModelAdapter> {
        loadedModels[modelId]?.let {
            val updated = it.copy(lastAccessedAtMillis = nowMillis())
            loadedModels = loadedModels + (modelId to updated)
            return Result.success(it.adapter)
        }

        return admissionQueue.withLock {
            loadedModels[modelId]?.let {
                val updated = it.copy(lastAccessedAtMillis = nowMillis())
                loadedModels = loadedModels + (modelId to updated)
                return@withLock Result.success(it.adapter)
            }

            val installedList = backend.installed()
            val descriptor = installedList.find { it.id == modelId }
                ?: backend.catalog().find { it.id == modelId }

            if (artifactPath == null) {
                if (descriptor == null || installedList.none { it.id == modelId }) {
                    return@withLock Result.failure(
                        IllegalStateException("Model $modelId is not installed")
                    )
                }

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
            }

            // Admission memory check (available RAM vs model requirements)
            if (memoryAdmissionCheck != null) {
                val admission = memoryAdmissionCheck.invoke(modelId, descriptor)
                if (admission.isFailure) {
                    val error = admission.exceptionOrNull() ?: IllegalStateException("INSUFFICIENT_MEMORY: admission check failed")
                    return@withLock Result.failure(error)
                }
            }

            // LRU eviction if resident limit reached
            if (loadedModels.size >= maxResidentModels && !loadedModels.containsKey(modelId)) {
                val evictCandidate = loadedModels.entries
                    .sortedBy { it.value.lastAccessedAtMillis }
                    .firstOrNull { !isModelActive(it.key) }

                if (evictCandidate != null) {
                    val evictId = evictCandidate.key
                    loadedModels = loadedModels - evictId
                    backend.unload(evictId)
                } else {
                    return@withLock Result.failure(
                        IllegalStateException("ENGINE_BUSY: Cannot evict resident models; active requests in progress")
                    )
                }
            }

            // Settings are read once, here, so what the card shows as "loaded with" is what the backend got.
            val settings = runSettings.settingsFor(modelId).coerced()
            val adapter = backend.load(modelId, artifactPath, settings).getOrElse { err ->
                return@withLock Result.failure(err)
            }
            val now = nowMillis()
            loadedModels = loadedModels + (modelId to LoadedModel(adapter, now, settings, now))
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

    /** The settings a resident model was loaded with, or null when it is not loaded. */
    fun loadedSettings(modelId: String): ModelRunSettings? = loadedModels[modelId]?.settings

    override suspend fun delete(modelId: String) {
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

/** The inference backend that [GlobalModelRuntime] delegates real work to. */
interface InferenceBackend {
    suspend fun catalog(): List<ModelDescriptor>
    suspend fun installed(): List<ModelDescriptor>
    suspend fun computeDigest(modelId: String): String
    suspend fun computeDigest(modelId: String, artifactPath: String?): String = computeDigest(modelId)
    suspend fun delete(modelId: String)
    suspend fun load(modelId: String): Result<ModelAdapter>
    suspend fun load(modelId: String, artifactPath: String?): Result<ModelAdapter> = load(modelId)
    /** [settings] only matter to backends that run llama.cpp; the default ignores them. */
    suspend fun load(modelId: String, artifactPath: String?, settings: ModelRunSettings): Result<ModelAdapter> =
        load(modelId, artifactPath)
    suspend fun unload(modelId: String)
}

/** Thrown when a weight file's digest does not match its catalog entry. */
class DigestMismatchException(
    val modelId: String,
    val expected: String,
    val actual: String,
) : RuntimeException(
    "Digest mismatch for model $modelId: expected $expected but got $actual. " +
            "Weights have been deleted. Re-install the model."
)
