package dev.aidos.modelruntime

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelRuntime
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Global model runtime with admission queue and LRU eviction (RFC-0020, RFC-0022, M20, PIPELINE.md §2.2).
 *
 * Invariants enforced here:
 * - Weights are user-scope, not per-project. This runtime is a singleton per process.
 * - Loading is globally serialized through a single admission queue. A model that is already
 *   resident is returned without joining that queue, so a request for a loaded model never waits
 *   behind another model's multi-second load.
 * - Digest is verified before a normal model-id load is returned.
 * - LRU context eviction bounds simultaneously resident models while protecting active requests
 *   and warm fallback candidates (RFC-0105).
 * - Deterministic native disposal: evicted models are unloaded immediately.
 * - Idle timeout: Models unloaded if unused for configurable duration (default 5 minutes, RFC-0020).
 */
class GlobalModelRuntime(
    private val backend: InferenceBackend,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
    private val runSettings: RunSettingsSource = RunSettingsSource.Defaults,
    private val maxResidentModels: Int = Int.MAX_VALUE,
    private val isModelActive: (String) -> Boolean = { false },
    private val memoryAdmissionCheck: (suspend (modelId: String, descriptor: ModelDescriptor?) -> Result<Unit>)? = null,
    private val idleTimeoutMillis: Long = 5 * 60 * 1000, // 5 minutes default (RFC-0020)
    /**
     * RFC-0022: memory-based admission. When both are set, loading evicts least-recently-used idle
     * models until the new model's estimated resident bytes (weights + KV cache at its settings)
     * fit under [residentBudgetBytes]; a model that cannot be made to fit is refused rather than
     * left to take the process down. [maxResidentModels] still applies as an upper bound.
     */
    private val residentBudgetBytes: Long? = null,
    private val residentEstimate: ((descriptor: ModelDescriptor?, settings: ModelRunSettings) -> Long)? = null,
) : ModelRuntime {

    private val admissionQueue = Mutex()
    @Volatile private var loadedModels: Map<String, LoadedModel> = emptyMap()
    @Volatile private var warmPinnedModels: Set<String> = emptySet()

    private class LoadedModel(
        val adapter: ModelAdapter,
        val loadedAtMillis: Long,
        val settings: ModelRunSettings,
        val residentBytes: Long = 0L,
    ) {
        // Mutable so a cache hit can record the access without rewriting the map: a write to the
        // map outside the admission queue could put back an entry that an eviction just removed.
        @Volatile var lastAccessedAtMillis: Long = loadedAtMillis
    }

    // Held only for the instant it takes to hand out a resident adapter or to take a model out
    // of the map, never across a load or an unload. It makes "is it in use? then remove it" one
    // step against the fast path in [load], so a request cannot be handed an adapter that an
    // eviction has already decided to free.
    private val residentLock = Mutex()

    private suspend fun residentAdapter(modelId: String): ModelAdapter? = residentLock.withLock {
        loadedModels[modelId]?.let {
            it.lastAccessedAtMillis = nowMillis()
            it.adapter
        }
    }

    /** Takes an idle [modelId] out of the resident map; false when it is in use or [alsoRequire] fails. */
    private suspend fun retireIfIdle(modelId: String, alsoRequire: (LoadedModel) -> Boolean = { true }): Boolean =
        residentLock.withLock {
            val model = loadedModels[modelId] ?: return@withLock false
            if (isModelActive(modelId) || !alsoRequire(model)) return@withLock false
            loadedModels = loadedModels - modelId
            true
        }

    private suspend fun retire(modelId: String): Boolean = residentLock.withLock {
        val present = loadedModels.containsKey(modelId)
        loadedModels = loadedModels - modelId
        present
    }

    override suspend fun catalog(): List<ModelDescriptor> = backend.catalog()

    override suspend fun installed(): List<ModelDescriptor> = backend.installed()

    /**
     * Marks [modelId] as a warm local fallback candidate (RFC-0105) so LRU eviction prefers
     * evicting non-pinned models while a group/peer candidate is active, unless no other inactive
     * resident model can be evicted.
     */
    fun setWarmFallbackPinned(modelId: String, pinned: Boolean) {
        warmPinnedModels = if (pinned) warmPinnedModels + modelId else warmPinnedModels - modelId
    }

    fun isWarmFallbackPinned(modelId: String): Boolean = modelId in warmPinnedModels

    /**
     * Ensures [modelId] is loaded and pinned as a warm local fallback if memory admission allows it.
     */
    suspend fun ensureWarmFallback(modelId: String): Result<ModelAdapter> {
        setWarmFallbackPinned(modelId, true)
        val res = load(modelId)
        if (res.isFailure) {
            setWarmFallbackPinned(modelId, false)
        }
        return res
    }

    /**
     * Periodic check for idle models and unload them to free memory (RFC-0020).
     * Called on a timer (e.g., every 30 seconds) to enforce idle timeout.
     * Models unused for longer than [idleTimeoutMillis] are unloaded.
     */
    suspend fun checkAndUnloadIdleModels() {
        admissionQueue.withLock {
            val now = nowMillis()
            val modelsToUnload = loadedModels.entries
                .filter { (modelId, loadedModel) ->
                    val idleTimeMs = now - loadedModel.lastAccessedAtMillis
                    idleTimeMs > idleTimeoutMillis &&
                        !isModelActive(modelId) &&
                        modelId !in warmPinnedModels
                }
                .map { it.key }

            modelsToUnload.forEach { modelId ->
                val stillIdle = retireIfIdle(modelId) {
                    nowMillis() - it.lastAccessedAtMillis > idleTimeoutMillis && modelId !in warmPinnedModels
                }
                if (stillIdle) backend.unload(modelId)
            }
        }
    }

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
        // Fast path, outside the queue: the queue is held for the whole of another model's load.
        residentAdapter(modelId)?.let { return Result.success(it) }
        return admissionQueue.withLock {
            // Checked again: the model may have been loaded while this call waited its turn.
            residentAdapter(modelId)?.let { return@withLock Result.success(it) }

            val installedList = backend.installed()
            val descriptor = installedList.find { it.id == modelId }
                ?: backend.catalog().find { it.id == modelId }

            if (artifactPath == null) {
                if (descriptor == null || installedList.none { it.id == modelId }) {
                    return@withLock Result.failure(
                        ModelNotInstalledException(modelId, "Model $modelId is not installed")
                    )
                }

                val catalogDigest = backend.catalog().find { it.id == modelId }?.digest
                if (catalogDigest != null) {
                    val actualDigest = backend.computeDigest(modelId)
                    if (actualDigest != catalogDigest) {
                        // RFC-0022: moved out of the store (quarantined), not kept and not served.
                        backend.quarantine(modelId, catalogDigest, actualDigest)
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
                    val error = admission.exceptionOrNull() ?: InsufficientMemoryException("admission check failed")
                    return@withLock Result.failure(error)
                }
            }

            // LRU eviction if resident limit reached (prefer non-pinned models first; only evict a
            // warm-pinned model if no other inactive resident model exists).
            if (loadedModels.size >= maxResidentModels && !loadedModels.containsKey(modelId)) {
                val inactiveSorted = loadedModels.entries
                    .sortedBy { it.value.lastAccessedAtMillis }
                    .filter { !isModelActive(it.key) }
                val evictCandidate = inactiveSorted.firstOrNull { it.key !in warmPinnedModels }
                    ?: inactiveSorted.firstOrNull()

                if (evictCandidate != null && retireIfIdle(evictCandidate.key)) {
                    backend.unload(evictCandidate.key)
                } else {
                    return@withLock Result.failure(
                        ResidentModelsBusyException("Cannot evict resident models; active requests in progress")
                    )
                }
            }

            // Settings are read once, here, so what the card shows as "loaded with" is what the backend got.
            val settings = runSettings.settingsFor(modelId).coerced()

            // RFC-0022 memory admission: evict LRU idle models until the newcomer's resident estimate fits.
            val estimate = if (residentBudgetBytes != null && residentEstimate != null) residentEstimate.invoke(descriptor, settings) else 0L
            if (residentBudgetBytes != null && residentEstimate != null) {
                if (estimate > residentBudgetBytes) {
                    return@withLock Result.failure(
                        InsufficientMemoryException("$modelId needs ~$estimate bytes resident, budget is $residentBudgetBytes")
                    )
                }
                while (loadedModels.values.sumOf { it.residentBytes } + estimate > residentBudgetBytes) {
                    val inactiveSorted = loadedModels.entries
                        .sortedBy { it.value.lastAccessedAtMillis }
                        .filter { !isModelActive(it.key) }
                    val victim = inactiveSorted.firstOrNull { it.key !in warmPinnedModels } ?: inactiveSorted.firstOrNull()
                        ?: return@withLock Result.failure(
                            ResidentModelsBusyException("Cannot free enough memory for $modelId; active requests in progress")
                        )
                    // A victim that became active since the list was made is skipped on the next pass.
                    if (retireIfIdle(victim.key)) backend.unload(victim.key)
                }
            }
            val adapter = backend.load(modelId, artifactPath, settings).getOrElse { err ->
                return@withLock Result.failure(err)
            }
            val now = nowMillis()
            residentLock.withLock { loadedModels = loadedModels + (modelId to LoadedModel(adapter, now, settings, estimate)) }
            Result.success(adapter)
        }
    }

    override suspend fun unload(modelId: String) {
        admissionQueue.withLock {
            warmPinnedModels = warmPinnedModels - modelId
            retire(modelId)
            backend.unload(modelId)
        }
    }

    fun loadedAtMillis(modelId: String): Long? = loadedModels[modelId]?.loadedAtMillis

    /** The settings a resident model was loaded with, or null when it is not loaded. */
    fun loadedSettings(modelId: String): ModelRunSettings? = loadedModels[modelId]?.settings

    /** Idle time in milliseconds for a loaded model, or null if not loaded. */
    fun idleTimeMs(modelId: String): Long? = loadedModels[modelId]?.let {
        nowMillis() - it.lastAccessedAtMillis
    }

    override suspend fun delete(modelId: String) {
        admissionQueue.withLock {
            warmPinnedModels = warmPinnedModels - modelId
            if (retire(modelId)) backend.unload(modelId)
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
    /**
     * Takes a model whose digest did not match out of the store (RFC-0022). Backends that keep
     * files move them to `quarantine/`; the default deletes, so no backend keeps a mismatched model.
     */
    suspend fun quarantine(modelId: String, expectedDigest: String, actualDigest: String) = delete(modelId)
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
            "The file was quarantined and will not be used. Re-install the model."
)
