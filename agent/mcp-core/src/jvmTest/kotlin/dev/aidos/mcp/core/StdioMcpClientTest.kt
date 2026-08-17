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

/**
 * [StdioMcpClient] against a real subprocess (`fake_mcp_stdio_server.py`), the same style M10's
 * `RealSocketIntegrationTest` uses for the socket transport: a mock proves the codec, a real
 * process proves the transport actually spawns, writes, and reads across a real stdin/stdout
 * pipe (RFC-0031 §Stdio Transport, M18).
 */
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
        } finally {
            client.close()
        }
    }

    @Test
    fun `tools_list returns the server's real catalog`() = runBlocking {
        val client = newClient()
        try {
            val tools = client.listTools()
            assertEquals(listOf("echo"), tools.map { it.name })
        } finally {
            client.close()
        }
    }

    @Test
    fun `tools_call round-trips a real request and response through the subprocess`() = runBlocking {
        val client = newClient()
        try {
            val result = client.callTool("echo", buildJsonObject { put("text", "hello from the test") })
            assertFalse(result.isError)
            assertEquals(listOf(McpContent.Text("hello from the test")), result.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `a tool-reported error is surfaced as isError, not an exception`() = runBlocking {
        val client = newClient()
        try {
            val result = client.callTool("fail", buildJsonObject {})
            assertTrue(result.isError)
        } finally {
            client.close()
        }
    }

    @Test
    fun `an unknown tool name returns a JSON-RPC error, not a crash`() = runBlocking {
        val client = newClient()
        try {
            val result = client.callTool("does-not-exist", buildJsonObject {})
            assertTrue(result.isError)
        } finally {
            client.close()
        }
    }

    @Test
    fun `an explicitly resolved secret_ref env var and PATH reach the child, an ambient marker does not`() = runBlocking {
        // scrubbedEnvironment()'s own filtering logic is proven directly in McpEnvironmentTest;
        // this proves the *wiring* -- that StdioMcpClient's ProcessBuilder actually applies it
        // rather than the ambient environment leaking through some other path (e.g. a forgotten
        // `environment().clear()`). The parent JVM process cannot itself set an ambient OS env
        // var at runtime (no public JDK API), so absence is proven the other way: this specific
        // JVM process was not launched with AIDOS_TEST_MARKER set, and the child must agree.
        val client = newClient(extraEnv = mapOf("MCP_SERVER_SECRET" to "resolved-from-vault"))
        try {
            client.initialize()
            val response = client.request("test/env", null)
            val obj = response.result?.jsonObject ?: fail("test/env returned no result")
            assertEquals(false, obj["has_marker"]?.jsonPrimitive?.content?.toBooleanStrict(), "an ambient var this process never set must not appear")
            assertEquals(true, obj["path_present"]?.jsonPrimitive?.content?.toBooleanStrict(), "PATH is allowlisted -- python3 itself needed it to even start")
            assertEquals(true, obj["has_secret"]?.jsonPrimitive?.content?.toBooleanStrict(), "an explicitly resolved secret_ref env var must reach the child")
        } finally {
            client.close()
        }
    }

    @Test
    fun `a request the server never answers times out rather than hanging forever`() = runBlocking {
        val client = StdioMcpClient(
            command = "python3", args = listOf(fakeServerPath()), requestTimeoutMillis = 500,
        )
        try {
            client.initialize()
            val result = withTimeoutOrNull(5_000) {
                runCatching { client.request("test/hang", null) }
            }
            assertTrue(result != null, "the client itself must not hang the test — the 500ms request timeout must fire")
            assertTrue(result.isFailure, "an unanswered request must fail, not return silently")
        } finally {
            client.close()
        }
    }

    @Test
    fun `closing the client terminates the subprocess`() = runBlocking {
        val client = newClient()
        client.initialize()
        client.close()
        // No public "isAlive" surface on McpClient by design (RFC-0031 gives callers a call
        // interface, not a process handle) -- a second call against the closed client failing
        // fast is the observable proof the transport is actually torn down, not hung.
        val result = runCatching { client.callTool("echo", buildJsonObject { put("text", "x") }) }
        assertTrue(result.isFailure, "a call after close() must fail, not hang or silently no-op")
    }
}
