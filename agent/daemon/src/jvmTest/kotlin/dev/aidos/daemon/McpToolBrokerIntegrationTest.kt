package dev.aidos.daemon

import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.UserId
import dev.aidos.mcp.McpAdoptionService
import dev.aidos.mcp.McpClientFactory
import dev.aidos.mcp.McpOperationAdoptionStore
import dev.aidos.mcp.McpSecretResolver
import dev.aidos.mcp.McpServerStore
import dev.aidos.mcp.core.McpCallResult
import dev.aidos.mcp.core.McpClient
import dev.aidos.mcp.core.McpContent
import dev.aidos.mcp.core.McpServerInfo
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpToolBrokerIntegrationTest {

    private val nowIso = "2026-08-10T00:00:00Z"
    private fun nextId() = UuidV7Generator().next()

    private fun openProjectDriver() = run {
        val root = Files.createTempDirectory("mcp-broker-proj-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun openUserDriver() = run {
        val root = Files.createTempDirectory("mcp-broker-user-test").toFile()
        AidosStorage.openUser(DesktopPaths.userDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private class FakeMcpClient(
        private val catalog: List<McpToolSpec> = listOf(
            McpToolSpec("echo", "Echoes input back", buildJsonObject { put("type", "object") }),
            McpToolSpec("secret_tool", "Unadopted tool", buildJsonObject { put("type", "object") }),
        ),
    ) : McpClient {
        var closed = false
        override suspend fun initialize() = McpServerInfo("fake-server", "1.0.0")
        override suspend fun listTools() = catalog
        override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
            return if (name == "echo") {
                McpCallResult(listOf(McpContent.Text("Echo: " + (arguments["msg"] ?: "none"))), isError = false)
            } else {
                McpCallResult(listOf(McpContent.Text("Unknown: $name")), isError = true)
            }
        }
        override fun close() { closed = true }
        override suspend fun closeSuspend() { closed = true }
    }

    @Test
    fun `adopted MCP tools are exposed in ToolBroker and executed on demand`() = runTest {
        val userDriver = openUserDriver()
        val projectDriver = openProjectDriver()
        val projectId = "proj-mcp-1"
        val sessionId = "sess-mcp-1"

        // Seed project & session
        projectDriver.execute(null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)", 4
        ) { bindString(0, projectId); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso) }

        projectDriver.execute(null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'test', 'DRIVER', 'RUNNING', ?, ?, ?)", 5
        ) { bindString(0, sessionId); bindString(1, projectId); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }

        // Register MCP server in user.db
        userDriver.execute(null,
            "INSERT INTO mcp_servers (name, transport, command, args_json, endpoint_url, profiles_json, secret_refs_json, auto_restart, registered_at) " +
                "VALUES ('test-mcp', 'stdio', 'dummy-cmd', '[]', NULL, '[\"DESKTOP\"]', '{}', 1, ?)", 2
        ) { bindString(0, nowIso) }

        val fakeClient = FakeMcpClient()
        val clientFactory = McpClientFactory { _, _ -> fakeClient }
        val serverStore = McpServerStore(userDriver)
        val adoptionStore = McpOperationAdoptionStore(projectDriver)
        val adoptionService = McpAdoptionService(serverStore, adoptionStore, McpSecretResolver { Result.failure(Exception()) }, clientFactory)

        // 1. Check adoption flow
        val servers = adoptionService.listConfiguredServers(PlatformProfile.DESKTOP)
        assertEquals(1, servers.size)
        assertEquals("test-mcp", servers[0].serverId)

        // Fetch catalog and adopt ONLY 'echo'
        val catalogResult = adoptionService.fetchCatalog(projectId, "test-mcp", PlatformProfile.DESKTOP)
        assertTrue(catalogResult.isSuccess)
        val catalog = catalogResult.getOrThrow()
        assertEquals(2, catalog.tools.size)
        val echoTool = catalog.tools.first { it.spec.name == "echo" }
        assertFalse(echoTool.isAdopted)

        // Adopt 'echo'
        adoptionService.adopt(projectId, "test-mcp", echoTool.spec, nowIso)
        assertTrue(adoptionService.listAdopted(projectId, "test-mcp").any { it.name == "echo" })

        // 2. Wire into RuntimeCompositionRoot
        val root = RuntimeCompositionRoot(
            anthropicApiKey = { null },
            userDriver = userDriver,
            mcpSecretResolver = { Result.failure(Exception()) },
            mcpClientFactory = clientFactory,
        )

        // Grant capability to session for SHELL_EXEC (stdio classification)
        val capMgr = SqliteCapabilityManager(projectDriver, UuidV7Generator(), { nowIso })
        val grant = capMgr.grant(
            subjectId = sessionId,
            subjectKind = SubjectKind.SESSION,
            permission = Permission.SHELL_EXEC,
            scope = CapabilityScope.Shell(ProjectId(projectId), "/", null),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user"),
        )

        // Create ToolBroker through RuntimeCompositionRoot buildExecutor logic
        val broker = dev.aidos.broker.ToolBroker(
            capabilityManager = capMgr,
            audit = dev.aidos.broker.AuditLog(projectDriver, "dev-1"),
            idGen = ::nextId,
            nowIso = { nowIso },
        )
        // Load adopted MCP tools via LazyMcpTool exactly as RuntimeCompositionRoot does
        val loaded = serverStore.loadAll(PlatformProfile.DESKTOP).first() as dev.aidos.mcp.McpServerLoadOutcome.Loaded
        val adopted = adoptionStore.adoptedCatalog(projectId, loaded.load.registration.serverId)
        val activator = dev.aidos.mcp.McpServerActivator(serverStore, adoptionStore, { Result.failure(Exception()) }, clientFactory)
        val lazyTool = dev.aidos.mcp.LazyMcpTool(
            serverId = "test-mcp",
            projectId = projectId,
            initialAdoptedSpecs = adopted,
            transport = loaded.load.registration.transport,
            activator = { activator.activate(projectId, "test-mcp", PlatformProfile.DESKTOP) }
        )
        broker.register(lazyTool)

        // Discover descriptors
        val descriptors = broker.descriptorsFor(sessionId, PlatformProfile.DESKTOP, false)
        assertTrue(descriptors.any { it.name == "test-mcp:echo" }, "Adopted tool test-mcp:echo must be offered to the model")
        assertFalse(descriptors.any { it.name == "test-mcp:secret_tool" }, "Unadopted tool must NEVER be offered to the model")

        // Invoke the tool
        val call = ToolCall(
            callId = "call-1",
            toolName = "test-mcp:echo",
            arguments = buildJsonObject { put("msg", "hello agent") },
            capabilityId = grant.getOrThrow().id,
        )
        val result = broker.invoke(sessionId, call, TrustLevel.TRUSTED)
        assertIs<ToolOutcome.Ok>(result.outcome)
        assertEquals(TrustLevel.UNTRUSTED, result.trustLevel, "MCP tool result is unconditionally UNTRUSTED (RFC-0027, RFC-0031)")
        assertEquals(listOf(ContentBlock.Text("Echo: \"hello agent\"")), result.content)

        // Test unadopt
        adoptionService.unadopt(projectId, "test-mcp", "echo")
        assertTrue(adoptionService.listAdopted(projectId, "test-mcp").isEmpty())
    }
}
