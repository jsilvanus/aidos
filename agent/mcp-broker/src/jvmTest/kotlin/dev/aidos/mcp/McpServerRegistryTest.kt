package dev.aidos.mcp

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.core.McpTransport
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
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

    @Test
    fun `upsert rejects malformed or unsupported-scheme http endpoint urls`() {
        val driver = openUserDriver()
        val registry = McpServerRegistry(driver)
        val invalidUrls = listOf(
            "file:///etc/passwd",
            "javascript:alert(1)",
            "ftp://example.com/mcp",
            "ws://example.com/mcp",
            "data:text/plain,hello",
            "",
            "   ",
            "/relative/mcp",
            "mcp.example.com/v1",
            "https://",
            "https:///missing-host",
            "http://:8080/no-host",
            "https://mcp.example.com:99999/v1",
            "https://mcp.example.com:abc/v1",
            "https://mcp.example.com/with space",
            "https://[broken/v1",
        )

        for (badUrl in invalidUrls) {
            assertFailsWith<IllegalArgumentException>("Expected rejection for URL '$badUrl'") {
                registry.upsert(
                    serverId = "bad-server",
                    transport = McpTransport.Http(endpointUrl = badUrl),
                    profiles = setOf(PlatformProfile.DESKTOP),
                    autoRestart = false,
                    nowIso = now,
                )
            }
        }
        assertTrue(McpServerStore(driver).loadAll(PlatformProfile.DESKTOP).isEmpty())

        registry.upsert(
            serverId = "local-http",
            transport = McpTransport.Http(endpointUrl = "  http://localhost:8080/mcp  "),
            profiles = setOf(PlatformProfile.DESKTOP),
            autoRestart = true,
            nowIso = now,
        )
        registry.upsert(
            serverId = "remote-https",
            transport = McpTransport.Http(endpointUrl = "https://mcp.example.com:8443/v1"),
            profiles = setOf(PlatformProfile.DESKTOP),
            autoRestart = false,
            nowIso = now,
        )
        val loaded = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        assertEquals(2, loaded.size)
        val local = (loaded.single { (it as McpServerLoadOutcome.Loaded).load.registration.serverId == "local-http" } as McpServerLoadOutcome.Loaded)
        assertEquals("http://localhost:8080/mcp", (local.load.registration.transport as McpTransport.Http).endpointUrl)
    }

    @Test
    fun `updating an existing server preserves registered_at and rowid`() {
        val driver = openUserDriver()
        val registry = McpServerRegistry(driver)
        val initialTime = "2026-11-13T00:00:00Z"
        val updatedTime = "2026-11-14T15:30:00Z"

        registry.upsert(
            serverId = "github",
            transport = McpTransport.Stdio(
                command = "npx",
                args = listOf("-y", "@modelcontextprotocol/server-github"),
                secretRefs = mapOf("GITHUB_TOKEN" to "github_token_v1"),
            ),
            profiles = setOf(PlatformProfile.DESKTOP),
            autoRestart = true,
            nowIso = initialTime,
        )

        val initialMeta = driver.executeQuery(
            identifier = null,
            sql = "SELECT rowid, registered_at FROM mcp_servers WHERE name = 'github'",
            mapper = { cursor ->
                assertTrue(cursor.next().value)
                QueryResult.Value(cursor.getLong(0) to cursor.getString(1))
            },
            parameters = 0,
        ).value
        assertEquals(initialTime, initialMeta.second)

        // Update same server with a new transport, profiles, and autoRestart=false at a later timestamp
        registry.upsert(
            serverId = "github",
            transport = McpTransport.Http(
                endpointUrl = "https://mcp.github.example.com/v2",
                secretRefs = mapOf("Authorization" to "github_token_v2"),
            ),
            profiles = setOf(PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER),
            autoRestart = false,
            nowIso = updatedTime,
        )

        val updatedRow = driver.executeQuery(
            identifier = null,
            sql = "SELECT rowid, registered_at, transport, command, endpoint_url, auto_restart " +
                "FROM mcp_servers WHERE name = 'github'",
            mapper = { cursor ->
                assertTrue(cursor.next().value)
                QueryResult.Value(
                    listOf<Any?>(
                        cursor.getLong(0),
                        cursor.getString(1),
                        cursor.getString(2),
                        cursor.getString(3),
                        cursor.getString(4),
                        cursor.getLong(5),
                    ),
                )
            },
            parameters = 0,
        ).value

        assertEquals(initialMeta.first, updatedRow[0], "rowid must be preserved on update (no delete+insert)")
        assertEquals(initialTime, updatedRow[1], "registered_at must be preserved on update")
        assertEquals("http", updatedRow[2])
        assertNull(updatedRow[3], "command should be cleared to NULL when switching to http")
        assertEquals("https://mcp.github.example.com/v2", updatedRow[4])
        assertEquals(0L, updatedRow[5])

        val loaded = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        assertEquals(1, loaded.size)
        val load = (loaded.single() as McpServerLoadOutcome.Loaded).load
        assertEquals(setOf(PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER), load.profiles)
        val http = load.registration.transport as McpTransport.Http
        assertEquals("https://mcp.github.example.com/v2", http.endpointUrl)
        assertEquals(mapOf("Authorization" to "github_token_v2"), http.secretRefs)
    }

    @Test
    fun `upsert validates and normalizes binder registrations and enforces profile compatibility`() {
        val driver = openUserDriver()
        val registry = McpServerRegistry(driver)
        val upperCert = "AB".repeat(32)
        val lowerCert = "ab".repeat(32)

        assertFailsWith<IllegalArgumentException> {
            registry.upsert(
                serverId = "amp-bad-cert",
                transport = McpTransport.Binder("com.agenticmessenger.mcp.host", ".AmpMcpBinderService", setOf("not-hex")),
                profiles = setOf(PlatformProfile.MOBILE),
                autoRestart = false,
                nowIso = now,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            registry.upsert(
                serverId = "amp-bad-profile",
                transport = McpTransport.Binder("com.agenticmessenger.mcp.host", ".AmpMcpBinderService", setOf(upperCert)),
                profiles = setOf(PlatformProfile.DESKTOP),
                autoRestart = false,
                nowIso = now,
            )
        }
        assertFailsWith<IllegalArgumentException> {
            registry.upsert(
                serverId = "stdio-mobile-profile",
                transport = McpTransport.Stdio("npx"),
                profiles = setOf(PlatformProfile.DESKTOP, PlatformProfile.MOBILE),
                autoRestart = false,
                nowIso = now,
            )
        }

        registry.upsert(
            serverId = "amp",
            transport = McpTransport.Binder(
                packageName = "com.agenticmessenger.mcp.host",
                serviceClass = "com.agenticmessenger.mcp.android.AmpMcpBinderService",
                signingCertSha256 = setOf(" $upperCert "),
            ),
            profiles = setOf(PlatformProfile.MOBILE),
            autoRestart = true,
            nowIso = now,
        )

        val loaded = McpServerStore(driver).loadAll(PlatformProfile.MOBILE).single() as McpServerLoadOutcome.Loaded
        val binder = loaded.load.registration.transport as McpTransport.Binder
        assertEquals(setOf(lowerCert), binder.signingCertSha256)
    }
}
