package dev.aidos.mcp.policy

import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Ported from `dev.aidos.mcp.McpAdapterTest`'s "HTTP validation" cases (RFC-0031), rewritten
 * against [McpProfile] instead of the kernel module's `PlatformProfile`. Every branch of
 * [validateHttpEndpoint] is covered: HTTPS on every profile, plain HTTP on a non-loopback host on
 * every profile, and plain HTTP on each of the three recognized loopback forms, both allowed
 * (DESKTOP, HEADLESS_SERVER) and refused (MOBILE).
 */
class EndpointValidationTest {

    @Test
    fun `HTTPS endpoint is accepted on all profiles`() {
        for (profile in McpProfile.entries) {
            val result = validateHttpEndpoint("https://api.example.com/mcp", profile)
            assertIs<McpValidationResult.Ok>(result, "expected HTTPS to be accepted on $profile")
        }
    }

    @Test
    fun `plain HTTP refused on MOBILE`() {
        val result = validateHttpEndpoint("http://api.example.com/mcp", McpProfile.MOBILE)
        assertIs<McpValidationResult.Rejected>(result)
        assertTrue(result.reason.contains("HTTPS") || result.reason.contains("refused"))
    }

    @Test
    fun `plain HTTP non-loopback refused on every profile`() {
        for (profile in McpProfile.entries) {
            val result = validateHttpEndpoint("http://some-external-server.com/mcp", profile)
            assertIs<McpValidationResult.Rejected>(
                result,
                "expected non-loopback plain HTTP to be refused on $profile",
            )
        }
    }

    @Test
    fun `plain HTTP loopback accepted on DESKTOP`() {
        val result = validateHttpEndpoint("http://localhost:3000/mcp", McpProfile.DESKTOP)
        assertIs<McpValidationResult.Ok>(result)
    }

    @Test
    fun `plain HTTP loopback accepted on HEADLESS_SERVER`() {
        val result = validateHttpEndpoint("http://127.0.0.1:3000/mcp", McpProfile.HEADLESS_SERVER)
        assertIs<McpValidationResult.Ok>(result)
    }

    @Test
    fun `plain HTTP IPv6 loopback accepted on DESKTOP`() {
        val result = validateHttpEndpoint("http://[::1]:3000/mcp", McpProfile.DESKTOP)
        assertIs<McpValidationResult.Ok>(result)
    }

    @Test
    fun `plain HTTP loopback refused on MOBILE`() {
        val result = validateHttpEndpoint("http://localhost:3000/mcp", McpProfile.MOBILE)
        assertIs<McpValidationResult.Rejected>(result)
    }

    @Test
    fun `plain HTTP IPv6 loopback refused on MOBILE`() {
        val result = validateHttpEndpoint("http://[::1]:3000/mcp", McpProfile.MOBILE)
        assertIs<McpValidationResult.Rejected>(result)
    }

    @Test
    fun `plain HTTP external refused on HEADLESS_SERVER`() {
        val result = validateHttpEndpoint(
            "http://some-external-server.com/mcp",
            McpProfile.HEADLESS_SERVER,
        )
        assertIs<McpValidationResult.Rejected>(result)
    }
}
