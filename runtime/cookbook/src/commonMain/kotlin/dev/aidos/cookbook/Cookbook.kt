package dev.aidos.cookbook

import dev.aidos.kernel.ModelDescriptor

/**
 * Cookbook verdict: which models will actually run on this device (RFC-0022).
 *
 * The cookbook answers one question — "which models will actually work on my phone?" —
 * and it answers it by computing, not by publishing a list of sizes and hoping.
 * Verdicts are **computed** against the device's actual capabilities, not asserted.
 */
enum class CookbookVerdict {
    /** Fits with headroom; sustained use is unlikely to be reclaimed by the OS. */
    RUNS_WELL,

    /** Fits, but the app is a likely victim if another app demands memory. */
    RUNS_TIGHT,

    /** Weights fit; the requested context does not. Offer a shorter context. */
    EXCEEDS_CONTEXT,

    /** Weights alone exceed what is available. */
    WILL_NOT_FIT,
}

/**
 * Device profiling information (RFC-0022, RFC-0049).
 *
 * Sampled at first run and re-sampled when it can change. The cookbook uses this to
 * determine which models fit on the device.
 */
data class DeviceProfile(
    /** Total RAM in bytes. */
    val totalRamBytes: Long,

    /** Available RAM in bytes (free at profile time). */
    val availableRamBytes: Long,

    /** Free storage space in bytes. */
    val storageFreeBytes: Long,

    /** Number of CPU cores. */
    val cpuCoreCount: Int,

    /** Whether NPU or GPU delegates are present. */
    val hasAccelerator: Boolean,

    /** Thermal headroom (0-100), higher is cooler. */
    val thermalHeadroom: Int = 50,

    /** Battery level floor for inference (0-100). */
    val batteryFloor: Int = 20,
)

/**
 * Model requirements for running on a device (RFC-0022).
 *
 * Computed from the model metadata. The KV cache is the critical part that catches people:
 * a 4GB quantized 7B does not need 4GB — it needs 4GB plus a cache that grows linearly
 * with the context window.
 */
data class ModelRequirements(
    /** Weights file size in bytes (post-quantization). */
    val weightsBytesOnDisk: Long,

    /** Context window in tokens (e.g., 4096, 8192, 32768). */
    val contextWindow: Int,

    /** Model parameter count (e.g., 7e9 for 7B). */
    val parameterCount: Long,

    /** Quantization type (e.g., "Q4_K_M", "fp16"). */
    val quantizationType: String,
)

/**
 * Cookbook entry: verdict for a model on a device at a specific context length.
 */
data class CookbookEntry(
    val model: ModelDescriptor,
    val device: DeviceProfile,
    val contextWindow: Int,
    val verdict: CookbookVerdict,
    val residentEstimateBytes: Long,
    val measurements: PerformanceMeasurement? = null,
)

/**
 * Measured performance for a model on this device (replaces predictions after first run).
 */
data class PerformanceMeasurement(
    /** Cold-start time in milliseconds. */
    val coldStartMs: Long,

    /** Tokens generated per second. */
    val tokensPerSecond: Float,

    /** When this measurement was taken. */
    val measuredAt: String, // ISO 8601
)

/**
 * Cookbook engine: computes model fit on a device (RFC-0022).
 *
 * The cookbook **filters and ranks a curated set of models** against the device,
 * computing (not asserting) which models will run where.
 */
class CookbookEngine {

    /**
     * Computes the resident memory requirement for a model on a device.
     *
     * Resident memory = weights + KV cache + runtime overhead
     *
     * The KV cache grows linearly with context window and can exceed weights themselves
     * at longer contexts. This is the part that catches naive implementations.
     *
     * [KV_CACHE_BYTES_PER_TOKEN] and [OVERHEAD_FRACTION] are calibrated against RFC-0022's own
     * worked example (Qwen2.5 3B Q4_K_M, 2.0GB weights): 4k context -> 2.4GB resident (runs
     * well), 16k -> 3.3GB, 32k -> 4.6GB (will not fit on a device with 3.9GB available). RFC-0022
     * does not mandate exact constants beyond that table, so these reproduce it directly rather
     * than being independently invented.
     *
     * @param requirements model metadata
     * @param device device profile (memory, accelerators, etc.)
     * @param contextWindow context length in tokens (may differ from model's default)
     * @return resident memory estimate in bytes
     */
    fun computeResidentMemory(
        requirements: ModelRequirements,
        device: DeviceProfile,
        contextWindow: Int,
    ): Long {
        val weightsInRam = requirements.weightsBytesOnDisk
        val kvCacheBytes = contextWindow.toLong() * KV_CACHE_BYTES_PER_TOKEN
        val overheadBytes = (weightsInRam * OVERHEAD_FRACTION).toLong()

        return weightsInRam + kvCacheBytes + overheadBytes
    }

    /**
     * Computes the verdict for a model on a device at a specific context window.
     */
    fun verdict(
        model: ModelDescriptor,
        device: DeviceProfile,
        contextWindow: Int,
        measurements: PerformanceMeasurement? = null,
    ): CookbookVerdict {
        val sizeBytes = model.sizeBytes
        if (sizeBytes == null) {
            // Cannot judge without size; assume worst
            return CookbookVerdict.WILL_NOT_FIT
        }

        val requirements = ModelRequirements(
            weightsBytesOnDisk = sizeBytes,
            contextWindow = contextWindow,
            parameterCount = estimateParams(model),
            quantizationType = "unknown",
        )

        val resident = computeResidentMemory(requirements, device, contextWindow)

        // Case 1: Weights alone don't fit
        if (sizeBytes > device.availableRamBytes) {
            return CookbookVerdict.WILL_NOT_FIT
        }

        // Case 2: Full resident doesn't fit
        if (resident > device.availableRamBytes) {
            // Does it fit at a shorter context?
            val contextThatFits = (device.availableRamBytes - sizeBytes - (sizeBytes * OVERHEAD_FRACTION))
                .toLong() / KV_CACHE_BYTES_PER_TOKEN
            return if (contextThatFits > 256) {
                CookbookVerdict.EXCEEDS_CONTEXT
            } else {
                CookbookVerdict.WILL_NOT_FIT
            }
        }

        // Case 3: It fits. Is there headroom?
        val headroom = device.availableRamBytes - resident
        val headroomPercent = (headroom * 100) / device.availableRamBytes

        // Headroom thresholds: more than 30% = RUNS_WELL, 10-30% = RUNS_TIGHT, less = EXCEEDS_CONTEXT
        return when {
            headroomPercent >= 30 -> CookbookVerdict.RUNS_WELL
            headroomPercent >= 10 -> CookbookVerdict.RUNS_TIGHT
            else -> CookbookVerdict.EXCEEDS_CONTEXT
        }
    }

    /**
     * Estimates parameter count from model size and quantization.
     * This is a rough heuristic; actual numbers come from model metadata.
     */
    private fun estimateParams(model: ModelDescriptor): Long {
        // Very rough: 7B is typically 4-5GB (quantized), so ~1.4-1.7 bytes per param
        // Use 1.5 as average
        return (model.sizeBytes ?: 1_000_000_000) / 1_500
    }

    private companion object {
        /** Bytes of KV cache per context token — see [computeResidentMemory] for calibration. */
        const val KV_CACHE_BYTES_PER_TOKEN = 76_800L

        /** Runtime overhead (model structure, state, buffers) as a fraction of weights. */
        const val OVERHEAD_FRACTION = 0.05
    }
}
