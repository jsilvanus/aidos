package dev.aidos.mcp

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.DirEntry
import dev.aidos.kernel.DirHandle
import dev.aidos.kernel.RelPath
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.mcp.core.McpServerRegistration
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.mcp.core.McpTransport
import dev.aidos.mcp.core.StdioMcpClient
import java.io.File
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.fail

/**
 * [McpTool] against the same real fake stdio server `StdioMcpClientTest` (in `mcp-core`) uses —
 * proving the broker-facing `execute()` seam the audit found entirely missing actually drives a
 * real MCP call end to end, not just the descriptor-mapping half ([McpAdapterTest]).
 */
class McpToolTest {

    private fun fakeServerPath(): String {
        val url = Thread.currentThread().contextClassLoader.getResource("fake_mcp_stdio_server.py")
            ?: fail("fake_mcp_stdio_server.py not found on test classpath")
        return File(url.toURI()).absolutePath
    }

    // McpTool.execute() never touches `handle` -- MCP calls carry no filesystem scope of their
    // own (the capability grant is per-server, not per-file) -- so this fake only needs to
    // satisfy the sealed ResourceHandle hierarchy's one non-sealed member type.
    private val fakeHandle = object : DirHandle {
        override val capabilityId = CapabilityId("test")
        override suspend fun read(relative: RelPath): Result<ByteArray> = Result.failure(UnsupportedOperationException())
        override suspend fun write(relative: RelPath, content: ByteArray): Result<Unit> = Result.failure(UnsupportedOperationException())
        override suspend fun list(relative: RelPath): Result<List<DirEntry>> = Result.failure(UnsupportedOperationException())
        override suspend fun exists(relative: RelPath): Boolean = false
    }

    @Test
    fun `execute drives a real call through the transport and reports UNTRUSTED`() = runBlocking {
        val client = StdioMcpClient(command = "python3", args = listOf(fakeServerPath()))
        val registration = McpServerRegistration(
            serverId = "fake",
            transport = McpTransport.Stdio(command = "python3", args = listOf(fakeServerPath())),
            tools = listOf(McpToolSpec("echo", "echoes its input")),
        )
        val tool = McpTool(registration, client)
        try {
            val result = tool.execute(fakeHandle, "fake:echo", buildJsonObject { put("text", "through the tool seam") })
            assertIs<ToolOutcome.Ok>(result.outcome)
            assertEquals(TrustLevel.UNTRUSTED, result.trustLevel, "an MCP result is UNTRUSTED unconditionally (RFC-0027, D30)")
            assertEquals(listOf(ContentBlock.Text("through the tool seam")), result.content)
        } finally {
            client.close()
        }
    }

    @Test
    fun `a server-reported tool error becomes ToolOutcome_Failed, not a thrown exception`() = runBlocking {
        val client = StdioMcpClient(command = "python3", args = listOf(fakeServerPath()))
        val registration = McpServerRegistration(
            serverId = "fake",
            transport = McpTransport.Stdio(command = "python3", args = listOf(fakeServerPath())),
            tools = listOf(McpToolSpec("fail", "always fails")),
        )
        val tool = McpTool(registration, client)
        try {
            val result = tool.execute(fakeHandle, "fake:fail", buildJsonObject {})
            assertIs<ToolOutcome.Failed>(result.outcome)
            assertEquals(TrustLevel.UNTRUSTED, result.trustLevel)
        } finally {
            client.close()
        }
    }

    @Test
    fun `a crashed transport becomes ToolOutcome_Failed, not an unhandled throw`() = runBlocking {
        val client = StdioMcpClient(command = "python3", args = listOf(fakeServerPath()))
        val registration = McpServerRegistration(
            serverId = "fake",
            transport = McpTransport.Stdio(command = "python3", args = listOf(fakeServerPath())),
            tools = listOf(McpToolSpec("echo", "echoes its input")),
        )
        val tool = McpTool(registration, client)
        client.close() // simulate the transport already being gone
        val result = tool.execute(fakeHandle, "fake:echo", buildJsonObject { put("text", "x") })
        assertIs<ToolOutcome.Failed>(result.outcome)
        assertEquals(TrustLevel.UNTRUSTED, result.trustLevel)
    }
}
