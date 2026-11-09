package dev.aidos.mcp

import dev.aidos.kernel.BasicResourceHandle
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ToolOutcome
import dev.aidos.mcp.core.McpCallResult
import dev.aidos.mcp.core.McpClient
import dev.aidos.mcp.core.McpContent
import dev.aidos.mcp.core.McpServerInfo
import dev.aidos.mcp.core.McpServerRegistration
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.mcp.core.McpTransport
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class McpLifecycleManagerTest {

    private val handle = BasicResourceHandle(CapabilityId("cap-1"))

    private class ControllableMcpClient(
        private val catalog: List<McpToolSpec>,
        private val callGate: CompletableDeferred<Unit>? = null,
        private val onCallEntered: (() -> Unit)? = null,
    ) : McpClient {
        var closedCount = 0
            private set

        override suspend fun initialize(): McpServerInfo = McpServerInfo("controllable", "1.0.0")
        override suspend fun listTools(): List<McpToolSpec> = catalog
        override suspend fun callTool(name: String, arguments: JsonObject): McpCallResult {
            onCallEntered?.invoke()
            callGate?.await()
            return McpCallResult(
                content = listOf(McpContent.Text("ok:$name")),
                isError = false,
            )
        }
        override fun close() {
            closedCount++
        }
        override suspend fun closeSuspend() {
            closedCount++
        }
    }

    private val adoptedSpec = McpToolSpec(
        name = "ping",
        description = "Pings the server",
        inputSchema = buildJsonObject { put("type", "object") },
    )

    private fun activationFor(serverId: String, client: McpClient): McpActivationOutcome {
        val reg = McpServerRegistration(
            serverId = serverId,
            transport = McpTransport.Stdio("cmd", emptyList()),
            tools = listOf(adoptedSpec),
        )
        return McpActivationOutcome.Activated(
            McpServerActivation(
                tool = McpTool(reg, client),
                unadopted = emptyList(),
                client = client,
            )
        )
    }

    @Test
    fun `lazy start, idle release after timeout, and transparent re-activation on next call`() = runTest {
        var now = 1_000L
        var activations = 0
        val createdClients = mutableListOf<ControllableMcpClient>()

        val lazyTool = LazyMcpTool(
            serverId = "srv-1",
            projectId = "proj-1",
            initialAdoptedSpecs = listOf(adoptedSpec),
            transport = McpTransport.Stdio("cmd", emptyList()),
            idleTimeoutMillis = 60_000L,
            clockMillis = { now },
            activator = {
                activations++
                val client = ControllableMcpClient(listOf(adoptedSpec))
                createdClients.add(client)
                activationFor("srv-1", client)
            },
        )

        val manager = McpLifecycleManager(clockMillis = { now })
        manager.register(lazyTool)

        // 1. Before any tool call: descriptors are available from storage without starting the server.
        assertEquals(1, lazyTool.operations().size)
        assertFalse(lazyTool.isConnected)
        assertTrue(manager.connectedServerIds().isEmpty())
        assertEquals(0, activations)

        // 2. First tool call activates the server lazily.
        val firstResult = lazyTool.execute(handle, "srv-1:ping", buildJsonObject {})
        assertIs<ToolOutcome.Ok>(firstResult.outcome)
        assertEquals(listOf(ContentBlock.Text("ok:ping")), firstResult.content)
        assertTrue(lazyTool.isConnected)
        assertEquals(setOf("srv-1"), manager.connectedServerIds())
        assertEquals(1, activations)

        // 3. Sweep before idle timeout does NOT release the server.
        now = 30_000L
        assertTrue(manager.sweepIdle().isEmpty())
        assertTrue(lazyTool.isConnected)
        assertEquals(0, createdClients[0].closedCount)

        // 4. Sweep after idle timeout releases the connection while preserving descriptors.
        now = 61_001L
        val released = manager.sweepIdle()
        assertEquals(listOf("srv-1"), released)
        assertFalse(lazyTool.isConnected)
        assertEquals(1, createdClients[0].closedCount)
        assertEquals(1, lazyTool.operations().size, "adopted descriptors remain available while idle-released")

        // 5. Subsequent call transparently re-activates the server.
        now = 70_000L
        val secondResult = lazyTool.execute(handle, "srv-1:ping", buildJsonObject {})
        assertIs<ToolOutcome.Ok>(secondResult.outcome)
        assertEquals(listOf(ContentBlock.Text("ok:ping")), secondResult.content)
        assertTrue(lazyTool.isConnected)
        assertEquals(2, activations)
        assertEquals(0, createdClients[1].closedCount)

        // 6. closeAllSuspend shuts down all active connections cleanly.
        manager.closeAllSuspend()
        assertFalse(lazyTool.isConnected)
        assertEquals(1, createdClients[1].closedCount)
    }

    @Test
    fun `sweepIdle never releases a server while a tool call is in flight`() = runTest {
        var now = 1_000L
        val callEntered = CompletableDeferred<Unit>()
        val callGate = CompletableDeferred<Unit>()
        val client = ControllableMcpClient(
            catalog = listOf(adoptedSpec),
            callGate = callGate,
            onCallEntered = { callEntered.complete(Unit) },
        )

        val lazyTool = LazyMcpTool(
            serverId = "srv-busy",
            projectId = "proj-1",
            initialAdoptedSpecs = listOf(adoptedSpec),
            transport = McpTransport.Stdio("cmd", emptyList()),
            idleTimeoutMillis = 10_000L,
            clockMillis = { now },
            activator = { activationFor("srv-busy", client) },
        )
        val manager = McpLifecycleManager(clockMillis = { now })
        manager.register(lazyTool)

        val inFlight = async {
            lazyTool.execute(handle, "srv-busy:ping", buildJsonObject {})
        }
        callEntered.await()
        assertEquals(1, lazyTool.inFlightCount)

        // Advance clock well past idle timeout while the call is still in flight.
        now = 100_000L
        assertTrue(manager.sweepIdle().isEmpty(), "in-flight call must prevent idle release")
        assertTrue(lazyTool.isConnected)
        assertEquals(0, client.closedCount)

        // Complete the call; lastActivityMillis is refreshed to 100_000L on completion.
        callGate.complete(Unit)
        val completedResult = inFlight.await()
        assertIs<ToolOutcome.Ok>(completedResult.outcome)
        assertEquals(listOf(ContentBlock.Text("ok:ping")), completedResult.content)
        assertEquals(0, lazyTool.inFlightCount)

        // Immediately after completion, not yet idle; after timeout from completion, released.
        now = 105_000L
        assertTrue(manager.sweepIdle().isEmpty())
        now = 110_001L
        assertEquals(listOf("srv-busy"), manager.sweepIdle())
        assertFalse(lazyTool.isConnected)
        assertEquals(1, client.closedCount)
    }
}
