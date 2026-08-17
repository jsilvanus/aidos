package dev.aidos.mcp

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.RecoveryClass
import dev.aidos.mcp.core.McpServerRegistration
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.mcp.core.McpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M18 done-when (RFC-0031, D17, D23), the `ToolDescriptor`-rendering half of the original
 * `dev.aidos.mcp.McpAdapterTest` (RFC-0031, "Implementation Layering"). The pure transport
 * classification and endpoint validation cases moved to `mcp-policy`'s
 * `TransportClassificationTest` and `EndpointValidationTest` — this file only covers what
 * [McpToolAdapter.descriptorsFor] renders onto [dev.aidos.kernel.ToolDescriptor] itself:
 *
 * 1. An off-the-shelf MCP server's tools appear in the broker with EffectKind and
 *    RecoveryClass assigned.
 * 2. HTTP transport forces Egress on every tool.
 * 3. stdio transport uses platform profiles (DESKTOP/HEADLESS_SERVER only).
 * 4. An MCP server cannot raise a capability request — resultGuidance is always null (D23).
 * 5. Tool names are namespaced by serverId.
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
    fun `RecoveryClass is CHECKABLE by default`() {
        val all = McpToolAdapter.descriptorsFor(stdioServer) +
                McpToolAdapter.descriptorsFor(httpServer)
        for (desc in all) {
            assertEquals(RecoveryClass.CHECKABLE, desc.recoveryClass)
        }
    }
}
