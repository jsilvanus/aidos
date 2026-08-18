package dev.aidos.mcp

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.PlatformProfile
import dev.aidos.mcp.core.McpCallResult
import dev.aidos.mcp.core.McpClient
import dev.aidos.mcp.core.McpRpcException
import dev.aidos.mcp.core.McpServerInfo
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/**
 * [McpServerActivator] (RFC-0031, M18): the connect -> catalog -> adoption -> tool sequence.
 * The test databases are built from the real `schema/user.sql` / `schema/project.sql` DDL via
 * `:storage`'s `AidosStorage`, same as `McpServerStoreTest` / `McpOperationAdoptionStoreTest` --
 * not a hand-written subset. [FakeMcpClient] is injected through [McpClientFactory] so nothing
 * here spawns a process or opens a socket.
 */
class McpServerActivatorTest {

    private fun openUserDriver(): SqlDriver {
        val root = Files.createTempDirectory("mcp-activator-user-test").toFile()
        return AidosStorage.openUser(DesktopPaths.userDb(root.path), "test-1.0") { "2026-08-17T00:00:00Z" }.driver
    }

    private fun openProjectDriver(): SqlDriver {
        val root = Files.createTempDirectory("mcp-activator-project-test").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { "2026-08-17T00:00:00Z" }.driver
    }

    /** `mcp_operation_adoptions.project_id` has an FK to `projects(id)`; every test needs one row. */
    private fun SqlDriver.insertProject(id: String) {
        execute(
            identifier = null,
            sql = "INSERT INTO projects (id, name, root_path, created_at, updated_at, state_updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            parameters = 6,
        ) {
            bindString(0, id)
            bindString(1, "Test Project")
            bindString(2, "/tmp/test-project")
            bindString(3, "2026-08-17T00:00:00Z")
            bindString(4, "2026-08-17T00:00:00Z")
            bindString(5, "2026-08-17T00:00:00Z")
        }
    }

    private fun SqlDriver.insertServer(
        name: String,
        transport: String,
        command: String? = null,
        argsJson: String = "[]",
        endpointUrl: String? = null,
        profilesJson: String = "[\"DESKTOP\"]",
        secretRefsJson: String = "{}",
    ) {
        execute(
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
            bindLong(7, 1L)
            bindString(8, "2026-08-17T00:00:00Z")
        }
    }

    /** Never spawns anything: a plain in-memory stand-in for a live [McpClient]. */
    private class FakeMcpClient(
        private val catalog: List<McpToolSpec>,
        private val failInitialize: Boolean = false,
    ) : McpClient {
        var closed = false
            private set

        override suspend fun initialize(): McpServerInfo {
            if (failInitialize) throw McpRpcException("fake initialize failure")
            return McpServerInfo(name = "fake", version = "1")
        }

        override suspend fun listTools(): List<McpToolSpec> = catalog

        override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult =
            McpCallResult(content = emptyList(), isError = false)

        override fun close() {
            closed = true
        }
    }

    /** [values] maps vault reference -> plaintext; a reference absent from it fails to resolve. */
    private fun fakeResolver(values: Map<String, String>) = McpSecretResolver { reference ->
        values[reference]?.let { Result.success(it.toCharArray()) }
            ?: Result.failure(NoSuchElementException("no such secret: $reference"))
    }

    private fun spec(name: String, description: String) = McpToolSpec(name, description)

    // ─── adopted-only catalog reaching the tool ─────────────────────────────

    @Test
    fun `only adopted operations reach the tool, unadopted ones are reported separately`() = runBlocking {
        val userDriver = openUserDriver()
        userDriver.insertServer(name = "github", transport = "stdio", command = "/bin/github-mcp")
        val projectDriver = openProjectDriver()
        projectDriver.insertProject("proj-1")

        val adoptionStore = McpOperationAdoptionStore(projectDriver)
        val adopted = spec("list_issues", "Lists issues")
        val notAdopted = spec("delete_repo", "Deletes a repository")
        adoptionStore.recordAdoption("proj-1", "github", adopted, "2026-08-17T00:00:00Z")

        val activator = McpServerActivator(
            serverStore = McpServerStore(userDriver),
            adoptionStore = adoptionStore,
            secretResolver = fakeResolver(emptyMap()),
            clientFactory = McpClientFactory { _, _ -> FakeMcpClient(catalog = listOf(adopted, notAdopted)) },
        )

        val outcome = activator.activate("proj-1", "github", PlatformProfile.DESKTOP)
        val activation = assertIs<McpActivationOutcome.Activated>(outcome).activation
        try {
            assertEquals(listOf("github:list_issues"), activation.tool.operations().map { it.name })
            assertEquals(listOf(notAdopted), activation.unadopted)
        } finally {
            activation.close()
        }
    }

    // ─── changed descriptor hash makes a previously adopted operation absent ──

    @Test
    fun `a changed descriptor makes a previously adopted operation absent from the tool`() = runBlocking {
        val userDriver = openUserDriver()
        userDriver.insertServer(name = "fs", transport = "stdio", command = "/bin/fs-mcp")
        val projectDriver = openProjectDriver()
        projectDriver.insertProject("proj-1")

        val adoptionStore = McpOperationAdoptionStore(projectDriver)
        val original = spec("delete_file", "Deletes a file")
        adoptionStore.recordAdoption("proj-1", "fs", original, "2026-08-17T00:00:00Z")
        // Same name, changed description: a real server-side revision, not a fresh operation.
        val changed = spec("delete_file", "Deletes a file permanently, bypassing trash")

        val activator = McpServerActivator(
            serverStore = McpServerStore(userDriver),
            adoptionStore = adoptionStore,
            secretResolver = fakeResolver(emptyMap()),
            clientFactory = McpClientFactory { _, _ -> FakeMcpClient(catalog = listOf(changed)) },
        )

        val outcome = activator.activate("proj-1", "fs", PlatformProfile.DESKTOP)
        val activation = assertIs<McpActivationOutcome.Activated>(outcome).activation
        try {
            assertTrue(activation.tool.operations().isEmpty(), "a changed descriptor must not ride the old adoption")
            assertEquals(listOf(changed), activation.unadopted)
        } finally {
            activation.close()
        }
    }

    // ─── unknown server name ─────────────────────────────────────────────────

    @Test
    fun `an unknown server name fails cleanly with ServerNotFound`() = runBlocking {
        val userDriver = openUserDriver()
        val projectDriver = openProjectDriver()
        projectDriver.insertProject("proj-1")

        val activator = McpServerActivator(
            serverStore = McpServerStore(userDriver),
            adoptionStore = McpOperationAdoptionStore(projectDriver),
            secretResolver = fakeResolver(emptyMap()),
            clientFactory = McpClientFactory { _, _ -> error("must not connect for a server that does not exist") },
        )

        val outcome = activator.activate("proj-1", "nonexistent", PlatformProfile.DESKTOP)
        val failure = assertIs<McpActivationFailure.ServerNotFound>(assertIs<McpActivationOutcome.Failed>(outcome).failure)
        assertEquals("nonexistent", failure.serverName)
    }

    // ─── rejected row ────────────────────────────────────────────────────────

    @Test
    fun `a rejected registration row fails cleanly with ServerRejected, naming the reason`() = runBlocking {
        val userDriver = openUserDriver()
        userDriver.insertServer(name = "broken", transport = "stdio", command = "/bin/tool", profilesJson = "not json")
        val projectDriver = openProjectDriver()
        projectDriver.insertProject("proj-1")

        val activator = McpServerActivator(
            serverStore = McpServerStore(userDriver),
            adoptionStore = McpOperationAdoptionStore(projectDriver),
            secretResolver = fakeResolver(emptyMap()),
            clientFactory = McpClientFactory { _, _ -> error("must not connect for a rejected row") },
        )

        val outcome = activator.activate("proj-1", "broken", PlatformProfile.DESKTOP)
        val failure = assertIs<McpActivationFailure.ServerRejected>(assertIs<McpActivationOutcome.Failed>(outcome).failure)
        assertEquals("broken", failure.serverName)
        assertTrue(failure.reason.isNotBlank())
    }

    // ─── vault reference fails to resolve ───────────────────────────────────

    @Test
    fun `an unresolvable vault reference fails cleanly and never leaks a resolved secret`() = runBlocking {
        val userDriver = openUserDriver()
        userDriver.insertServer(
            name = "github",
            transport = "stdio",
            command = "/bin/github-mcp",
            // NPM_TOKEN resolves first (insertion order) so its value is actually produced and
            // discarded before GITHUB_TOKEN's reference fails -- this exercises "already-resolved
            // secrets are never carried into the failure", not just "the failing one is fine".
            secretRefsJson = "{\"NPM_TOKEN\": \"npm_token\", \"GITHUB_TOKEN\": \"github_token\"}",
        )
        val projectDriver = openProjectDriver()
        projectDriver.insertProject("proj-1")

        val secretValue = "sk-super-secret-value-must-not-leak"
        val secretResolver = fakeResolver(values = mapOf("npm_token" to secretValue)) // "github_token" is absent -> fails.

        val activator = McpServerActivator(
            serverStore = McpServerStore(userDriver),
            adoptionStore = McpOperationAdoptionStore(projectDriver),
            secretResolver = secretResolver,
            clientFactory = McpClientFactory { _, _ -> error("must not connect when a secret fails to resolve") },
        )

        val outcome = activator.activate("proj-1", "github", PlatformProfile.DESKTOP)
        val failure = assertIs<McpActivationOutcome.Failed>(outcome).failure
        val unresolved = assertIs<McpActivationFailure.SecretUnresolved>(failure)
        assertEquals("github", unresolved.serverName)
        assertEquals("github_token", unresolved.reference, "must name the failing *reference*, never the destination or a value")
        assertTrue(
            !outcome.toString().contains(secretValue),
            "a value already resolved for another destination must never appear in the failure",
        )
    }

    // ─── empty adoption set ──────────────────────────────────────────────────

    @Test
    fun `an empty adoption set succeeds with an empty catalog, not an error`() = runBlocking {
        val userDriver = openUserDriver()
        userDriver.insertServer(name = "github", transport = "stdio", command = "/bin/github-mcp")
        val projectDriver = openProjectDriver()
        projectDriver.insertProject("proj-1")

        val op1 = spec("list_issues", "Lists issues")
        val op2 = spec("close_issue", "Closes an issue")

        val activator = McpServerActivator(
            serverStore = McpServerStore(userDriver),
            adoptionStore = McpOperationAdoptionStore(projectDriver),
            secretResolver = fakeResolver(emptyMap()),
            clientFactory = McpClientFactory { _, _ -> FakeMcpClient(catalog = listOf(op1, op2)) },
        )

        val outcome = activator.activate("proj-1", "github", PlatformProfile.DESKTOP)
        val activation = assertIs<McpActivationOutcome.Activated>(outcome).activation
        try {
            assertTrue(activation.tool.operations().isEmpty(), "nothing was adopted, so the tool offers nothing")
            assertEquals(listOf(op1, op2), activation.unadopted)
        } finally {
            activation.close()
        }
    }

    // ─── the client is released on a connect failure, not leaked ────────────

    @Test
    fun `a client that fails during initialize is closed, not leaked`() = runBlocking {
        val userDriver = openUserDriver()
        userDriver.insertServer(name = "flaky", transport = "stdio", command = "/bin/flaky-mcp")
        val projectDriver = openProjectDriver()
        projectDriver.insertProject("proj-1")

        var created: FakeMcpClient? = null
        val activator = McpServerActivator(
            serverStore = McpServerStore(userDriver),
            adoptionStore = McpOperationAdoptionStore(projectDriver),
            secretResolver = fakeResolver(emptyMap()),
            clientFactory = McpClientFactory { _, _ ->
                FakeMcpClient(catalog = emptyList(), failInitialize = true).also { created = it }
            },
        )

        val outcome = activator.activate("proj-1", "flaky", PlatformProfile.DESKTOP)
        val failure = assertIs<McpActivationFailure.ConnectFailed>(assertIs<McpActivationOutcome.Failed>(outcome).failure)
        assertEquals("flaky", failure.serverName)
        assertTrue(created?.closed == true, "the client constructed before initialize() failed must be closed, not leaked")
    }
}
