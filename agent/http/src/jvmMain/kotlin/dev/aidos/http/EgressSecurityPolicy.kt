package dev.aidos.http

import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.RedirectPolicy
import java.net.Inet4Address
import java.net.Inet6Address
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
        val isIpLiteral = isIpLiteral(host)
        if (isIpLiteral) {
            val explicitlyAllowed = scope.allowedHosts.any { it.equals(host, ignoreCase = true) }
            if (!explicitlyAllowed) {
                return EgressValidationResult.Denied(
                    reason = "IP literal '$host' is rejected: hostnames required by RFC-0042 unless explicitly granted in scope.",
                    outcome = EgressOutcome.BLOCKED_PRE_DNS,
                    addressClass = classifyIpString(host)
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
        val resolvedAddresses = try {
            dnsResolver(host).toList()
        } catch (e: Exception) {
            return EgressValidationResult.Denied(
                reason = "DNS resolution failed for '$host': ${e.message}",
                outcome = EgressOutcome.FAILED
            )
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
        fun isIpLiteral(host: String): Boolean {
            // IPv4 regex check
            if (host.matches(Regex("""^\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}$"""))) {
                return true
            }
            // IPv6 check
            if (host.contains(':')) {
                return true
            }
            return false
        }

        fun classifyIpString(host: String): AddressClass {
            val addr = try {
                InetAddress.getByName(host)
            } catch (e: Exception) {
                return AddressClass.RESERVED
            }
            return classifyAddress(addr)
        }

        fun classifyAddress(addr: InetAddress): AddressClass {
            if (addr.isLoopbackAddress) return AddressClass.LOOPBACK
            if (addr.isLinkLocalAddress) return AddressClass.LINK_LOCAL
            if (addr.isSiteLocalAddress) return AddressClass.PRIVATE
            if (addr.isMulticastAddress || addr.isAnyLocalAddress) return AddressClass.RESERVED

            val bytes = addr.address
            if (addr is Inet4Address && bytes.size == 4) {
                val b0 = bytes[0].toInt() and 0xFF
                val b1 = bytes[1].toInt() and 0xFF

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
                // 0.0.0.0/8 or 255.255.255.255
                if (b0 == 0 || b0 >= 240) return AddressClass.RESERVED
            }

            if (addr is Inet6Address) {
                if (addr.isLoopbackAddress) return AddressClass.LOOPBACK
                if (addr.isLinkLocalAddress) return AddressClass.LINK_LOCAL
                // Unique Local Address (ULA) fc00::/7
                val b0 = bytes[0].toInt() and 0xFF
                if ((b0 and 0xFE) == 0xFC) return AddressClass.PRIVATE
            }

            return AddressClass.PUBLIC
        }

        fun matchesHostPattern(host: String, pattern: String): Boolean {
            val p = pattern.trim().lowercase()
            val h = host.trim().lowercase()
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
