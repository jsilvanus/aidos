package dev.aidos.wire

import dev.aidos.routes.Route
import dev.aidos.routes.RouteHints

/**
 * RFC-0108 Phase 1: Encoding/decoding route hints in AIDIN wire protocol.
 *
 * PairingInvite carries `addresses: List<String>` which is the initial set of LAN addresses.
 * Phase 1 extends this with route hints encoded as a structured list:
 *
 * Format: "lan" (marker), then optionally:
 * - "direct://" entries: "direct://host:port"
 * - "ssh://" entries: "ssh://tunnelId:serverHost:serverPort"
 *
 * Example addresses list for route hints:
 * ["192.168.1.100", "lan", "direct://tailscale:8766", "ssh://home:home.example.com:47100"]
 *
 * This is a forward-compatible extension: old clients that don't understand route hints
 * will simply treat "direct://" and "ssh://" as literal hostnames and fail to dial,
 * then fall back to "lan". New clients parse route hints and try them in order.
 */
object RouteHintCodec {
    private const val LAN_MARKER = "lan"
    private const val DIRECT_PREFIX = "direct://"
    private const val SSH_PREFIX = "ssh://"

    /**
     * Encode route hints to an addresses list for wire transmission.
     *
     * Format: LAN addresses first (literal hostnames/IPs), then "lan" marker,
     * then structured route hints (direct://, ssh://).
     *
     * Example:
     * Input: RouteHints([Lan, Direct("peer-tailscale", 8766), SshReverse("home", "example.com", 47100)])
     * Output: ["192.168.1.100", "lan", "direct://peer-tailscale:8766", "ssh://home:example.com:47100"]
     */
    fun encode(lanAddresses: List<String>, hints: RouteHints): List<String> {
        val result = mutableListOf<String>()

        // LAN addresses come first
        result.addAll(lanAddresses)

        // Explicit "lan" marker separates LAN addresses from route hints
        result.add(LAN_MARKER)

        // Encode each route hint
        for (route in hints.hints) {
            when (route) {
                Route.Lan -> {
                    // Lan is implicit; already marked above
                }

                is Route.Direct -> {
                    result.add("$DIRECT_PREFIX${route.host}:${route.port}")
                }

                is Route.SshReverse -> {
                    result.add("$SSH_PREFIX${route.tunnelId}:${route.serverHost}:${route.serverPort}")
                }
            }
        }

        return result
    }

    /**
     * Decode route hints from an addresses list.
     *
     * Returns a pair of (lanAddresses, routeHints).
     * - lanAddresses: literal hostnames/IPs before the "lan" marker
     * - routeHints: RouteHints with Lan always first, then decoded direct/ssh routes
     *
     * Returns null if the list is malformed (invalid routes that can't be parsed).
     */
    fun decode(addressesList: List<String>): Pair<List<String>, RouteHints>? {
        // Find the "lan" marker
        val lanIndex = addressesList.indexOf(LAN_MARKER)
        val lanAddresses = if (lanIndex >= 0) addressesList.subList(0, lanIndex) else addressesList
        val routeStrings = if (lanIndex >= 0) addressesList.subList(lanIndex + 1, addressesList.size) else emptyList()

        // Decode route hints
        val routes = mutableListOf<Route>(Route.Lan)
        for (routeStr in routeStrings) {
            when {
                routeStr.startsWith(DIRECT_PREFIX) -> {
                    val rest = routeStr.removePrefix(DIRECT_PREFIX)
                    val (host, portStr) = rest.rsplit(':', 1) ?: return null
                    val port = portStr.toIntOrNull() ?: return null
                    try {
                        routes.add(Route.Direct(host, port))
                    } catch (e: IllegalArgumentException) {
                        return null
                    }
                }

                routeStr.startsWith(SSH_PREFIX) -> {
                    val rest = routeStr.removePrefix(SSH_PREFIX)
                    val parts = rest.split(':')
                    if (parts.size != 3) return null
                    val (tunnelId, serverHost, portStr) = parts
                    val port = portStr.toIntOrNull() ?: return null
                    try {
                        routes.add(Route.SshReverse(tunnelId, serverHost, port))
                    } catch (e: IllegalArgumentException) {
                        return null
                    }
                }

                else -> {
                    // Unknown route type; skip (forward compatibility)
                    continue
                }
            }
        }

        return lanAddresses to RouteHints(routes)
    }

    /**
     * Parse a direct route from "direct://host:port" format.
     * Returns null if malformed.
     */
    fun parseDirectRoute(encoded: String): Route.Direct? {
        if (!encoded.startsWith(DIRECT_PREFIX)) return null
        val rest = encoded.removePrefix(DIRECT_PREFIX)
        val (host, portStr) = rest.rsplit(':', 1) ?: return null
        val port = portStr.toIntOrNull() ?: return null
        return try {
            Route.Direct(host, port)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /**
     * Parse an SSH reverse route from "ssh://tunnelId:serverHost:serverPort" format.
     * Returns null if malformed.
     */
    fun parseSshRoute(encoded: String): Route.SshReverse? {
        if (!encoded.startsWith(SSH_PREFIX)) return null
        val rest = encoded.removePrefix(SSH_PREFIX)
        val parts = rest.split(':')
        if (parts.size != 3) return null
        val (tunnelId, serverHost, portStr) = parts
        val port = portStr.toIntOrNull() ?: return null
        return try {
            Route.SshReverse(tunnelId, serverHost, port)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    private fun String.rsplit(delimiter: Char, limit: Int): Pair<String, String>? {
        val index = lastIndexOf(delimiter)
        return if (index >= 0) substring(0, index) to substring(index + 1) else null
    }
}
