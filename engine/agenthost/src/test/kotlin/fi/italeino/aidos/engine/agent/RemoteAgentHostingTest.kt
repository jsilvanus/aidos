package fi.italeino.aidos.engine.agent

import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.InMemoryAidinStateStore
import dev.aidos.aidin.NodeId
import dev.aidos.aidin.PairingStatus
import dev.aidos.aidin.PeerTracker
import dev.aidos.aidin.Scope
import dev.aidos.aidin.SigningKey
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.Turn
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Calls the tool named [tool] once, then answers with what it returned. */
private class ToolCallingAdapter(private val tool: String, private val secondTool: String? = null) : ModelAdapter {
    override val providerId = "test"
    override val modelId = "scripted"
    override val modelVersion = "1"
    override val contextWindow = 4096
    override val isLocal = true
    override fun supportsNativeToolCalls() = true
    val localities = CopyOnWriteArrayList<Int>()

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        val last = request.messages.last()
        val toolResults = request.messages.count { it is Turn.ToolResult }
        return Result.success(
            when {
                last is Turn.ToolResult && secondTool != null && toolResults == 1 ->
                    ModelResponse(listOf(ToolCallOutput(ToolCall("m2", secondTool, buildJsonObject { put("q", "y") }, null))), StopReason.TOOL_USE, null, null)
                last is Turn.ToolResult -> {
                    val text = last.result.content.joinToString { (it as ContentBlock.Text).text }
                    ModelResponse(listOf(TextOutput("tool said: $text")), StopReason.END_TURN, null, null)
                }
                else -> ModelResponse(listOf(ToolCallOutput(ToolCall("m1", tool, buildJsonObject { put("q", "x") }, null))), StopReason.TOOL_USE, null, null)
            },
        )
    }
}

/** RFC-0107 phase 3 over a real AIDIN peer link between two nodes on loopback. */
class RemoteAgentHostingTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nodes = mutableListOf<AidinNode>()

    @AfterTest
    fun tearDown() {
        nodes.forEach { runCatching { it.stop() } }
        scope.cancel()
    }

    private fun node() = AidinNode(
        SigningKey.generate(), InMemoryAidinStateStore(), scope, engineVersion = "test",
        tracker = PeerTracker(heartbeatIntervalMillis = 100, leaveAfterMillis = 2_000),
    ).also { it.start("127.0.0.1"); nodes += it }

    private fun eventually(what: String, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline) { if (check()) return; Thread.sleep(20) }
        throw AssertionError("timed out waiting for: $what")
    }

    private class Pair2(val origin: AidinNode, val host: AidinNode, val hostRuntime: AgentRuntime, val adapter: ToolCallingAdapter)

    /** origin and host paired with default scopes (no agent scope); the host serves peer agent endpoints. */
    private fun pairedHost(adapter: ToolCallingAdapter): Pair2 {
        val origin = node()
        val host = node()
        origin.pairWith(host.createInvite(listOf("127.0.0.1")))
        eventually("pairings shown") { origin.pairingViews.value.isNotEmpty() && host.pairingViews.value.isNotEmpty() }
        origin.confirmPairing(origin.pairingViews.value.single().id, "host")
        host.confirmPairing(host.pairingViews.value.single().id, "origin")
        eventually("paired and linked") {
            origin.pairingViews.value.single().status == PairingStatus.DONE && host.pairingViews.value.single().status == PairingStatus.DONE &&
                origin.peers.value.any { it.connected } && host.peers.value.any { it.connected }
        }
        val runtime = AgentRuntime(scope = scope, adapterFor = { adapter })
        val endpoints = AgentPeerEndpoints(runtime)
        host.delegationHandler = { from, req, _ -> endpoints.handle(from, req) }
        return Pair2(origin, host, runtime, adapter)
    }

    private fun lookupTool(readOnly: Boolean = true, name: String = "lookup") = ClientToolSpec(name, "look something up", readOnly = readOnly)
    private fun request(vararg tools: ClientToolSpec) = AgentTurnRequest(model = "scripted", message = "go", tools = tools.toList())

    private fun client(p: Pair2, allowed: Set<String>?, audit: MutableList<OriginToolAudit> = mutableListOf(), approve: Boolean = false) =
        RemoteAgentClient(
            node = { p.origin }, peer = p.host.nodeId, scope = scope,
            toolHost = OriginToolHost(allowedTools = { _, _ -> allowed }, approve = { _, _, _ -> approve }, audit = { audit += it }),
        )

    @Test
    fun `agent scope is not granted by pairing, so the host refuses the peer until it is switched on`() = runBlocking {
        val p = pairedHost(ToolCallingAdapter("lookup"))
        val remote = client(p, setOf("lookup"))
        val denied = assertFailsWith<AgentRequestException> { remote.start("app-a", request(lookupTool())) }
        assertEquals("agent_scope_not_granted", denied.code)
        assertTrue(p.hostRuntime.listAll().isEmpty(), "nothing ran on the host")

        p.host.setPairScopes(p.origin.nodeId, Scope.ALL + Scope.AGENT)
        assertTrue(p.host.authorize(p.origin.nodeId, Scope.AGENT).isAllowed)
        val ok = remote.start("app-a", request(lookupTool()))
        assertEquals(AgentTurnResponse.TOOL_CALLS, ok.status)
    }

    @Test
    fun `the loop runs on the host while the tool executes on the origin app`() = runBlocking {
        val p = pairedHost(ToolCallingAdapter("lookup"))
        p.host.setPairScopes(p.origin.nodeId, Scope.ALL + Scope.AGENT)
        val audit = mutableListOf<OriginToolAudit>()
        val remote = client(p, setOf("lookup"), audit)

        val stop = remote.start("app-a", request(lookupTool()))
        assertEquals(AgentTurnResponse.TOOL_CALLS, stop.status)
        assertEquals("lookup", stop.toolCalls.single().name)
        // The host's run is owned by (peer, app), not by a local app named app-a.
        assertEquals(0, p.hostRuntime.list("app-a").size)
        assertTrue(p.hostRuntime.listAll().single().owner.startsWith("peer:${p.origin.nodeId.value}:app-a"))
        // The app (origin side) executes its own tool and hands back the result.
        val done = remote.submit("app-a", stop.runId!!, listOf(ClientToolResult(stop.toolCalls.single().callId, "42")))
        assertEquals(AgentTurnResponse.COMPLETED, done.status)
        assertEquals("tool said: 42", done.reply)
        assertEquals(listOf(OriginToolAudit.FORWARDED), audit.map { it.outcome })
    }

    @Test
    fun `only allowlisted tools are offered and the peer cannot ask for anything else`() = runBlocking {
        val p = pairedHost(ToolCallingAdapter("delete_everything"))
        p.host.setPairScopes(p.origin.nodeId, Scope.ALL + Scope.AGENT)
        val audit = mutableListOf<OriginToolAudit>()
        // The app declares two tools; the user allowed only `lookup`.
        val remote = client(p, setOf("lookup"), audit)
        val r = remote.start("app-a", request(lookupTool(), lookupTool(name = "delete_everything", readOnly = false)))
        // The host's model asked for a tool that was never offered to it; the host reports it as unavailable, the app never sees it.
        assertTrue(r.toolCalls.none { it.name == "delete_everything" })
        assertTrue(audit.none { it.outcome == OriginToolAudit.FORWARDED && it.tool == "delete_everything" })
    }

    @Test
    fun `a tainted remote run needs approval for effectful tools, decided at the origin`() = runBlocking {
        val p = pairedHost(ToolCallingAdapter("lookup", secondTool = "write_file"))
        p.host.setPairScopes(p.origin.nodeId, Scope.ALL + Scope.AGENT)
        val audit = mutableListOf<OriginToolAudit>()
        val remote = client(p, setOf("lookup", "write_file"), audit, approve = false)

        val first = remote.start("app-a", request(lookupTool(), lookupTool(readOnly = false, name = "write_file")))
        assertEquals("lookup", first.toolCalls.single().name) // read-only on an untainted run: straight through
        // Submitting the result taints the run; the model's next call is the effectful write_file.
        val after = remote.submit("app-a", first.runId!!, listOf(ClientToolResult(first.toolCalls.single().callId, "data")))
        // Refused at the origin without bothering the app: the loop got an error and finished.
        assertEquals(AgentTurnResponse.COMPLETED, after.status)
        assertTrue(audit.any { it.tool == "write_file" && it.outcome == OriginToolAudit.REFUSED_NEEDS_APPROVAL })
        assertTrue(audit.none { it.tool == "write_file" && it.outcome == OriginToolAudit.FORWARDED })
    }

    @Test
    fun `an approved effectful call on a tainted run is forwarded to the app`() = runBlocking {
        val p = pairedHost(ToolCallingAdapter("lookup", secondTool = "write_file"))
        p.host.setPairScopes(p.origin.nodeId, Scope.ALL + Scope.AGENT)
        val audit = mutableListOf<OriginToolAudit>()
        val remote = client(p, setOf("lookup", "write_file"), audit, approve = true)
        val first = remote.start("app-a", request(lookupTool(), lookupTool(readOnly = false, name = "write_file")))
        val second = remote.submit("app-a", first.runId!!, listOf(ClientToolResult(first.toolCalls.single().callId, "data")))
        assertEquals("write_file", second.toolCalls.single().name)
        assertTrue(audit.any { it.tool == "write_file" && it.outcome == OriginToolAudit.APPROVED })
    }

    @Test
    fun `another app cannot read or steer the run`(): Unit = runBlocking {
        val p = pairedHost(ToolCallingAdapter("lookup"))
        p.host.setPairScopes(p.origin.nodeId, Scope.ALL + Scope.AGENT)
        val remote = client(p, setOf("lookup"))
        val r = remote.start("app-a", request(lookupTool()))
        assertFailsWith<AgentRequestException> { remote.get("app-b", r.runId!!) }
        assertEquals(false, remote.cancel("app-b", r.runId!!))
        assertNotNull(remote.get("app-a", r.runId!!))
    }

    @Test
    fun `routing prefers the peer only when the locality allows the cluster, and falls back to local when the peer is gone`(): Unit = runBlocking {
        val p = pairedHost(ToolCallingAdapter("lookup"))
        p.host.setPairScopes(p.origin.nodeId, Scope.ALL + Scope.AGENT)
        val localRuntime = AgentRuntime(scope = scope, adapterFor = { ToolCallingAdapter("lookup") })
        val remote = client(p, setOf("lookup"))
        val routed = RoutedAgentRuntime(
            local = localRuntime,
            candidates = { listOf(AgentCandidate.Peer(p.host.nodeId), AgentCandidate.Local) },
            remoteFor = { if (it == p.host.nodeId) remote else null },
        )
        // local_only never reaches a peer (a loop on a peer is cluster placement).
        val localOnly = routed.start("app-a", request(lookupTool()).copy(locality = "local_only"))
        assertEquals(1, localRuntime.listAll().size)
        assertTrue(p.hostRuntime.listAll().isEmpty())
        assertNotNull(localOnly.runId)

        val cluster = routed.start("app-a", request(lookupTool()).copy(locality = "trusted_cluster"))
        assertEquals(1, p.hostRuntime.listAll().size, "trusted_cluster allows the peer")
        // The run's id routes back to the host that owns it.
        val done = routed.submit("app-a", cluster.runId!!, listOf(ClientToolResult(cluster.toolCalls.single().callId, "7")))
        assertEquals("tool said: 7", done.reply)

        // Peer disappears: the next turn falls back to local, never errors and never widens.
        p.host.stop()
        eventually("link drops") { p.origin.peers.value.none { it.connected } }
        val fallback = routed.start("app-a", request(lookupTool()).copy(locality = "trusted_cluster"))
        assertEquals(2, localRuntime.listAll().size)
        assertNotNull(fallback.runId)
    }
}
