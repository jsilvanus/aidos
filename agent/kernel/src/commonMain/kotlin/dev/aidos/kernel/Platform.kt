package dev.aidos.kernel

/**
 * What this device can do (RFC-0049).
 *
 * Determined at startup, immutable for the process lifetime, and exposed on the Runtime API —
 * frontends must render availability, not discover it by failure.
 */
enum class PlatformProfile { MOBILE, DESKTOP, HEADLESS_SERVER }

enum class AvailabilityTier {
    /** Every profile, offline. Filesystem, Git object database, Git working tree. */
    UNIVERSAL,

    /** A native binary fixed at build time inside the app package. Never a shell. */
    BUNDLED,

    /** Some profiles only. Shell, arbitrary subprocess, stdio MCP. */
    PLATFORM,

    /** Requires connectivity. Remote models, HTTP MCP, git fetch/push. */
    NETWORKED,
}

data class ToolAvailability(
    val profiles: Set<PlatformProfile>,
    val tier: AvailabilityTier,
    val requiresNetwork: Boolean = false,
) {
    fun availableOn(profile: PlatformProfile, networkAvailable: Boolean): Boolean =
        profile in profiles && (!requiresNetwork || networkAvailable)
}

/**
 * How much uninterrupted execution is available (RFC-0009).
 *
 * The executor stops cleanly at a checkpoint when the remaining window cannot cover the next
 * step, rather than starting work it cannot finish. On DESKTOP the window is effectively
 * unbounded; there is no Android-specific execution path, only a different budget.
 */
interface ExecutionWindow {
    /** Remaining milliseconds, or null when effectively unbounded. */
    fun remainingMillis(): Long?

    /**
     * Whether a local model call may be made now.
     *
     * On MOBILE this requires a foreground service (decision D24). When it returns false, a Run
     * reaching a local model call parks with [SuspendedOperation.ForegroundRequired] rather than
     * failing or silently routing to a remote model.
     */
    fun permitsLocalInference(): Boolean
}

/** What a project declared it needs, evaluated against the profile (RFC-0049). */
data class AvailabilityReport(
    val profile: PlatformProfile,
    val networkAvailable: Boolean,
    val satisfied: List<String>,
    val degraded: List<String>,
    val unsatisfied: List<String>,
)
