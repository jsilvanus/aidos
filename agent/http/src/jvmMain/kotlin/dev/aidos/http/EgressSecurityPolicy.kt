package dev.aidos.http

import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.RedirectPolicy
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest

/**
 * Address class classification for egress audit and security checks (RFC-0042).
 */
enum class AddressClass {
    PUBLIC,
    PRIVATE,
    LOOPBACK,
    LINK_LOCAL,
    RESERVED
}

/**
 * Egress outcome classification (RFC-0042).
 */
enum class EgressOutcome {
    SUCCESS,
    BLOCKED_PRE_DNS,
    BLOCKED_POST_DNS,
    BLOCKED_REDIRECT,
    BLOCKED_RESPONSE_SIZE,
    TIMEOUT,
    INDETERMINATE,
    FAILED
}

/**
 * Result of destination validation.
 */
sealed interface EgressValidationResult {
    data class Allowed(
        val uri: URI,
        val resolvedAddresses: List<InetAddress>,
        val addressClass: AddressClass
    ) : EgressValidationResult

    data class Denied(
        val reason: String,
        val outcome: EgressOutcome,
        val addressClass: AddressClass? = null
    ) : EgressValidationResult
}

/**
 * Destination scoping and SSRF protection engine (RFC-0042).
 *
 * Evaluates network requests in four places:
 * 1. Before DNS: IP literals rejected by default; host must match allowedHosts; scheme & port checks.
 * 2. After DNS, before connect: resolved addresses checked against loopback, link-local (169.254.x.x),
 *    and RFC-1918 private ranges.
 * 3. Connected socket: rebinding verification.
 * 4. On redirect: new target is validated from step 1; cross-host redirects rejected by default.
 */
class EgressSecurityPolicy(
    private val dnsResolver: (String) -> Array<InetAddress> = { host -> InetAddress.getAllByName(host) }
) {
    /**
     * Default restrictive scope when none is explicitly granted:
     * - No private addresses (SSRF blocked)
     * - Same-host redirects only
     * - Only standard ports (80, 443)
     */
    val defaultScope = CapabilityScope.Network(
        allowedHosts = emptyList(),
        allowedPorts = listOf(80, 443),
        allowPrivateAddresses = false,
        followRedirects = RedirectPolicy.SAME_HOST_ONLY,
    )

    /**
     * Validates a target URL against destination scoping and RFC-0042 SSRF rules.
     */
    fun validateDestination(
        urlString: String,
        scope: CapabilityScope.Network = defaultScope,
        allowWildcardHostIfEmpty: Boolean = false
    ): EgressValidationResult {
        // 1. Pre-DNS checks
        val uri = try {
            URI.create(urlString)
        } catch (e: Exception) {
            return EgressValidationResult.Denied(
                reason = "Invalid URL syntax: ${e.message}",
                outcome = EgressOutcome.BLOCKED_PRE_DNS
            )
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") {
            return EgressValidationResult.Denied(
                reason = "Unsupported scheme '$scheme': only HTTP and HTTPS permitted by RFC-0042",
                outcome = EgressOutcome.BLOCKED_PRE_DNS
            )
        }

        val rawHost = uri.host ?: return EgressValidationResult.Denied(
            reason = "Missing host in URL: $urlString",
            outcome = EgressOutcome.BLOCKED_PRE_DNS
        )
        val host = rawHost.removePrefix("[").removeSuffix("]").lowercase()

        // RFC-0042: "IP literals are rejected outright unless the scope explicitly names one;
        // a model proposing http://169.254.169.254/ is not making a hostname request."
        val literalBytes = parseIpLiteralBytes(host)
        val isIpLiteral = literalBytes != null
        if (literalBytes != null) {
            val explicitlyAllowed = scope.allowedHosts.any {
                normalizeBracketedHost(it) == host
            }
            if (!explicitlyAllowed) {
                return EgressValidationResult.Denied(
                    reason = "IP literal '$host' is rejected: hostnames required by RFC-0042 unless explicitly granted in scope.",
                    outcome = EgressOutcome.BLOCKED_PRE_DNS,
                    addressClass = classifyIpBytes(literalBytes)
                )
            }
        }

        // Port check
        val port = if (uri.port != -1) uri.port else if (scheme == "https") 443 else 80
        val allowedPorts = scope.allowedPorts
        if (allowedPorts != null && port !in allowedPorts) {
            return EgressValidationResult.Denied(
                reason = "Port $port is not in allowedPorts: $allowedPorts",
                outcome = EgressOutcome.BLOCKED_PRE_DNS
            )
        }

        // Host pattern matching
        if (scope.allowedHosts.isNotEmpty()) {
            val matchesHost = scope.allowedHosts.any { pattern ->
                matchesHostPattern(host, pattern)
            }
            if (!matchesHost) {
                return EgressValidationResult.Denied(
                    reason = "Host '$host' does not match allowedHosts: ${scope.allowedHosts}",
                    outcome = EgressOutcome.BLOCKED_PRE_DNS
                )
            }
        } else if (!allowWildcardHostIfEmpty && !isIpLiteral) {
            return EgressValidationResult.Denied(
                reason = "No allowedHosts configured in NetworkScope",
                outcome = EgressOutcome.BLOCKED_PRE_DNS
            )
        }

        // 2. Post-DNS checks (Resolve addresses & SSRF blocklist)
        val resolvedAddresses = if (literalBytes != null) {
            listOf(InetAddress.getByAddress(host, literalBytes))
        } else {
            try {
                dnsResolver(host).toList()
            } catch (e: Exception) {
                return EgressValidationResult.Denied(
                    reason = "DNS resolution failed for '$host': ${e.message}",
                    outcome = EgressOutcome.FAILED
                )
            }
        }

        if (resolvedAddresses.isEmpty()) {
            return EgressValidationResult.Denied(
                reason = "No IP addresses resolved for host '$host'",
                outcome = EgressOutcome.FAILED
            )
        }

        var overallClass = AddressClass.PUBLIC
        for (addr in resolvedAddresses) {
            val addrClass = classifyAddress(addr)
            if (addrClass != AddressClass.PUBLIC) {
                overallClass = addrClass
            }

            // Loopback check
            if (addrClass == AddressClass.LOOPBACK && !scope.allowPrivateAddresses) {
                return EgressValidationResult.Denied(
                    reason = "SSRF blocked: host '$host' resolves to loopback address ${addr.hostAddress}",
                    outcome = EgressOutcome.BLOCKED_POST_DNS,
                    addressClass = AddressClass.LOOPBACK
                )
            }

            // Link-local check (catches AWS/GCP/Azure 169.254.169.254 instance metadata)
            if (addrClass == AddressClass.LINK_LOCAL) {
                return EgressValidationResult.Denied(
                    reason = "SSRF blocked: host '$host' resolves to link-local address ${addr.hostAddress} (RFC-0042 cloud metadata defense)",
                    outcome = EgressOutcome.BLOCKED_POST_DNS,
                    addressClass = AddressClass.LINK_LOCAL
                )
            }

            // Private network check (RFC-1918)
            if (addrClass == AddressClass.PRIVATE && !scope.allowPrivateAddresses) {
                return EgressValidationResult.Denied(
                    reason = "SSRF blocked: host '$host' resolves to private network address ${addr.hostAddress} (RFC-1918)",
                    outcome = EgressOutcome.BLOCKED_POST_DNS,
                    addressClass = AddressClass.PRIVATE
                )
            }

            // Reserved / Multicast
            if (addrClass == AddressClass.RESERVED) {
                return EgressValidationResult.Denied(
                    reason = "SSRF blocked: host '$host' resolves to reserved/multicast address ${addr.hostAddress}",
                    outcome = EgressOutcome.BLOCKED_POST_DNS,
                    addressClass = AddressClass.RESERVED
                )
            }
        }

        return EgressValidationResult.Allowed(
            uri = uri,
            resolvedAddresses = resolvedAddresses,
            addressClass = overallClass
        )
    }

    /**
     * Validates a redirect target URL relative to the original request (RFC-0042 Step 4).
     */
    fun validateRedirect(
        fromUrl: String,
        toLocation: String,
        scope: CapabilityScope.Network = defaultScope
    ): EgressValidationResult {
        val fromUri = URI.create(fromUrl)
        val targetUri = fromUri.resolve(toLocation)

        when (scope.followRedirects) {
            RedirectPolicy.NONE -> {
                return EgressValidationResult.Denied(
                    reason = "Redirects are disallowed by RedirectPolicy.NONE",
                    outcome = EgressOutcome.BLOCKED_REDIRECT
                )
            }
            RedirectPolicy.SAME_HOST_ONLY -> {
                val fromHost = fromUri.host ?: ""
                val targetHost = targetUri.host ?: ""
                if (!fromHost.equals(targetHost, ignoreCase = true)) {
                    return EgressValidationResult.Denied(
                        reason = "Cross-host redirect blocked by SAME_HOST_ONLY policy ($fromHost -> $targetHost)",
                        outcome = EgressOutcome.BLOCKED_REDIRECT
                    )
                }
            }
            RedirectPolicy.ANY_ALLOWED_HOST -> {
                // Must pass standard destination validation
            }
        }

        return validateDestination(
            urlString = targetUri.toString(),
            scope = scope,
            allowWildcardHostIfEmpty = scope.allowedHosts.isEmpty()
        )
    }

    companion object {
        fun isIpLiteral(host: String): Boolean = parseIpLiteralBytes(host) != null

        fun classifyIpString(host: String): AddressClass {
            val bytes = parseIpLiteralBytes(host) ?: return AddressClass.RESERVED
            return classifyIpBytes(bytes)
        }

        fun classifyAddress(addr: InetAddress): AddressClass {
            if (addr.isLoopbackAddress) return AddressClass.LOOPBACK
            if (addr.isLinkLocalAddress) return AddressClass.LINK_LOCAL
            if (addr.isSiteLocalAddress) return AddressClass.PRIVATE
            if (addr.isMulticastAddress || addr.isAnyLocalAddress) return AddressClass.RESERVED
            return classifyIpBytes(addr.address)
        }

        private fun classifyIpBytes(bytes: ByteArray): AddressClass {
            if (bytes.size == 4) {
                return classifyIpv4Bytes(
                    bytes[0].toInt() and 0xFF,
                    bytes[1].toInt() and 0xFF,
                )
            }

            if (bytes.size == 16) {
                // ::/128 (unspecified) and ::1/128 (loopback)
                val firstTwelveZero = (0 until 12).all { bytes[it] == 0.toByte() }
                if (firstTwelveZero) {
                    val v4b0 = bytes[12].toInt() and 0xFF
                    val v4b1 = bytes[13].toInt() and 0xFF
                    val v4b2 = bytes[14].toInt() and 0xFF
                    val v4b3 = bytes[15].toInt() and 0xFF
                    if (v4b0 == 0 && v4b1 == 0 && v4b2 == 0) {
                        return if (v4b3 == 1) AddressClass.LOOPBACK else AddressClass.RESERVED
                    }
                    // IPv4-compatible IPv6 (::/96)
                    return classifyIpv4Bytes(v4b0, v4b1)
                }

                // IPv4-mapped IPv6 (::ffff:0:0/96)
                val isIpv4Mapped = (0 until 10).all { bytes[it] == 0.toByte() } &&
                    bytes[10] == 0xFF.toByte() &&
                    bytes[11] == 0xFF.toByte()
                if (isIpv4Mapped) {
                    return classifyIpv4Bytes(
                        bytes[12].toInt() and 0xFF,
                        bytes[13].toInt() and 0xFF,
                    )
                }

                // NAT64 well-known prefix (64:ff9b::/96)
                val isNat64WellKnown = bytes[0] == 0x00.toByte() &&
                    bytes[1] == 0x64.toByte() &&
                    bytes[2] == 0xFF.toByte() &&
                    bytes[3] == 0x9B.toByte() &&
                    (4 until 12).all { bytes[it] == 0.toByte() }
                if (isNat64WellKnown) {
                    return classifyIpv4Bytes(
                        bytes[12].toInt() and 0xFF,
                        bytes[13].toInt() and 0xFF,
                    )
                }

                // NAT64 local-use prefix (64:ff9b:1::/48)
                val isNat64Local = bytes[0] == 0x00.toByte() &&
                    bytes[1] == 0x64.toByte() &&
                    bytes[2] == 0xFF.toByte() &&
                    bytes[3] == 0x9B.toByte() &&
                    bytes[4] == 0x00.toByte() &&
                    bytes[5] == 0x01.toByte()
                if (isNat64Local) return AddressClass.PRIVATE

                // 6to4 prefix (2002::/16) embedding IPv4 in bytes[2..5]
                if (bytes[0] == 0x20.toByte() && bytes[1] == 0x02.toByte()) {
                    val embedded = classifyIpv4Bytes(
                        bytes[2].toInt() and 0xFF,
                        bytes[3].toInt() and 0xFF,
                    )
                    if (embedded != AddressClass.PUBLIC) return embedded
                }

                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF
                // Link-local fe80::/10
                if (b0 == 0xFE && (b1 and 0xC0) == 0x80) return AddressClass.LINK_LOCAL
                // Deprecated site-local fec0::/10
                if (b0 == 0xFE && (b1 and 0xC0) == 0xC0) return AddressClass.PRIVATE
                // Unique Local Address (ULA) fc00::/7
                if ((b0 and 0xFE) == 0xFC) return AddressClass.PRIVATE
                // Multicast ff00::/8
                if (b0 == 0xFF) return AddressClass.RESERVED
                // Discard-only 0100::/64
                if (b0 == 0x01 && (1 until 8).all { bytes[it] == 0.toByte() }) return AddressClass.RESERVED
            }

            return AddressClass.PUBLIC
        }

        private fun classifyIpv4Bytes(b0: Int, b1: Int): AddressClass {
            // 127.0.0.0/8
            if (b0 == 127) return AddressClass.LOOPBACK
            // 169.254.0.0/16
            if (b0 == 169 && b1 == 254) return AddressClass.LINK_LOCAL
            // 10.0.0.0/8
            if (b0 == 10) return AddressClass.PRIVATE
            // 172.16.0.0/12
            if (b0 == 172 && (b1 in 16..31)) return AddressClass.PRIVATE
            // 192.168.0.0/16
            if (b0 == 192 && b1 == 168) return AddressClass.PRIVATE
            // Carrier-grade NAT: 100.64.0.0/10
            if (b0 == 100 && (b1 in 64..127)) return AddressClass.RESERVED
            // 0.0.0.0/8, 224.0.0.0/4 multicast, or 240.0.0.0/4 reserved
            if (b0 == 0 || b0 >= 224) return AddressClass.RESERVED
            return AddressClass.PUBLIC
        }

        private fun parseIpLiteralBytes(rawHost: String): ByteArray? {
            val trimmed = rawHost.trim()
            if (trimmed.isEmpty()) return null
            if (trimmed.startsWith('[') || trimmed.endsWith(']')) {
                if (!(trimmed.startsWith('[') && trimmed.endsWith(']'))) return null
                val inner = trimmed.substring(1, trimmed.length - 1).trim()
                return parseIpv6Bytes(inner)
            }
            if (trimmed.contains(':')) {
                return parseIpv6Bytes(trimmed)
            }
            val candidate = if (trimmed.endsWith('.') && trimmed.count { it == '.' } >= 1) {
                trimmed.dropLast(1)
            } else {
                trimmed
            }
            if (candidate.isEmpty()) return null
            return parseIpv4Aton(candidate)
        }

        /**
         * Parses IPv4 literals using BSD/POSIX inet_aton syntax (standard 4-part dotted decimal,
         * shorthand 1/2/3-part forms, octal 0-prefixed segments, and hexadecimal 0x-prefixed segments)
         * purely locally without triggering DNS resolution.
         */
        private fun parseIpv4Aton(host: String): ByteArray? {
            val parts = host.split('.')
            if (parts.size !in 1..4 || parts.any { it.isEmpty() }) return null
            val values = LongArray(parts.size)
            for (i in parts.indices) {
                values[i] = parseIpv4Part(parts[i]) ?: return null
            }
            return when (values.size) {
                1 -> {
                    val v = values[0]
                    byteArrayOf(
                        ((v ushr 24) and 0xFFL).toByte(),
                        ((v ushr 16) and 0xFFL).toByte(),
                        ((v ushr 8) and 0xFFL).toByte(),
                        (v and 0xFFL).toByte(),
                    )
                }
                2 -> {
                    if (values[0] > 0xFFL || values[1] > 0xFFFFFFL) return null
                    val b0 = values[0]
                    val rest = values[1]
                    byteArrayOf(
                        b0.toByte(),
                        ((rest ushr 16) and 0xFFL).toByte(),
                        ((rest ushr 8) and 0xFFL).toByte(),
                        (rest and 0xFFL).toByte(),
                    )
                }
                3 -> {
                    if (values[0] > 0xFFL || values[1] > 0xFFL || values[2] > 0xFFFFL) return null
                    byteArrayOf(
                        values[0].toByte(),
                        values[1].toByte(),
                        ((values[2] ushr 8) and 0xFFL).toByte(),
                        (values[2] and 0xFFL).toByte(),
                    )
                }
                else -> {
                    if (values.any { it > 0xFFL }) return null
                    byteArrayOf(
                        values[0].toByte(),
                        values[1].toByte(),
                        values[2].toByte(),
                        values[3].toByte(),
                    )
                }
            }
        }

        private fun parseIpv4Part(part: String): Long? {
            if (part.isEmpty()) return null
            val (radix, digits) = when {
                part.startsWith("0x", ignoreCase = true) -> {
                    val hex = part.substring(2)
                    if (hex.isEmpty()) return null
                    16 to hex
                }
                part.length > 1 && part[0] == '0' -> 8 to part.substring(1)
                else -> 10 to part
            }
            var value = 0L
            for (ch in digits) {
                val digit = when (radix) {
                    16 -> when (ch) {
                        in '0'..'9' -> ch - '0'
                        in 'a'..'f' -> ch - 'a' + 10
                        in 'A'..'F' -> ch - 'A' + 10
                        else -> return null
                    }
                    8 -> if (ch in '0'..'7') ch - '0' else return null
                    else -> if (ch in '0'..'9') ch - '0' else return null
                }
                value = value * radix + digit
                if (value > 0xFFFFFFFFL) return null
            }
            return value
        }

        private fun parseIpv6Bytes(raw: String): ByteArray? {
            if (raw.isEmpty()) return null
            val percentIdx = raw.indexOf('%')
            val addrPart = if (percentIdx >= 0) {
                if (percentIdx == raw.lastIndex) return null
                raw.substring(0, percentIdx)
            } else {
                raw
            }
            if (!addrPart.contains(':')) return null

            val hexPart = if (addrPart.contains('.')) {
                val lastColon = addrPart.lastIndexOf(':')
                if (lastColon < 0 || lastColon == addrPart.lastIndex) return null
                val v4Segment = addrPart.substring(lastColon + 1)
                val v4Bytes = parseIpv4Aton(v4Segment) ?: return null
                val h1 = ((v4Bytes[0].toInt() and 0xFF) shl 8) or (v4Bytes[1].toInt() and 0xFF)
                val h2 = ((v4Bytes[2].toInt() and 0xFF) shl 8) or (v4Bytes[3].toInt() and 0xFF)
                addrPart.substring(0, lastColon + 1) + h1.toString(16) + ":" + h2.toString(16)
            } else {
                addrPart
            }

            val firstDoubleColon = hexPart.indexOf("::")
            val words = IntArray(8)
            if (firstDoubleColon >= 0) {
                if (hexPart.indexOf("::", firstDoubleColon + 2) >= 0) return null
                val leftStr = hexPart.substring(0, firstDoubleColon)
                val rightStr = hexPart.substring(firstDoubleColon + 2)
                val leftParts = if (leftStr.isEmpty()) emptyList() else leftStr.split(':')
                val rightParts = if (rightStr.isEmpty()) emptyList() else rightStr.split(':')
                if (leftParts.size + rightParts.size >= 8) return null

                for (i in leftParts.indices) {
                    words[i] = parseIpv6Group(leftParts[i]) ?: return null
                }
                val rightOffset = 8 - rightParts.size
                for (i in rightParts.indices) {
                    words[rightOffset + i] = parseIpv6Group(rightParts[i]) ?: return null
                }
            } else {
                val parts = hexPart.split(':')
                if (parts.size != 8) return null
                for (i in parts.indices) {
                    words[i] = parseIpv6Group(parts[i]) ?: return null
                }
            }

            val bytes = ByteArray(16)
            for (i in 0 until 8) {
                bytes[i * 2] = ((words[i] ushr 8) and 0xFF).toByte()
                bytes[i * 2 + 1] = (words[i] and 0xFF).toByte()
            }
            return bytes
        }

        private fun parseIpv6Group(group: String): Int? {
            if (group.isEmpty() || group.length > 4) return null
            var value = 0
            for (ch in group) {
                val d = when (ch) {
                    in '0'..'9' -> ch - '0'
                    in 'a'..'f' -> ch - 'a' + 10
                    in 'A'..'F' -> ch - 'A' + 10
                    else -> return null
                }
                value = (value shl 4) or d
            }
            return value
        }

        private fun normalizeBracketedHost(value: String): String {
            val trimmed = value.trim().lowercase()
            return if (trimmed.startsWith('[') && trimmed.endsWith(']')) {
                trimmed.substring(1, trimmed.length - 1).trim()
            } else {
                trimmed
            }
        }

        fun matchesHostPattern(host: String, pattern: String): Boolean {
            val p = normalizeBracketedHost(pattern)
            val h = normalizeBracketedHost(host)
            if (p == "*" || p == h) return true
            if (p.startsWith("*.")) {
                val domain = p.removePrefix("*.")
                return h == domain || h.endsWith(".$domain")
            }
            return false
        }

        fun sha256Hex(bytes: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256")
            val hash = digest.digest(bytes)
            return hash.joinToString("") { "%02x".format(it) }
        }
    }
}
