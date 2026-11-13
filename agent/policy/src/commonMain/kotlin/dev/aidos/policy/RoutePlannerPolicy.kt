package dev.aidos.policy

import dev.aidos.routes.Route
import dev.aidos.routes.RouteLatency
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * RFC-0108 Phase 2: Policy for which routes can carry split inference.
 *
 * Layer split (RFC-0105) is only safe over LAN routes due to overhead (~10ms per token per helper).
 * WAN routes (direct to Tailscale, SSH tunnel) are too latent.
 *
 * A route is deemed "local" (safe for split) if:
 * - It is explicitly `Route.Lan` (mDNS discovery), OR
 * - It is `Route.Direct` or `Route.SshReverse` with measured RTT ≤ threshold (default 50ms)
 *
 * If RTT is unmeasured (never tried), conservatively treat as non-local (refuse split).
 */
class RoutePlannerPolicy(
    private val lanRttThreshold: Duration = 50.milliseconds,
) {
    /**
     * Decides whether a route can be used for layer split.
     *
     * Returns true only if:
     * 1. Route is `Route.Lan` (always local), OR
     * 2. Route has measured RTT and RTT ≤ lanRttThreshold
     *
     * Returns false for:
     * - Routes without RTT measurement (no evidence of LAN speed)
     * - Routes with RTT > threshold (WAN speed; too much overhead)
     */
    fun isSafeForSplit(latency: RouteLatency): Boolean {
        val route = latency.route
        val rtt = latency.rttMillis

        // LAN routes are always safe for split
        if (route == Route.Lan) return true

        // Unmeasured routes (RTT = -1 means failure) or failed: refuse split
        if (rtt < 0) return false

        // Direct and SSH routes only safe if RTT is low (LAN speed)
        return rtt <= lanRttThreshold.inWholeMilliseconds
    }

    /**
     * Decides if any route in a set of latencies is safe for split.
     * Useful for checking if a peer can participate in a split plan.
     *
     * Returns true if at least one route meets split safety criteria.
     */
    fun hasLocalRoute(latencies: List<RouteLatency>): Boolean {
        return latencies.any { isSafeForSplit(it) }
    }

    /**
     * Filters a list of latencies to only those safe for split.
     * Returns them in RTT order (fastest first).
     */
    fun filterLocalRoutes(latencies: List<RouteLatency>): List<RouteLatency> {
        return latencies
            .filter { isSafeForSplit(it) }
            .sortedBy { it.rttMillis }
    }

    /**
     * For a peer with multiple latencies, selects the best one for split.
     * Returns null if no route is safe.
     */
    fun selectBestLocalRoute(latencies: List<RouteLatency>): RouteLatency? {
        return filterLocalRoutes(latencies).firstOrNull()
    }
}

/**
 * Classification of route type for policy decisions.
 * Independent of latency; used for UI, logging, and policy branching.
 */
enum class RouteClass {
    /** mDNS discovery (always local) */
    Lan,
    /** Direct connection (Tailscale, forwarded port, etc.) — local or WAN depending on RTT */
    Direct,
    /** SSH reverse tunnel — typically WAN, but could be fast if collocated */
    SshTunnel,
}

fun Route.classify(): RouteClass = when (this) {
    Route.Lan -> RouteClass.Lan
    is Route.Direct -> RouteClass.Direct
    is Route.SshReverse -> RouteClass.SshTunnel
}
