package dev.aidos.mcp.policy

/**
 * Validates HTTP transport registration (RFC-0031).
 *
 * HTTPS is enforced except for loopback addresses on DESKTOP and HEADLESS_SERVER.
 * Cross-host redirects are refused (not enforced here — the HTTP client must be configured).
 *
 * Moved from `dev.aidos.mcp.McpAdapter.validateHttpEndpoint` (RFC-0031, "Implementation
 * Layering", blocker 2) unchanged in behavior; only the profile type changed, from the kernel
 * module's `PlatformProfile` to this module's kernel-free [McpProfile].
 */
fun validateHttpEndpoint(
    url: String,
    profile: McpProfile,
): McpValidationResult {
    val uri = runCatching { java.net.URI(url.trim()) }.getOrNull()
    if (uri == null) return McpValidationResult.Rejected("Malformed endpoint URL.")
    if (uri.scheme.equals("http", ignoreCase = true)) {
        // The host is parsed, not prefix-matched: `http://localhost.evil.example` and
        // `http://localhost@evil.example` must not count as loopback (RFC-0031).
        val host = uri.host?.lowercase()
        val isLoopback = host == "localhost" || host == "127.0.0.1" || host == "[::1]"
        val allowPlain = isLoopback &&
                profile in setOf(McpProfile.DESKTOP, McpProfile.HEADLESS_SERVER)
        if (!allowPlain) {
            return McpValidationResult.Rejected(
                "Plain HTTP is refused for non-loopback endpoints on $profile. Use HTTPS."
            )
        }
    }
    return McpValidationResult.Ok
}

sealed interface McpValidationResult {
    data object Ok : McpValidationResult
    data class Rejected(val reason: String) : McpValidationResult
}
