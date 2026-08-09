package dev.aidos.routing

import dev.aidos.kernel.BatteryState
import dev.aidos.kernel.DegradationRung
import dev.aidos.kernel.DeviceSignals
import dev.aidos.kernel.MemoryPressureLevel

/**
 * Computes which degradation rungs (RFC-0045) should be active given the device's current
 * signals. Pure and stateless: callers own tracking transitions (open/close in
 * `degradation_events`) by diffing successive calls' results — this class only answers "given
 * things as they are right now, what should be true".
 *
 * MVP scope only: rungs 1 (PAUSE_INDEXING), 2 (UNLOAD_MODEL), 4 (SUSPEND_DEFERRED_WORK), and 5
 * (PARK_RUNS). Rungs 3 and 6 are declared in [DegradationRung] for completeness but this ladder
 * never activates them — they're explicitly post-MVP in RFC-0045.
 */
class DegradationLadder(
    /** Below this charge percentage while not charging, rung 4 (SUSPEND_DEFERRED_WORK) triggers. */
    private val batteryFloorPercent: Int = 20,
) {
    /** Active rungs mapped to a short, user-visible reason (RFC-0045: "degradation is announced, not silent"). */
    fun evaluate(signals: DeviceSignals): Map<DegradationRung, String> {
        val active = mutableMapOf<DegradationRung, String>()

        when (signals.memoryPressure) {
            MemoryPressureLevel.BACKGROUND_PRESSURE ->
                active[DegradationRung.PAUSE_INDEXING] = "sustained background pressure"
            MemoryPressureLevel.ELEVATED -> {
                active[DegradationRung.PAUSE_INDEXING] = "sustained background pressure"
                active[DegradationRung.UNLOAD_MODEL] = "memory pressure"
            }
            MemoryPressureLevel.CONTINUED -> {
                // Rung 3 (drop knowledge caches) is the correct next step here but is post-MVP —
                // rungs 1 and 2 stay active rather than silently doing nothing at this severity.
                active[DegradationRung.PAUSE_INDEXING] = "sustained background pressure"
                active[DegradationRung.UNLOAD_MODEL] = "memory pressure"
            }
            MemoryPressureLevel.CRITICAL -> {
                active[DegradationRung.PAUSE_INDEXING] = "sustained background pressure"
                active[DegradationRung.UNLOAD_MODEL] = "memory pressure"
                active[DegradationRung.PARK_RUNS] = "critical memory"
            }
            MemoryPressureLevel.NONE -> {}
        }

        if (isLowBattery(signals.battery)) {
            active[DegradationRung.SUSPEND_DEFERRED_WORK] = "low battery, not charging"
        }

        // Rung 6 (thermal) is post-MVP — signals.thermal is accepted but not actioned yet.

        return active
    }

    private fun isLowBattery(battery: BatteryState): Boolean =
        !battery.isCharging && battery.percent < batteryFloorPercent
}
