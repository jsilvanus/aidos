package dev.aidos.mcp

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.core.McpTransport
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `McpServerStore` (RFC-0031 "Registration is user-scope; projects only request", RFC-0054).
 *
 * The database under test is opened through `:storage`'s `AidosStorage.openUser`, which applies
 * the real `schema/user.sql` (RFC-0040) via `MigrationRunner` -- not a hand-written subset of the
 * DDL, since drift between a test schema and the canonical one is exactly what `schema/check.py`
 * exists to prevent (see this task's own instructions).
 */
class McpServerStoreTest {

    private fun openUserDriver(): SqlDriver {
        val root = Files.createTempDirectory("mcp-server-store-test").toFile()
        val db = AidosStorage.openUser(DesktopPaths.userDb(root.path), "test-1.0") { "2026-08-17T00:00:00Z" }
        return db.driver
    }

    private fun insertServer(
        driver: SqlDriver,
        name: String,
        transport: String,
        command: String? = null,
        argsJson: String = "[]",
        endpointUrl: String? = null,
        profilesJson: String = "[\"DESKTOP\"]",
        secretRefsJson: String = "{}",
        autoRestart: Boolean = true,
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO mcp_servers " +
                "(name, transport, command, args_json, endpoint_url, profiles_json, secret_refs_json, " +
                "auto_restart, registered_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            parameters = 9,
        ) {
            bindString(0, name)
            bindString(1, transport)
            bindString(2, command)
            bindString(3, argsJson)
            bindString(4, endpointUrl)
            bindString(5, profilesJson)
            bindString(6, secretRefsJson)
            bindLong(7, if (autoRestart) 1L else 0L)
            bindString(8, "2026-08-17T00:00:00Z")
        }.value
    }

    // ─── Empty table ────────────────────────────────────────────────────────

    @Test
    fun `empty table loads no outcomes`() {
        val driver = openUserDriver()
        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        assertTrue(outcomes.isEmpty())
    }

    // ─── stdio row ──────────────────────────────────────────────────────────

    @Test
    fun `a stdio row loads with an empty tool catalog and the secret reference carried through`() {
        val driver = openUserDriver()
        insertServer(
            driver,
            name = "github",
            transport = "stdio",
            command = "npx",
            argsJson = "[\"-y\", \"@modelcontextprotocol/server-github\"]",
            profilesJson = "[\"DESKTOP\", \"HEADLESS_SERVER\"]",
            secretRefsJson = "{\"GITHUB_TOKEN\": \"github_token\"}",
        )

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        assertEquals(1, outcomes.size)
        val loaded = assertIs<McpServerLoadOutcome.Loaded>(outcomes.single())

        assertEquals("github", loaded.load.registration.serverId)
        assertTrue(loaded.load.registration.tools.isEmpty(), "tools must be empty at load time (fetched at enable time)")
        assertEquals(setOf(PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER), loaded.load.profiles)

        val transport = assertIs<McpTransport.Stdio>(loaded.load.registration.transport)
        assertEquals("npx", transport.command)
        assertEquals(listOf("-y", "@modelcontextprotocol/server-github"), transport.args)
        // secret_refs_json holds a *reference*, never a value (RFC-0035): the key is the env var
        // the secret is injected under, the value is the vault key -- not a resolved secret.
        // Both halves must survive, which is why this is a map and not a single slot.
        assertEquals(mapOf("GITHUB_TOKEN" to "github_token"), transport.secretRefs)
    }

    @Test
    fun `a stdio row with no secret ref loads with no credentials`() {
        val driver = openUserDriver()
        insertServer(driver, name = "local-tool", transport = "stdio", command = "/usr/local/bin/local-tool")

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        val loaded = assertIs<McpServerLoadOutcome.Loaded>(outcomes.single())
        val transport = assertIs<McpTransport.Stdio>(loaded.load.registration.transport)
        assertTrue(transport.secretRefs.isEmpty())
    }

    @Test
    fun `a stdio row naming several secrets keeps all of them`() {
        val driver = openUserDriver()
        insertServer(
            driver,
            name = "multi",
            transport = "stdio",
            command = "npx",
            secretRefsJson = "{\"GITHUB_TOKEN\": \"github_token\", \"NPM_TOKEN\": \"npm_token\"}",
        )

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        val loaded = assertIs<McpServerLoadOutcome.Loaded>(outcomes.single())
        val transport = assertIs<McpTransport.Stdio>(loaded.load.registration.transport)
        assertEquals(
            mapOf("GITHUB_TOKEN" to "github_token", "NPM_TOKEN" to "npm_token"),
            transport.secretRefs,
            "a server configured with two secrets is representable; dropping one would spawn it under-credentialed",
        )
    }

    // ─── http row ───────────────────────────────────────────────────────────

    @Test
    fun `an http row loads with the header name and secret reference carried through`() {
        val driver = openUserDriver()
        insertServer(
            driver,
            name = "issues",
            transport = "http",
            endpointUrl = "https://mcp.example.com/v1",
            profilesJson = "[\"MOBILE\", \"DESKTOP\", \"HEADLESS_SERVER\"]",
            secretRefsJson = "{\"Authorization\": \"issues_token\"}",
        )

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.MOBILE)
        val loaded = assertIs<McpServerLoadOutcome.Loaded>(outcomes.single())

        assertEquals("issues", loaded.load.registration.serverId)
        assertTrue(loaded.load.registration.tools.isEmpty())
        assertEquals(
            setOf(PlatformProfile.MOBILE, PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER),
            loaded.load.profiles,
        )

        val transport = assertIs<McpTransport.Http>(loaded.load.registration.transport)
        assertEquals("https://mcp.example.com/v1", transport.endpointUrl)
        assertEquals("Authorization", transport.authHeaderName)
        assertEquals(mapOf("Authorization" to "issues_token"), transport.secretRefs)
    }

    // ─── Rejected: plain HTTP, non-loopback ────────────────────────────────

    @Test
    fun `an http row on plain HTTP to a non-loopback host is rejected, not silently dropped`() {
        val driver = openUserDriver()
        insertServer(
            driver,
            name = "insecure",
            transport = "http",
            endpointUrl = "http://mcp.example.com/v1",
        )

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        assertEquals(1, outcomes.size)
        val rejected = assertIs<McpServerLoadOutcome.Rejected>(outcomes.single())
        assertEquals("insecure", rejected.serverId)
        assertTrue(rejected.reason.isNotBlank())
    }

    @Test
    fun `plain HTTP on loopback is accepted on DESKTOP`() {
        val driver = openUserDriver()
        insertServer(
            driver,
            name = "dev-server",
            transport = "http",
            endpointUrl = "http://127.0.0.1:9000/v1",
        )

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        assertIs<McpServerLoadOutcome.Loaded>(outcomes.single())
    }

    @Test
    fun `plain HTTP on loopback is rejected on MOBILE -- no loopback exemption there`() {
        val driver = openUserDriver()
        insertServer(
            driver,
            name = "dev-server",
            transport = "http",
            endpointUrl = "http://127.0.0.1:9000/v1",
        )

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.MOBILE)
        assertIs<McpServerLoadOutcome.Rejected>(outcomes.single())
    }

    // ─── Rejected: malformed JSON ───────────────────────────────────────────

    @Test
    fun `malformed profiles_json is rejected, not silently dropped`() {
        val driver = openUserDriver()
        insertServer(driver, name = "broken", transport = "stdio", command = "/bin/tool", profilesJson = "not json")

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        val rejected = assertIs<McpServerLoadOutcome.Rejected>(outcomes.single())
        assertEquals("broken", rejected.serverId)
        assertTrue(rejected.reason.contains("profiles_json"), "reason should name the offending column: ${rejected.reason}")
    }

    @Test
    fun `malformed args_json is rejected`() {
        val driver = openUserDriver()
        insertServer(driver, name = "broken-args", transport = "stdio", command = "/bin/tool", argsJson = "{not-an-array}")

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        val rejected = assertIs<McpServerLoadOutcome.Rejected>(outcomes.single())
        assertTrue(rejected.reason.contains("args_json"), rejected.reason)
    }

    @Test
    fun `malformed secret_refs_json is rejected`() {
        val driver = openUserDriver()
        insertServer(driver, name = "broken-secret", transport = "stdio", command = "/bin/tool", secretRefsJson = "[\"not\", \"an\", \"object\"]")

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        val rejected = assertIs<McpServerLoadOutcome.Rejected>(outcomes.single())
        assertTrue(rejected.reason.contains("secret_refs_json"), rejected.reason)
    }

    @Test
    fun `secret_refs_json with a blank destination name is rejected`() {
        val driver = openUserDriver()
        insertServer(
            driver,
            name = "blank-destination",
            transport = "stdio",
            command = "/bin/tool",
            secretRefsJson = "{\"\": \"ref-a\"}",
        )

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        val rejected = assertIs<McpServerLoadOutcome.Rejected>(outcomes.single())
        assertTrue(rejected.reason.contains("blank destination name"), rejected.reason)
    }

    // ─── Multiple rows, mixed outcomes ──────────────────────────────────────

    @Test
    fun `multiple rows are each reported independently -- one rejection does not drop the others`() {
        val driver = openUserDriver()
        insertServer(driver, name = "good-stdio", transport = "stdio", command = "/bin/good")
        insertServer(driver, name = "good-http", transport = "http", endpointUrl = "https://good.example.com/v1")
        insertServer(driver, name = "bad", transport = "stdio", command = "/bin/bad", profilesJson = "garbage")

        val outcomes = McpServerStore(driver).loadAll(PlatformProfile.DESKTOP)
        assertEquals(3, outcomes.size)
        val byId = outcomes.associateBy {
            when (it) {
                is McpServerLoadOutcome.Loaded -> it.load.registration.serverId
                is McpServerLoadOutcome.Rejected -> it.serverId
            }
        }
        assertIs<McpServerLoadOutcome.Loaded>(byId.getValue("good-stdio"))
        assertIs<McpServerLoadOutcome.Loaded>(byId.getValue("good-http"))
        assertIs<McpServerLoadOutcome.Rejected>(byId.getValue("bad"))
    }
}
