package dev.aidos.mcp

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.BasicResourceHandle
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ToolOutcome
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * External / non-Kotlin MCP interoperability suite (PIPELINE.md §3, RFC-0031).
 *
 * Exercises both `stdio` and `Streamable HTTP` transports end-to-end against a real non-Kotlin
 * (Python 3) MCP server process through:
 * - `McpServerStore` (user.db registration)
 * - `McpAdoptionService` (`fetchCatalog` + selective `adopt`)
 * - `McpOperationAdoptionStore` (hash-pinned persistence in project.db)
 * - `McpServerActivator` + `McpClientFactory.Default` (`StdioMcpClient` / `HttpMcpClient`)
 * - `LazyMcpTool` + `McpLifecycleManager` (lazy activation, tool execution, idle release, re-activation)
 */
class McpExternalInteropTest {

    private val nowIso = "2026-08-17T00:00:00Z"
    private val pythonCmd = if (System.getProperty("os.name").contains("win", ignoreCase = true)) "python" else "python3"
    private val handle = BasicResourceHandle(CapabilityId("cap-interop"))

    private fun serverScriptPath(): String {
        val url = Thread.currentThread().contextClassLoader.getResource("external_mcp_server.py")
            ?: fail("external_mcp_server.py not found on test classpath")
        return File(url.toURI()).absolutePath
    }

    private fun openUserDb(): SqlDriver {
        val root = Files.createTempDirectory("mcp-interop-user").toFile()
        return AidosStorage.openUser(DesktopPaths.userDb(root.path), "test-1.0") { nowIso }.driver
    }

    private fun openProjectDb(projectId: String = "proj-interop"): SqlDriver {
        val root = Files.createTempDirectory("mcp-interop-proj").toFile()
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
        driver.execute(
            identifier = null,
            sql = "INSERT INTO projects (id, name, root_path, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'Interop Project', '/tmp/interop', ?, ?, ?)",
            parameters = 4,
        ) {
            bindString(0, projectId)
            bindString(1, nowIso)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        return driver
    }

    @Test
    fun `stdio end-to-end interop with external Python MCP server process`() = runBlocking {
        val userDriver = openUserDb()
        val projectDriver = openProjectDb("proj-stdio")
        val argsJson = Json.encodeToString(listOf(serverScriptPath(), "--mode", "stdio"))

        userDriver.execute(
            identifier = null,
            sql = "INSERT INTO mcp_servers " +
                "(name, transport, command, args_json, endpoint_url, profiles_json, secret_refs_json, auto_restart, registered_at) " +
                "VALUES ('py-stdio', 'stdio', ?, ?, NULL, '[\"DESKTOP\"]', '{\"MCP_API_KEY\":\"stdio_vault_ref\"}', 0, ?)",
            parameters = 3,
        ) {
            bindString(0, pythonCmd)
            bindString(1, argsJson)
            bindString(2, nowIso)
        }

        val secretResolver = McpSecretResolver { ref ->
            if (ref == "stdio_vault_ref") Result.success("stdio-secret-123".toCharArray())
            else Result.failure(NoSuchElementException(ref))
        }
        val serverStore = McpServerStore(userDriver)
        val adoptionStore = McpOperationAdoptionStore(projectDriver)
        val adoptionService = McpAdoptionService(
            serverStore = serverStore,
            adoptionStore = adoptionStore,
            secretResolver = secretResolver,
            clientFactory = McpClientFactory.Default,
        )

        // 1. Discover catalog from the real Python stdio subprocess and adopt add_numbers + inspect_auth.
        val catalog = adoptionService.fetchCatalog("proj-stdio", "py-stdio", PlatformProfile.DESKTOP).getOrThrow()
        assertEquals(listOf("add_numbers", "inspect_auth", "unadopted_secret_op"), catalog.tools.map { it.spec.name })

        val addSpec = catalog.tools.first { it.spec.name == "add_numbers" }.spec
        val authSpec = catalog.tools.first { it.spec.name == "inspect_auth" }.spec
        adoptionService.adopt("proj-stdio", "py-stdio", addSpec, nowIso)
        adoptionService.adopt("proj-stdio", "py-stdio", authSpec, nowIso)

        // 2. Wire LazyMcpTool + McpServerActivator + McpLifecycleManager using McpClientFactory.Default.
        var clock = 1_000L
        val activator = McpServerActivator(
            serverStore = serverStore,
            adoptionStore = adoptionStore,
            secretResolver = secretResolver,
            clientFactory = McpClientFactory.Default,
        )
        val adoptedSpecs = adoptionStore.adoptedCatalog("proj-stdio", "py-stdio")
        assertEquals(2, adoptedSpecs.size)

        val loaded = (serverStore.loadAll(PlatformProfile.DESKTOP).single() as McpServerLoadOutcome.Loaded).load
        val lazyTool = LazyMcpTool(
            serverId = "py-stdio",
            projectId = "proj-stdio",
            initialAdoptedSpecs = adoptedSpecs,
            transport = loaded.registration.transport,
            idleTimeoutMillis = 5_000L,
            clockMillis = { clock },
            activator = { activator.activate("proj-stdio", "py-stdio", PlatformProfile.DESKTOP) },
        )
        val lifecycle = McpLifecycleManager(clockMillis = { clock })
        lifecycle.register(lazyTool)

        try {
            assertFalse(lazyTool.isConnected)

            // 3. Execute add_numbers over stdio.
            val sumResult = lazyTool.execute(
                handle,
                "py-stdio:add_numbers",
                buildJsonObject { put("a", 19); put("b", 23) },
            )
            assertIs<ToolOutcome.Ok>(sumResult.outcome)
            assertEquals(listOf(ContentBlock.Text("sum=42")), sumResult.content)
            assertTrue(lazyTool.isConnected)

            // 4. Verify secret was injected into the Python subprocess's environment.
            val authResult = lazyTool.execute(
                handle,
                "py-stdio:inspect_auth",
                buildJsonObject {},
            )
            assertIs<ToolOutcome.Ok>(authResult.outcome)
            val authJson = Json.parseToJsonElement((authResult.content.single() as ContentBlock.Text).text).jsonObject
            assertEquals("true", authJson["env_secret_present"]?.jsonPrimitive?.content)

            // 5. Sweep idle connection and verify transparent re-activation on next call.
            clock = 10_000L
            assertEquals(listOf("py-stdio"), lifecycle.sweepIdle())
            assertFalse(lazyTool.isConnected)

            val sumAfterReconnect = lazyTool.execute(
                handle,
                "py-stdio:add_numbers",
                buildJsonObject { put("a", 100); put("b", 25) },
            )
            assertIs<ToolOutcome.Ok>(sumAfterReconnect.outcome)
            assertEquals(listOf(ContentBlock.Text("sum=125")), sumAfterReconnect.content)
            assertTrue(lazyTool.isConnected)
        } finally {
            lifecycle.closeAllSuspend()
        }
    }

    @Test
    fun `streamable HTTP end-to-end interop with external Python MCP server process`() = runBlocking {
        val process = ProcessBuilder(pythonCmd, serverScriptPath(), "--mode", "http")
            .redirectErrorStream(true)
            .start()

        try {
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val portLine = reader.readLine() ?: fail("Python HTTP MCP server exited without printing PORT")
            assertTrue(portLine.startsWith("PORT="), "Unexpected startup line: $portLine")
            val port = portLine.removePrefix("PORT=").trim().toInt()
            val endpointUrl = "http://127.0.0.1:$port/mcp"

            val userDriver = openUserDb()
            val projectDriver = openProjectDb("proj-http")

            userDriver.execute(
                identifier = null,
                sql = "INSERT INTO mcp_servers " +
                    "(name, transport, command, args_json, endpoint_url, profiles_json, secret_refs_json, auto_restart, registered_at) " +
                    "VALUES ('py-http', 'http', NULL, '[]', ?, '[\"DESKTOP\"]', '{\"Authorization\":\"http_vault_ref\"}', 0, ?)",
                parameters = 2,
            ) {
                bindString(0, endpointUrl)
                bindString(1, nowIso)
            }

            val secretResolver = McpSecretResolver { ref ->
                if (ref == "http_vault_ref") Result.success("Bearer http-secret-token-xyz".toCharArray())
                else Result.failure(NoSuchElementException(ref))
            }
            val serverStore = McpServerStore(userDriver)
            val adoptionStore = McpOperationAdoptionStore(projectDriver)
            val adoptionService = McpAdoptionService(
                serverStore = serverStore,
                adoptionStore = adoptionStore,
                secretResolver = secretResolver,
                clientFactory = McpClientFactory.Default,
            )

            // 1. Discover catalog over Streamable HTTP and adopt add_numbers + inspect_auth.
            val catalog = adoptionService.fetchCatalog("proj-http", "py-http", PlatformProfile.DESKTOP).getOrThrow()
            assertEquals(listOf("add_numbers", "inspect_auth", "unadopted_secret_op"), catalog.tools.map { it.spec.name })

            val addSpec = catalog.tools.first { it.spec.name == "add_numbers" }.spec
            val authSpec = catalog.tools.first { it.spec.name == "inspect_auth" }.spec
            adoptionService.adopt("proj-http", "py-http", addSpec, nowIso)
            adoptionService.adopt("proj-http", "py-http", authSpec, nowIso)

            // 2. Wire LazyMcpTool + McpServerActivator + McpLifecycleManager using McpClientFactory.Default.
            var clock = 1_000L
            val activator = McpServerActivator(
                serverStore = serverStore,
                adoptionStore = adoptionStore,
                secretResolver = secretResolver,
                clientFactory = McpClientFactory.Default,
            )
            val adoptedSpecs = adoptionStore.adoptedCatalog("proj-http", "py-http")
            val loaded = (serverStore.loadAll(PlatformProfile.DESKTOP).single() as McpServerLoadOutcome.Loaded).load
            val lazyTool = LazyMcpTool(
                serverId = "py-http",
                projectId = "proj-http",
                initialAdoptedSpecs = adoptedSpecs,
                transport = loaded.registration.transport,
                idleTimeoutMillis = 5_000L,
                clockMillis = { clock },
                activator = { activator.activate("proj-http", "py-http", PlatformProfile.DESKTOP) },
            )
            val lifecycle = McpLifecycleManager(clockMillis = { clock })
            lifecycle.register(lazyTool)

            try {
                // 3. Execute add_numbers over Streamable HTTP.
                val sumResult = lazyTool.execute(
                    handle,
                    "py-http:add_numbers",
                    buildJsonObject { put("a", 7); put("b", 8) },
                )
                assertIs<ToolOutcome.Ok>(sumResult.outcome)
                assertEquals(listOf(ContentBlock.Text("sum=15")), sumResult.content)

                // 4. Verify resolved Authorization header reached the Python HTTP server.
                val authResult = lazyTool.execute(
                    handle,
                    "py-http:inspect_auth",
                    buildJsonObject {},
                )
                assertIs<ToolOutcome.Ok>(authResult.outcome)
                val authJson = Json.parseToJsonElement((authResult.content.single() as ContentBlock.Text).text).jsonObject
                assertEquals("Bearer http-secret-token-xyz", authJson["auth_header"]?.jsonPrimitive?.content)

                // 5. Idle sweep and transparent re-activation over HTTP.
                clock = 10_000L
                assertEquals(listOf("py-http"), lifecycle.sweepIdle())
                assertFalse(lazyTool.isConnected)

                val sumAgain = lazyTool.execute(
                    handle,
                    "py-http:add_numbers",
                    buildJsonObject { put("a", 50); put("b", 50) },
                )
                assertIs<ToolOutcome.Ok>(sumAgain.outcome)
                assertEquals(listOf(ContentBlock.Text("sum=100")), sumAgain.content)
            } finally {
                lifecycle.closeAllSuspend()
            }
        } finally {
            process.destroy()
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly()
            }
        }
    }
}
