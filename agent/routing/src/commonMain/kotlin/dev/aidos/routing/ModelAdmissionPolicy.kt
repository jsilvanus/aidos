package dev.aidos.routing

import dev.aidos.kernel.DeviceSignals
import dev.aidos.kernel.ThermalState

/**
 * Local model admission decision (RFC-0045 §The local model admission decision).
 *
 * Admission requires:
 * 1. Available memory >= model working set * 1.3
 * 2. Not thermally throttled
 * 3. For large models (>= [largeModelThresholdBytes]), either charging or battery >= [batteryFloorPercent]
 */
class ModelAdmissionPolicy(
    private val batteryFloorPercent: Int = DEFAULT_BATTERY_FLOOR_PERCENT,
    private val largeModelThresholdBytes: Long = DEFAULT_LARGE_MODEL_THRESHOLD_BYTES,
) {
    sealed interface AdmissionDecision {
        data object Admitted : AdmissionDecision
        data class Denied(val code: String, val reason: String) : AdmissionDecision
    }

    fun evaluate(
        workingSetBytes: Long,
        availableMemoryBytes: Long,
        signals: DeviceSignals,
    ): AdmissionDecision {
        if (signals.thermal == ThermalState.THROTTLING) {
            return AdmissionDecision.Denied(
                code = "thermal_throttling",
                reason = "Device is thermally throttled",
            )
        }

        val requiredBytes = (workingSetBytes.toDouble() * MEMORY_HEADROOM_MULTIPLIER).toLong()
        if (availableMemoryBytes < requiredBytes) {
            return AdmissionDecision.Denied(
                code = "insufficient_memory",
                reason = "Available memory ${availableMemoryBytes}B < required ${requiredBytes}B (1.3x working set)",
            )
        }

        if (workingSetBytes >= largeModelThresholdBytes &&
            !signals.battery.isCharging &&
            signals.battery.percent < batteryFloorPercent
        ) {
            return AdmissionDecision.Denied(
                code = "low_battery",
                reason = "Battery ${signals.battery.percent}% < floor ${batteryFloorPercent}% and not charging",
            )
        }

        return AdmissionDecision.Admitted
    }

    companion object {
        const val MEMORY_HEADROOM_MULTIPLIER = 1.3
        const val DEFAULT_BATTERY_FLOOR_PERCENT = 20
        const val DEFAULT_LARGE_MODEL_THRESHOLD_BYTES = 1_000_000_000L
    }
}
