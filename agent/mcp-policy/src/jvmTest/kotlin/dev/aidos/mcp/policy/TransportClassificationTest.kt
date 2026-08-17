package dev.aidos.mcp.policy

import dev.aidos.mcp.core.McpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Ported from `dev.aidos.mcp.McpAdapterTest`'s transport-classification cases (RFC-0031),
 * rewritten against [McpTransportPolicy.classify] and [McpOperationClassification] instead of
 * `McpToolAdapter.descriptorsFor` and `ToolDescriptor` — the latter stays with `mcp-broker`.
 * Covers both branches of `classify`.
 */
class TransportClassificationTest {

    private val stdio = McpTransport.Stdio(command = "/usr/bin/github-mcp")

    private val http = McpTransport.Http(
        endpointUrl = "https://api.search-mcp.example.com/mcp",
        authHeaderName = "Authorization",
    )

    @Test
    fun `stdio classifies as READ with PLATFORM tier and no MOBILE availability`() {
        val classification = McpTransportPolicy.classify(stdio)
        assertEquals(McpEffect.READ, classification.effect)
        assertNull(classification.egressDestination)
        assertEquals(McpPermission.SHELL_EXEC, classification.permission)
        assertEquals(McpTier.PLATFORM, classification.tier)
        assertFalse(classification.requiresNetwork)
        assertEquals(McpRecovery.CHECKABLE, classification.recovery)
        assertTrue(McpProfile.DESKTOP in classification.profiles)
        assertTrue(McpProfile.HEADLESS_SERVER in classification.profiles)
        assertFalse(
            McpProfile.MOBILE in classification.profiles,
            "stdio must not be available on MOBILE",
        )
    }

    @Test
    fun `HTTP classifies as EGRESS to the endpoint with NETWORKED tier on every profile`() {
        val classification = McpTransportPolicy.classify(http)
        assertEquals(McpEffect.EGRESS, classification.effect)
        assertEquals("https://api.search-mcp.example.com/mcp", classification.egressDestination)
        assertEquals(McpPermission.NETWORK_EGRESS, classification.permission)
        assertEquals(McpTier.NETWORKED, classification.tier)
        assertTrue(classification.requiresNetwork)
        assertEquals(McpRecovery.CHECKABLE, classification.recovery)
        assertEquals(
            setOf(McpProfile.MOBILE, McpProfile.DESKTOP, McpProfile.HEADLESS_SERVER),
            classification.profiles,
        )
    }
}
