package dev.aidos.policy

import dev.aidos.routes.Route
import dev.aidos.routes.RouteLatency
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Locality ceiling governing which route types may be used.
 *
 * - [LOCAL_ONLY]: Only LAN discovery (`Route.Lan`) is permitted; direct WAN/overlay routes and
 *   SSH reverse tunnels are blocked.
 * - [TRUSTED_CLUSTER]: LAN and direct peer routes (`Route.Direct`) are permitted; SSH reverse
 *   tunnels remain blocked.
 * - [ANY]: All route types (`Route.Lan`, `Route.Direct`, `Route.SshReverse`) are permitted for
 *   routing, though layer split remains disallowed over tunnels.
 */
enum class Locality {
    LOCAL_ONLY,
    TRUSTED_CLUSTER,
    ANY,
}

/**
 * RFC-0108 Phase 2: Policy for which routes can carry split inference and pass locality checks.
 *
 * Layer split (RFC-0105) is only safe over LAN routes (or low-RTT direct cluster links when
 * locality allows) due to overhead (~10ms per token per helper).
 * SSH reverse tunnels (`Route.SshReverse`) are never safe for layer split regardless of RTT.
 *
 * Defaults to [Locality.LOCAL_ONLY] (fail-closed) so remote/tunnel routes are never permitted
 * unless a wider [Locality] is explicitly selected.
 */
class RoutePlannerPolicy(
    private val lanRttThreshold: Duration = 50.milliseconds,
    private val defaultLocality: Locality = Locality.LOCAL_ONLY,
) {
    init {
        require(!lanRttThreshold.isNegative()) { "lanRttThreshold must be non-negative" }
    }

    /**
     * Checks whether [route] is permitted under [locality].
     * Under [Locality.LOCAL_ONLY], only [Route.Lan] is permitted.
     */
    fun isRoutePermitted(route: Route, locality: Locality = defaultLocality): Boolean = when (locality) {
        Locality.LOCAL_ONLY -> route == Route.Lan
        Locality.TRUSTED_CLUSTER -> route == Route.Lan || route is Route.Direct
        Locality.ANY -> true
    }

    /**
     * Filters [latencies] to routes permitted under [locality] (excluding failed `rttMillis < 0`).
     */
    fun filterPermittedRoutes(
        latencies: List<RouteLatency>,
        locality: Locality = defaultLocality,
    ): List<RouteLatency> {
        return latencies
            .filter { it.rttMillis >= 0 && isRoutePermitted(it.route, locality) }
            .sortedBy { it.rttMillis }
    }

    /**
     * Decides whether a route can be used for layer split under [locality].
     *
     * Returns true only if:
     * 1. Measured RTT is non-negative (`rttMillis >= 0`), AND
     * 2. Route is permitted by [locality], AND
     * 3. Either route is `Route.Lan`, OR route is `Route.Direct` with `rttMillis <= lanRttThreshold`.
     *
     * Returns false for:
     * - Unmeasured/failed routes (`rttMillis = -1`)
     * - Any non-LAN route under [Locality.LOCAL_ONLY]
     * - Any SSH reverse tunnel (`Route.SshReverse`), regardless of RTT or locality
     * - Direct routes with `rttMillis > lanRttThreshold`
     */
    fun isSafeForSplit(latency: RouteLatency, locality: Locality = defaultLocality): Boolean {
        val rtt = latency.rttMillis
        if (rtt < 0) return false
        if (!isRoutePermitted(latency.route, locality)) return false

        return when (latency.route) {
            Route.Lan -> true
            is Route.Direct -> locality != Locality.LOCAL_ONLY && rtt <= lanRttThreshold.inWholeMilliseconds
            is Route.SshReverse -> false
        }
    }

    /**
     * Decides if any route in a set of latencies is safe for split under [locality].
     */
    fun hasLocalRoute(latencies: List<RouteLatency>, locality: Locality = defaultLocality): Boolean {
        return latencies.any { isSafeForSplit(it, locality) }
    }

    /**
     * Filters a list of latencies to only those safe for split under [locality].
     * Returns them in RTT order (fastest first).
     */
    fun filterLocalRoutes(
        latencies: List<RouteLatency>,
        locality: Locality = defaultLocality,
    ): List<RouteLatency> {
        return latencies
            .filter { isSafeForSplit(it, locality) }
            .sortedBy { it.rttMillis }
    }

    /**
     * For a peer with multiple latencies, selects the best one for split under [locality].
     * Returns null if no route is safe.
     */
    fun selectBestLocalRoute(
        latencies: List<RouteLatency>,
        locality: Locality = defaultLocality,
    ): RouteLatency? {
        return filterLocalRoutes(latencies, locality).firstOrNull()
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
    /** SSH reverse tunnel — typically WAN, never safe for layer split */
    SshTunnel,
}

fun Route.classify(): RouteClass = when (this) {
    Route.Lan -> RouteClass.Lan
    is Route.Direct -> RouteClass.Direct
    is Route.SshReverse -> RouteClass.SshTunnel
}
