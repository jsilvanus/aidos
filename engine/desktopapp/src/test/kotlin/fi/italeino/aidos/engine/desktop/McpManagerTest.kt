package fi.italeino.aidos.engine.desktop

import dev.aidos.mcp.McpClientFactory
import dev.aidos.mcp.core.McpCallResult
import dev.aidos.mcp.core.McpClient
import dev.aidos.mcp.core.McpServerInfo
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.vault.DesktopVault
import fi.italeino.aidos.engine.ui.ParsedMcpServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The Tools pane's broker: real user.db registration, vault-held secrets, a catalog read from a (fake) live server, adoption rows. */
class McpManagerTest {
    private val home = Files.createTempDirectory("aidos-mcp-manager").toFile()
    private val seenSecrets = mutableListOf<Map<String, String>>()

    private val factory = McpClientFactory { _, secrets ->
        seenSecrets += secrets
        object : McpClient {
            override suspend fun initialize() = McpServerInfo("fake", "1")
            override suspend fun listTools() = listOf(McpToolSpec("read_note", "Reads a note"), McpToolSpec("delete_note", "Deletes a note"))
            override suspend fun callTool(name: String, arguments: JsonObject) = McpCallResult(emptyList(), false)
            override fun close() = Unit
        }
    }

    private fun manager() = McpManager.forDesktop(
        home = home.path, engineStateDir = home.resolve("engine-state"),
        vault = { DesktopVault.open(home.path, passphrase = { "pw".toCharArray() }, forcePassphrase = true).vault },
        clientFactory = factory,
    )

    @AfterTest
    fun cleanUp() { home.deleteRecursively() }

    private val server = ParsedMcpServerConfig(name = "notes", transport = "stdio", command = "notes-mcp", args = listOf("--stdio"), envOrHeaders = mapOf("TOKEN" to "s3cret-value"))

    @Test
    fun `a server is registered, its secret goes to the vault, and its catalog is read from the live server`(): Unit = runBlocking {
        val m = manager()
        assertTrue(m.servers().isEmpty())
        m.register(server)

        val listed = m.servers().single()
        assertEquals("notes", listed.id)
        assertEquals("stdio", listed.transport)
        assertEquals(mapOf("TOKEN" to "mcp:notes:TOKEN"), listed.secretRefs, "the registration keeps a reference, not the value")
        val userDb = home.resolve(".aidos/user.db")
        assertFalse(userDb.readBytes().toString(Charsets.ISO_8859_1).contains("s3cret-value"), "the value is not in user.db")

        val catalog = m.catalog("notes")
        assertEquals(listOf("read_note", "delete_note"), catalog.map { it.name })
        assertTrue(catalog.none { it.isAdopted })
        assertEquals("s3cret-value", seenSecrets.single()["TOKEN"], "the broker resolved the secret from the vault for the server")
    }

    @Test
    fun `adoption is recorded per tool and reflected in the server list`(): Unit = runBlocking {
        val m = manager()
        m.register(server)
        assertFailsWith<IllegalStateException> { m.adopt("notes", "read_note") } // catalog not read yet
        m.catalog("notes")

        m.adopt("notes", "read_note")
        assertEquals(listOf(true, false), m.catalog("notes").map { it.isAdopted })
        assertEquals(1, m.servers().single().adoptedCount)

        m.unadopt("notes", "read_note")
        assertTrue(m.catalog("notes").none { it.isAdopted })
        assertEquals(0, m.servers().single().adoptedCount)

        m.delete("notes")
        assertTrue(m.servers().isEmpty())
    }

    @Test
    fun `an insecure remote endpoint is refused and a server without a command is invalid`(): Unit = runBlocking {
        val m = manager()
        assertFailsWith<Exception> { m.register(ParsedMcpServerConfig(name = "remote", transport = "http", endpointUrl = "http://example.com/mcp")) }
        assertFailsWith<IllegalArgumentException> { m.register(ParsedMcpServerConfig(name = "bad", transport = "stdio")) }
        assertTrue(m.servers().isEmpty())
    }

    @Test
    fun `the Tools presenter lists servers, loads a catalog and keeps adoption`(): Unit = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val host = DesktopEngineHost(modelsDir = home.resolve("m").apply { mkdirs() }, stateDir = home.resolve("s"), serverPort = { 0 }, discoveryFile = null)
        try {
            val model = DesktopMcpModel(host, scope, manager())
            fun eventually(what: String, c: () -> Boolean) { val end = System.currentTimeMillis() + 10_000; while (!c() && System.currentTimeMillis() < end) Thread.sleep(25); assertTrue(c(), what) }
            model.addServer(server)
            eventually("listed") { model.state.value.servers.any { it.id == "notes" } }
            model.selectServer("notes")
            eventually("catalog shown") { model.state.value.selectedCatalog.size == 2 && !model.state.value.isLoadingCatalog }
            model.toggleAdoption("notes", "read_note", true)
            eventually("adopted") { model.state.value.selectedCatalog.first { it.name == "read_note" }.isAdopted && model.state.value.servers.single().adoptedCount == 1 }
            model.adoptAll("notes")
            eventually("all adopted") { model.state.value.selectedCatalog.all { it.isAdopted } }
            model.unadoptAll("notes")
            eventually("none adopted") { model.state.value.selectedCatalog.none { it.isAdopted } }
            model.deleteServer("notes")
            eventually("removed") { model.state.value.servers.isEmpty() }
        } finally {
            scope.cancel(); host.close()
        }
    }
}
