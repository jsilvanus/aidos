package dev.aidos.mcp

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.core.McpTransport
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class McpServerRegistryTest {
    private val now = "2026-11-13T00:00:00Z"

    private fun openUserDriver(): SqlDriver {
        val root = Files.createTempDirectory("mcp-server-registry-test").toFile()
        return AidosStorage.openUser(DesktopPaths.userDb(root.path), "test-1.0") { now }.driver
    }

    @Test
    fun `upsert writes stdio row that store can read`() {
        val driver = openUserDriver()
        val registry = McpServerRegistry(driver)
        registry.upsert(
            serverId = "github",
            transport = McpTransport.Stdio(
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-github"),
                secretRefs = mapOf("GITHUB_TOKEN" to "github_token"),
            ),
            profiles = setOf(PlatformProfile.DESKTOP),
            autoRestart = true,
            nowIso = now,
        )

        val loaded = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        assertEquals(1, loaded.size)
        val row = loaded.single() as McpServerLoadOutcome.Loaded
        val transport = row.load.registration.transport as McpTransport.Stdio
        assertEquals("npx", transport.command)
        assertEquals(mapOf("GITHUB_TOKEN" to "github_token"), transport.secretRefs)
    }

    @Test
    fun `upsert then delete removes server row`() {
        val driver = openUserDriver()
        val registry = McpServerRegistry(driver)
        registry.upsert(
            serverId = "issues",
            transport = McpTransport.Http(
                endpointUrl = "https://mcp.example.com/v1",
                authHeaderName = "Authorization",
                secretRefs = mapOf("Authorization" to "issues_token"),
            ),
            profiles = setOf(PlatformProfile.DESKTOP, PlatformProfile.MOBILE),
            autoRestart = false,
            nowIso = now,
        )
        registry.delete("issues")
        assertTrue(McpServerStore(driver).loadAll(PlatformProfile.DESKTOP).isEmpty())
    }
}
