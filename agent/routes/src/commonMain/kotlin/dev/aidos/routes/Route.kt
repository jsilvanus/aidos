package dev.aidos.routes

/**
 * RFC-0108 Phase 1: A route is a way to open a byte stream to a known peer.
 * Routes are hints, ordered by preference. The dialer tries them in order: `lan` first,
 * then hints. TLS handshake always verifies the peer's node key, so a hint can point at
 * anything without becoming trusted.
 */
sealed interface Route {
    /** mDNS discovery (today's behavior): find peer on local network. */
    data object Lan : Route

    /**
     * Direct connection: peer can be dialed at `host:port`. Could be:
     * - A Tailscale/WireGuard/ZeroTier address (overlay network)
     * - A forwarded router port
     * - A tunnel's public end (e.g., server.example.com:47100 for a reverse tunnel)
     */
    data class Direct(
        val host: String,
        val port: Int,
    ) : Route {
        init {
            require(host.isNotBlank() && port in 1..65535) { "invalid host:port" }
        }
    }

    /**
     * SSH reverse tunnel: Engine-managed outbound SSH session that publishes this Engine's
     * peer port on a server the user owns. The other side dials the server's host:port as a
     * `Direct` route. (RFC-0108 Phase 3)
     *
     * The dialer never needs to know it's SSH; it just sees "use host:port" (a Direct route).
     * The tunnel manager on the peer side is responsible for keeping it up.
     */
    data class SshReverse(
        val tunnelId: String,  // Identifies which managed tunnel to use
        val serverHost: String,
        val serverPort: Int,
    ) : Route {
        init {
            require(tunnelId.isNotBlank() && serverHost.isNotBlank() && serverPort in 1..65535) {
                "invalid SSH reverse route"
            }
        }
    }

    companion object {
        fun direct(host: String, port: Int) = Direct(host, port)
    }
}

/**
 * An ordered list of route hints for a peer. The dialer tries them in order.
 * Always starts with `Lan` (mDNS discovery), then optional hints.
 *
 * Example: [Lan, Direct("peer-tailscale", 8766), SshReverse("home", "home.example.com", 47100)]
 * Meaning: try LAN first, then Tailscale address, then SSH tunnel to home.
 */
@JvmInline
value class RouteHints(val hints: List<Route> = listOf(Route.Lan)) {
    init {
        require(hints.isNotEmpty() && hints[0] == Route.Lan) { "first hint must be Lan" }
    }

    companion object {
        /** Just LAN discovery. */
        fun lanOnly() = RouteHints(listOf(Route.Lan))

        /** LAN, then direct address. */
        fun lanThenDirect(host: String, port: Int) =
            RouteHints(listOf(Route.Lan, Route.Direct(host, port)))

        /** LAN, then direct, then SSH tunnel. */
        fun withTunnel(direct: Route.Direct, tunnel: Route.SshReverse) =
            RouteHints(listOf(Route.Lan, direct, tunnel))
    }
}

/**
 * Round-trip time measured for each route. Used by the planner to decide whether to
 * offer layer split over a route (only if RTT is low enough for LAN; high WAN RTT means
 * the overhead is too large).
 *
 * `rttMillis = -1` indicates that the route failed to connect (unmeasured).
 */
data class RouteLatency(
    val route: Route,
    val rttMillis: Long,
    val measuredAt: Long = System.currentTimeMillis(),
) {
    init {
        require(rttMillis == -1L || rttMillis >= 0) { "RTT must be -1 (failure) or non-negative" }
    }
}

/** Result of attempting to dial a route. */
sealed interface DialResult {
    data class Success(val route: Route, val rttMillis: Long) : DialResult
    data class Failure(val route: Route, val reason: String) : DialResult
}
