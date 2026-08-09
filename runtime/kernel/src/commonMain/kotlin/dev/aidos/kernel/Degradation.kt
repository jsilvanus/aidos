package dev.aidos.kernel

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * The degradation ladder (RFC-0045). When a device cannot meet performance targets, the runtime
 * degrades in a fixed order, most-recoverable first. Each rung is user-visible (never silent)
 * and independently triggered — more than one may be active at once, since rungs respond to
 * different signals (memory pressure vs. battery vs. thermal), not one escalating severity.
 *
 * MVP scope only (RFC-0045 "MVP" section): rungs 1, 2, 4, and 5. Rung 3 (drop knowledge index
 * caches) and rung 6 (thermal throttling disables local inference) are explicitly post-MVP and
 * declared here for completeness against the schema's `rung INTEGER` column, but nothing
 * computes them yet.
 */
@Serializable
enum class DegradationRung(val level: Int) {
    /** Sustained background pressure -> pause indexing and compaction. */
    PAUSE_INDEXING(1),

    /** Memory pressure -> unload the loaded model; keep weights on disk. */
    UNLOAD_MODEL(2),

    /** Continued pressure -> drop knowledge index caches; queries degrade to keyword. Post-MVP. */
    DROP_KNOWLEDGE_CACHE(3),

    /** Low battery, not charging -> suspend all DEFERRED and OPPORTUNISTIC work (RFC-0044). */
    SUSPEND_DEFERRED_WORK(4),

    /** Critical memory -> park active Runs at the next checkpoint; do not start new ones. */
    PARK_RUNS(5),

    /** Thermal throttling -> disable local inference; route remote or UNAVAILABLE_OFFLINE. Post-MVP. */
    DISABLE_LOCAL_INFERENCE(6),
}

/**
 * Memory pressure as a fixed severity ladder, not a raw metric — the runtime's decision only
 * ever needs "how bad", not the underlying number, and platform-specific memory APIs (Android's
 * `ComponentCallbacks2` trim levels, JVM heap stats, ...) each map onto this differently. That
 * mapping is a platform concern; this type is not one.
 */
@Serializable
enum class MemoryPressureLevel {
    NONE,
    /** Rung 1 trigger. */
    BACKGROUND_PRESSURE,
    /** Rung 2 trigger. */
    ELEVATED,
    /** Rung 3 trigger. Not actioned in MVP. */
    CONTINUED,
    /** Rung 5 trigger. */
    CRITICAL,
}

@Serializable
data class BatteryState(
    val percent: Int,
    val isCharging: Boolean,
)

/** Post-MVP (rung 6) — declared so [DeviceSignals] is complete against the RFC's ladder table. */
@Serializable
enum class ThermalState {
    NORMAL,
    THROTTLING,
}

@Serializable
data class DeviceSignals(
    val memoryPressure: MemoryPressureLevel,
    val battery: BatteryState,
    val thermal: ThermalState = ThermalState.NORMAL,
)

/**
 * A recorded rung transition (RFC-0045 `degradation_events`) — exists so "why was it slow last
 * Tuesday?" is answerable. `projectId` is null for device-wide rungs (e.g. [DegradationRung.SUSPEND_DEFERRED_WORK]).
 */
@Serializable
data class DegradationEvent(
    val id: String,
    val rung: DegradationRung,
    val trigger: String,
    val enteredAt: Instant,
    val exitedAt: Instant?,
    val projectId: String?,
)
