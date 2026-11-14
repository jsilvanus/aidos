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
 * Bounded parsing guarantees:
 * - Rejects frames exceeding [MAX_HINTS] entries, [MAX_ENTRY_LENGTH] per entry, or
 *   [MAX_FRAME_BYTES] total wire bytes before allocating route structures.
 * - Validates hosts, tunnel IDs, and ports (`1..65535`) explicitly prior to constructing [Route].
 */
object RouteHintCodec {
    const val MAX_HINTS = 32
    const val MAX_FRAME_BYTES = 8 * 1024
    const val MAX_ENTRY_LENGTH = 512
    const val MAX_HOST_LENGTH = Route.MAX_HOST_LENGTH
    const val MAX_TUNNEL_ID_LENGTH = Route.MAX_TUNNEL_ID_LENGTH

    private const val LAN_MARKER = "lan"
    private const val DIRECT_SCHEME = "direct:"
    private const val DIRECT_PREFIX = "direct://"
    private const val SSH_SCHEME = "ssh:"
    private const val SSH_PREFIX = "ssh://"

    /**
     * Encode route hints to an addresses list for wire transmission.
     * Enforces host validity and frame size bounds.
     */
    fun encode(lanAddresses: List<String>, hints: RouteHints): List<String> {
        require(lanAddresses.size + hints.hints.size <= MAX_HINTS) {
            "encoded route hints exceed MAX_HINTS ($MAX_HINTS)"
        }

        val result = ArrayList<String>(lanAddresses.size + hints.hints.size)
        for (addr in lanAddresses) {
            require(Route.isValidHost(addr)) { "invalid LAN address" }
            result.add(addr)
        }

        result.add(LAN_MARKER)

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

        require(result.size <= MAX_HINTS) { "encoded frame exceeds MAX_HINTS ($MAX_HINTS)" }
        var totalBytes = 0
        for (entry in result) {
            require(entry.length <= MAX_ENTRY_LENGTH) { "encoded entry exceeds MAX_ENTRY_LENGTH ($MAX_ENTRY_LENGTH)" }
            totalBytes += utf8Size(entry) + 1
            require(totalBytes <= MAX_FRAME_BYTES) { "encoded frame exceeds MAX_FRAME_BYTES ($MAX_FRAME_BYTES)" }
        }

        return result
    }

    /**
     * Decode route hints from an addresses list.
     * Returns null if the list exceeds bounds or is malformed.
     */
    fun decode(addressesList: List<String>): Pair<List<String>, RouteHints>? =
        decodeStrict(addressesList).getOrNull()

    /**
     * Decode route hints with explicit failure reasons on oversized or malformed input.
     */
    fun decodeStrict(addressesList: List<String>): Result<Pair<List<String>, RouteHints>> {
        if (addressesList.size > MAX_HINTS) {
            return Result.failure(
                IllegalArgumentException("route hint count ${addressesList.size} exceeds MAX_HINTS ($MAX_HINTS)"),
            )
        }

        var totalBytes = 0
        for ((index, entry) in addressesList.withIndex()) {
            if (entry.length > MAX_ENTRY_LENGTH) {
                return Result.failure(
                    IllegalArgumentException("entry $index length ${entry.length} exceeds MAX_ENTRY_LENGTH ($MAX_ENTRY_LENGTH)"),
                )
            }
            if (hasDisallowedCharacters(entry)) {
                return Result.failure(
                    IllegalArgumentException("entry $index is blank or contains whitespace/control characters"),
                )
            }
            totalBytes += utf8Size(entry) + 1
            if (totalBytes > MAX_FRAME_BYTES) {
                return Result.failure(
                    IllegalArgumentException("frame byte size exceeds MAX_FRAME_BYTES ($MAX_FRAME_BYTES)"),
                )
            }
        }

        val lanIndex = addressesList.indexOf(LAN_MARKER)
        val lanAddresses = if (lanIndex >= 0) addressesList.subList(0, lanIndex).toList() else addressesList.toList()
        val routeStrings = if (lanIndex >= 0) addressesList.subList(lanIndex + 1, addressesList.size) else emptyList()

        for (lanAddress in lanAddresses) {
            if (!Route.isValidHost(lanAddress)) {
                return Result.failure(IllegalArgumentException("invalid LAN address"))
            }
        }

        val routes = mutableListOf<Route>(Route.Lan)
        for (routeStr in routeStrings) {
            when {
                routeStr == LAN_MARKER -> {
                    return Result.failure(IllegalArgumentException("duplicate lan marker"))
                }

                routeStr.startsWith(DIRECT_PREFIX) -> {
                    val direct = parseDirectRouteStrict(routeStr).getOrElse { return Result.failure(it) }
                    routes.add(direct)
                }

                routeStr.startsWith(SSH_PREFIX) -> {
                    val ssh = parseSshRouteStrict(routeStr).getOrElse { return Result.failure(it) }
                    routes.add(ssh)
                }

                routeStr.startsWith(DIRECT_SCHEME) || routeStr.startsWith(SSH_SCHEME) -> {
                    return Result.failure(IllegalArgumentException("malformed route hint scheme"))
                }

                isValidUnknownRouteUri(routeStr) -> {
                    // Unknown route type; skip (forward compatibility)
                    continue
                }

                else -> {
                    return Result.failure(IllegalArgumentException("unrecognized malformed route entry"))
                }
            }
        }

        return Result.success(lanAddresses to RouteHints(routes))
    }

    /**
     * Parse a direct route from "direct://host:port" format.
     * Returns null if malformed or out of bounds.
     */
    fun parseDirectRoute(encoded: String): Route.Direct? =
        parseDirectRouteStrict(encoded).getOrNull()

    /**
     * Parse a direct route with explicit validation failure reasons.
     */
    fun parseDirectRouteStrict(encoded: String): Result<Route.Direct> {
        if (encoded.length > MAX_ENTRY_LENGTH) {
            return Result.failure(IllegalArgumentException("direct route exceeds MAX_ENTRY_LENGTH ($MAX_ENTRY_LENGTH)"))
        }
        if (hasDisallowedCharacters(encoded)) {
            return Result.failure(IllegalArgumentException("direct route contains whitespace or control characters"))
        }
        if (!encoded.startsWith(DIRECT_PREFIX)) {
            return Result.failure(IllegalArgumentException("missing '$DIRECT_PREFIX' prefix"))
        }
        val rest = encoded.removePrefix(DIRECT_PREFIX)
        val (host, portStr) = rest.rsplit(':')
            ?: return Result.failure(IllegalArgumentException("missing host:port separator"))
        if (!Route.isValidHost(host)) {
            return Result.failure(IllegalArgumentException("invalid host in direct route"))
        }
        val port = parsePort(portStr)
            ?: return Result.failure(IllegalArgumentException("port out of range (1..65535): $portStr"))
        return Result.success(Route.Direct(host, port))
    }

    /**
     * Parse an SSH reverse route from "ssh://tunnelId:serverHost:serverPort" format.
     * Returns null if malformed or out of bounds.
     */
    fun parseSshRoute(encoded: String): Route.SshReverse? =
        parseSshRouteStrict(encoded).getOrNull()

    /**
     * Parse an SSH reverse route with explicit validation failure reasons.
     */
    fun parseSshRouteStrict(encoded: String): Result<Route.SshReverse> {
        if (encoded.length > MAX_ENTRY_LENGTH) {
            return Result.failure(IllegalArgumentException("ssh route exceeds MAX_ENTRY_LENGTH ($MAX_ENTRY_LENGTH)"))
        }
        if (hasDisallowedCharacters(encoded)) {
            return Result.failure(IllegalArgumentException("ssh route contains whitespace or control characters"))
        }
        if (!encoded.startsWith(SSH_PREFIX)) {
            return Result.failure(IllegalArgumentException("missing '$SSH_PREFIX' prefix"))
        }
        val rest = encoded.removePrefix(SSH_PREFIX)
        val parts = rest.split(':')
        if (parts.size != 3) {
            return Result.failure(IllegalArgumentException("ssh route must have 3 colon-separated fields"))
        }
        val (tunnelId, serverHost, portStr) = parts
        if (!Route.isValidTunnelId(tunnelId)) {
            return Result.failure(IllegalArgumentException("invalid tunnelId in ssh route"))
        }
        if (!Route.isValidHost(serverHost)) {
            return Result.failure(IllegalArgumentException("invalid serverHost in ssh route"))
        }
        val port = parsePort(portStr)
            ?: return Result.failure(IllegalArgumentException("serverPort out of range (1..65535): $portStr"))
        return Result.success(Route.SshReverse(tunnelId, serverHost, port))
    }

    private fun parsePort(portStr: String): Int? {
        if (portStr.length !in 1..5) return null
        if (portStr[0] !in '1'..'9') return null
        if (!portStr.all { it in '0'..'9' }) return null
        return portStr.toIntOrNull()?.takeIf { it in 1..65535 }
    }

    private fun hasDisallowedCharacters(s: String): Boolean =
        s.isBlank() || s.any { it.isWhitespace() || it.code < 0x20 || it.code == 0x7F || it.code in 0x80..0x9F }

    private fun isValidUnknownRouteUri(routeStr: String): Boolean {
        val schemeSep = routeStr.indexOf("://")
        if (schemeSep <= 0 || schemeSep + 3 >= routeStr.length) return false
        val scheme = routeStr.substring(0, schemeSep)
        return scheme[0] in 'a'..'z' &&
            scheme.all { it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '.' || it == '-' }
    }

    private fun utf8Size(s: String): Int {
        var bytes = 0
        var i = 0
        while (i < s.length) {
            val c = s[i].code
            bytes += when {
                c < 0x80 -> 1
                c < 0x800 -> 2
                c in 0xD800..0xDBFF && i + 1 < s.length && s[i + 1].code in 0xDC00..0xDFFF -> {
                    i++
                    4
                }
                else -> 3
            }
            i++
        }
        return bytes
    }

    private fun String.rsplit(delimiter: Char): Pair<String, String>? {
        val index = lastIndexOf(delimiter)
        return if (index >= 0) substring(0, index) to substring(index + 1) else null
    }
}
