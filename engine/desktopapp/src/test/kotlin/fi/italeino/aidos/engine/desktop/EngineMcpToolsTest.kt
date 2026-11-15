package fi.italeino.aidos.engine.desktop

import dev.aidos.mcp.McpClientFactory
import dev.aidos.mcp.core.McpCallResult
import dev.aidos.mcp.core.McpClient
import dev.aidos.mcp.core.McpContent
import dev.aidos.mcp.core.McpServerInfo
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.vault.DesktopVault
import fi.italeino.aidos.engine.ui.ParsedMcpServerConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** RFC-0115: the adopted MCP operations become the Engine agent loop's tools, run by the Engine, with approval for anything that may change things. */
class EngineMcpToolsTest {
    private val home = Files.createTempDirectory("aidos-engine-mcp-tools").toFile()
    private val scope = CoroutineScope(Dispatchers.Default)
    private val called = mutableListOf<Pair<String, JsonObject>>()
    private var failCalls = false

    private val factory = McpClientFactory { _, _ ->
        object : McpClient {
            override suspend fun initialize() = McpServerInfo("fake", "1")
            override suspend fun listTools() = listOf(
                McpToolSpec("read_note", "Reads a note", annotations = buildJsonObject { put("readOnlyHint", true) }),
                McpToolSpec("delete_note", "Deletes a note"),
            )
            override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
                if (failCalls) throw IllegalStateException("server went away")
                called += name to arguments
                return McpCallResult(listOf(McpContent.Text("done $name")), false)
            }
            override fun close() = Unit
        }
    }

    private fun manager() = McpManager.forDesktop(
        home = home.path, engineStateDir = home.resolve("state"),
        vault = { DesktopVault.open(home.path, passphrase = { "pw".toCharArray() }, forcePassphrase = true).vault },
        clientFactory = factory,
    )

    @AfterTest
    fun cleanUp() { scope.cancel(); home.deleteRecursively() }

    private suspend fun adopted(m: McpManager) {
        m.register(ParsedMcpServerConfig(name = "notes", transport = "stdio", command = "notes-mcp"))
        m.catalog("notes")
        m.adopt("notes", "read_note")
        m.adopt("notes", "delete_note")
    }

    @Test
    fun `adopted operations become Engine tools and only an adopted one can be called`(): Unit = runBlocking {
        val m = manager()
        assertTrue(EngineMcpTools(m).toolsFor("app").isEmpty(), "nothing is offered until something is adopted")
        m.register(ParsedMcpServerConfig(name = "notes", transport = "stdio", command = "notes-mcp"))
        m.catalog("notes")
        m.adopt("notes", "read_note")

        val tools = EngineMcpTools(m).toolsFor("app")
        assertEquals(listOf("notes:read_note"), tools.map { it.name })
        val read = tools.single()
        assertFalse(read.readOnly, "nothing is trusted to be read only: a server's own claim is not stored, so every call asks")

        val ok = read.execute(buildJsonObject { put("id", "1") })
        assertEquals("done read_note", ok.outputText)
        assertFalse(ok.isError)
        assertEquals(listOf("read_note"), called.map { it.first })

        val refused = m.callTool("notes", "delete_note", JsonObject(emptyMap()))
        assertTrue(refused.isError && refused.outputText.contains("not an adopted operation"), refused.outputText)
        assertEquals(1, called.size, "an operation that was not adopted never reached the server")
    }

    @Test
    fun `every adopted operation asks first, and a failing server is an error result`(): Unit = runBlocking {
        val m = manager()
        adopted(m)
        val tools = EngineMcpTools(m).toolsFor("app").associateBy { it.name }
        assertTrue(tools.values.none { it.readOnly })
        failCalls = true
        val r = tools.getValue("notes:read_note").execute(JsonObject(emptyMap()))
        assertTrue(r.isError && r.outputText.contains("server went away"), r.outputText)
    }

    @Test
    fun `a pending Engine tool approval shows in the approvals feed and the answer reaches the loop`(): Unit = runBlocking {
        val host = DesktopEngineHost(modelsDir = home.resolve("m").apply { mkdirs() }, stateDir = home.resolve("s"), serverPort = { 0 }, discoveryFile = null)
        try {
            host.start()
            val runtime = assertNotNull(host.agentRuntime)
            val answer = scope.async { runtime.engineToolApprovals.request("dev.test.app", "notes:delete_note") }
            val control = LocalApprovalsControl(host)
            var item = control.pending().firstOrNull { it.id.startsWith("etool:") }
            val end = System.currentTimeMillis() + 5000
            while (item == null && System.currentTimeMillis() < end) { Thread.sleep(20); item = control.pending().firstOrNull { it.id.startsWith("etool:") } }
            assertNotNull(item)
            assertEquals("dev.test.app wants to run notes:delete_note", item.title)
            assertTrue(control.decide(item.id, true))
            assertTrue(answer.await())
            assertTrue(control.pending().none { it.id.startsWith("etool:") })
            assertFalse(control.decide(item.id, true), "an answered item is gone")
        } finally {
            host.close()
        }
    }
}
