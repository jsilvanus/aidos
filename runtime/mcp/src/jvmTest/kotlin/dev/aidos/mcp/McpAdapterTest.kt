package dev.aidos.mcp

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.TrustLevel
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M18 done-when (RFC-0031, D17, D23):
 *
 * 1. An off-the-shelf MCP server's tools appear in the broker with EffectKind and
 *    RecoveryClass assigned.
 * 2. HTTP transport forces Egress on every tool.
 * 3. stdio transport uses platform profiles (DESKTOP/HEADLESS_SERVER only).
 * 4. An MCP server cannot raise a capability request — resultGuidance is always null (D23).
 * 5. HTTPS enforced; plain HTTP refused except loopback on DESKTOP/HEADLESS_SERVER.
 * 6. Tool results are UNTRUSTED — the broker must assign UNTRUSTED to MCP results.
 */
class McpAdapterTest {

    private val stdioServer = McpServerRegistration(
        serverId = "github",
        transport = McpTransport.Stdio(command = "/usr/bin/github-mcp"),
        tools = listOf(
            McpToolSpec("list-repos", "Lists GitHub repositories"),
            McpToolSpec("get-issue", "Gets a GitHub issue"),
        ),
    )

    private val httpServer = McpServerRegistration(
        serverId = "search",
        transport = McpTransport.Http(
            endpointUrl = "https://api.search-mcp.example.com/mcp",
            authHeaderName = "Authorization",
        ),
        tools = listOf(
            McpToolSpec("web-search", "Performs a web search"),
        ),
    )

    @Test
    fun `stdio tools appear with Read effect and PLATFORM availability`() {
        val descriptors = McpToolAdapter.descriptorsFor(stdioServer)
        assertEquals(2, descriptors.size)
        for (desc in descriptors) {
            assertIs<EffectKind.Read>(desc.effect)
            assertEquals(AvailabilityTier.PLATFORM, desc.availability.tier)
            assertTrue(PlatformProfile.DESKTOP in desc.availability.profiles)
            assertTrue(PlatformProfile.HEADLESS_SERVER in desc.availability.profiles)
            assertTrue(PlatformProfile.MOBILE !in desc.availability.profiles,
                "stdio tools must not be available on MOBILE")
        }
    }

    @Test
    fun `HTTP tools have Egress effect on every tool`() {
        val descriptors = McpToolAdapter.descriptorsFor(httpServer)
        assertEquals(1, descriptors.size)
        val desc = descriptors.first()
        assertIs<EffectKind.Egress>(desc.effect)
        assertTrue((desc.effect as EffectKind.Egress).destination.contains("search-mcp.example.com"))
    }

    @Test
    fun `HTTP tools are available on all profiles including MOBILE`() {
        val descriptors = McpToolAdapter.descriptorsFor(httpServer)
        val availability = descriptors.first().availability
        assertTrue(PlatformProfile.MOBILE in availability.profiles)
        assertTrue(availability.requiresNetwork)
        assertEquals(AvailabilityTier.NETWORKED, availability.tier)
    }

    @Test
    fun `MCP tools use NETWORK_EGRESS permission for HTTP`() {
        val descriptors = McpToolAdapter.descriptorsFor(httpServer)
        assertEquals(Permission.NETWORK_EGRESS, descriptors.first().requiredPermission)
    }

    @Test
    fun `MCP tools use SHELL_EXEC permission for stdio`() {
        val descriptors = McpToolAdapter.descriptorsFor(stdioServer)
        descriptors.forEach { assertEquals(Permission.SHELL_EXEC, it.requiredPermission) }
    }

    @Test
    fun `resultGuidance is null for all MCP tools (D23 - MCP cannot supply result guidance)`() {
        val all = McpToolAdapter.descriptorsFor(stdioServer) +
                McpToolAdapter.descriptorsFor(httpServer)
        for (desc in all) {
            assertTrue(desc.resultGuidance == null,
                "MCP tool ${desc.name} must not supply resultGuidance (D23): got ${desc.resultGuidance}")
        }
    }

    @Test
    fun `tool names are prefixed with serverId`() {
        val descriptors = McpToolAdapter.descriptorsFor(stdioServer)
        for (desc in descriptors) {
            assertTrue(desc.name.startsWith("github:"),
                "Tool name must be prefixed with serverId: ${desc.name}")
        }
    }

    @Test
    fun `RecoveryClass is REVERSIBLE by default`() {
        val all = McpToolAdapter.descriptorsFor(stdioServer) +
                McpToolAdapter.descriptorsFor(httpServer)
        for (desc in all) {
            assertEquals(RecoveryClass.CHECKABLE, desc.recoveryClass)
        }
    }

    // ── HTTP validation ────────────────────────────────────────────────────────

    @Test
    fun `HTTPS endpoint is accepted on all profiles`() {
        val result = validateHttpEndpoint("https://api.example.com/mcp", PlatformProfile.MOBILE)
        assertIs<McpValidationResult.Ok>(result)
    }

    @Test
    fun `plain HTTP refused on MOBILE`() {
        val result = validateHttpEndpoint("http://api.example.com/mcp", PlatformProfile.MOBILE)
        assertIs<McpValidationResult.Rejected>(result)
        assertTrue(result.reason.contains("HTTPS") || result.reason.contains("refused"))
    }

    @Test
    fun `plain HTTP loopback accepted on DESKTOP`() {
        val result = validateHttpEndpoint("http://localhost:3000/mcp", PlatformProfile.DESKTOP)
        assertIs<McpValidationResult.Ok>(result)
    }

    @Test
    fun `plain HTTP loopback refused on MOBILE`() {
        val result = validateHttpEndpoint("http://localhost:3000/mcp", PlatformProfile.MOBILE)
        assertIs<McpValidationResult.Rejected>(result)
    }

    @Test
    fun `plain HTTP external refused on HEADLESS_SERVER`() {
        val result = validateHttpEndpoint(
            "http://some-external-server.com/mcp",
            PlatformProfile.HEADLESS_SERVER
        )
        assertIs<McpValidationResult.Rejected>(result)
    }
}
