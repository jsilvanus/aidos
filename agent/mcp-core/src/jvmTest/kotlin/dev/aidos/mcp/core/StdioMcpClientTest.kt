package dev.aidos.mcp.core

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class StdioMcpClientTest {
    private fun fakeServerPath(): String {
        val url = Thread.currentThread().contextClassLoader.getResource("fake_mcp_stdio_server.py")
            ?: fail("fake_mcp_stdio_server.py not found on test classpath")
        return File(url.toURI()).absolutePath
    }

    private fun newClient(extraEnv: Map<String, String> = emptyMap()) =
        StdioMcpClient(command = "python3", args = listOf(fakeServerPath()), extraEnv = extraEnv, requestTimeoutMillis = 5_000)

    @Test
    fun `initialize returns the server's advertised info over a real subprocess`() = runBlocking {
        val client = newClient()
        try {
            val info = client.initialize()
            assertEquals("fake-mcp-server", info.name)
            assertEquals("0.0.1", info.version)
        } finally { client.close() }
    }

    @Test
    fun `tools_list returns the server's real catalog`() = runBlocking {
        val client = newClient()
        try { assertEquals(listOf("echo", "env", "hang"), client.listTools().map { it.name }) }
        finally { client.close() }
    }

    @Test
    fun `tools_call round-trips a real request and response through the subprocess`() = runBlocking {
        val client = newClient()
        try {
            val result = client.callTool("echo", buildJsonObject { put("text", "hello from the test") })
            assertFalse(result.isError)
            assertEquals(listOf(McpContent.Text("hello from the test")), result.content)
        } finally { client.close() }
    }

    @Test
    fun `a tool-reported error is surfaced as isError, not an exception`() = runBlocking {
        val client = newClient()
        try { assertTrue(client.callTool("fail", buildJsonObject {}).isError) }
        finally { client.close() }
    }

    @Test
    fun `an unknown tool name returns a JSON-RPC error, not a crash`() = runBlocking {
        val client = newClient()
        try { assertTrue(client.callTool("does-not-exist", buildJsonObject {}).isError) }
        finally { client.close() }
    }

    @Test
    fun `an explicitly resolved secret_ref env var and PATH reach the child, an ambient marker does not`() = runBlocking {
        val client = newClient(extraEnv = mapOf("MCP_SERVER_SECRET" to "resolved-from-vault"))
        try {
            client.initialize()
            val result = client.callTool("env", buildJsonObject {})
            assertFalse(result.isError)
            val obj = (result.content.single() as McpContent.Text).text.let { mcpJson.parseToJsonElement(it).jsonObject }
            assertEquals(false, obj["has_marker"]?.jsonPrimitive?.content?.toBooleanStrict())
            assertEquals(true, obj["path_present"]?.jsonPrimitive?.content?.toBooleanStrict())
            assertEquals(true, obj["has_secret"]?.jsonPrimitive?.content?.toBooleanStrict())
        } finally { client.close() }
    }

    @Test
    fun `a tool the server never answers times out rather than hanging forever`() = runBlocking {
        val client = StdioMcpClient(command = "python3", args = listOf(fakeServerPath()), requestTimeoutMillis = 500)
        try {
            client.initialize()
            val result = withTimeoutOrNull(5_000) {
                runCatching { client.callTool("hang", buildJsonObject {}) }
            }
            assertTrue(result != null, "the client itself must not hang the test")
            assertTrue(result.isFailure, "an unanswered tool call must fail")
        } finally { client.close() }
    }

    @Test
    fun `closing the client terminates the subprocess`() = runBlocking {
        val client = newClient()
        client.initialize()
        client.close()
        val result = runCatching { client.callTool("echo", buildJsonObject { put("text", "x") }) }
        assertTrue(result.isFailure, "a call after close() must fail")
    }
}
