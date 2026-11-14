package dev.aidos.mcp.binder.android

import dev.aidos.mcp.core.McpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class InstalledMcpDiscoveryTest {

    @Test
    fun standardActionMatchesExpectedProtocolConstant() {
        assertEquals("fi.italeino.mcp.action.BINDER_SERVER", InstalledMcpDiscovery.ACTION_MCP_BINDER_SERVER)
    }

    @Test
    fun normalizeServiceClassExpandsPackageRelativeNames() {
        assertEquals(
            "com.agenticmessenger.mcp.host.AmpMcpBinderService",
            InstalledMcpDiscovery.normalizeServiceClass("com.agenticmessenger.mcp.host", ".AmpMcpBinderService"),
        )
        assertEquals(
            "com.agenticmessenger.mcp.android.AmpMcpBinderService",
            InstalledMcpDiscovery.normalizeServiceClass(
                "com.agenticmessenger.mcp.host",
                "com.agenticmessenger.mcp.android.AmpMcpBinderService",
            ),
        )
    }

    @Test
    fun discoveredProviderConvertsToMcpTransportBinder() {
        val certs = setOf("a1b2c3d4e5f60718293a4b5c6d7e8f90a1b2c3d4e5f60718293a4b5c6d7e8f90")
        val provider = DiscoveredMcpProvider(
            packageName = "com.agenticmessenger.mcp.host",
            serviceClass = "com.agenticmessenger.mcp.android.AmpMcpBinderService",
            appName = "Agentic Messenger",
            serviceName = "AmpMcpBinderService",
            signingCertSha256 = certs,
        )

        val transport = provider.toTransport()

        assertEquals("com.agenticmessenger.mcp.host", transport.packageName)
        assertEquals("com.agenticmessenger.mcp.android.AmpMcpBinderService", transport.serviceClass)
        assertEquals(certs, transport.signingCertSha256)
        assertEquals(1, transport.signingCertSha256.size)
    }
}
