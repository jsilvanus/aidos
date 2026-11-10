package fi.italeino.aidos.engine.agent

import dev.aidos.aidin.LinkMessage
import dev.aidos.aidin.NodeId
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import fi.italeino.aidos.engine.inference.Locality
import fi.italeino.aidos.engine.inference.LocalityViolationException
import fi.italeino.aidos.engine.inference.Placement
import fi.italeino.aidos.engine.inference.ResolvedCandidateRoute
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RoutedModelAdapterTest {
    private val peer = NodeId("abcdefghijklmnopqrstuvwxyz")
    private val request = ModelRequest(
        messages = listOf(Turn.System("be brief"), Turn.User(listOf(ContentBlock.Text("hi")), TrustLevel.TRUSTED)),
        tools = emptyList(),
        toolChoice = ToolChoice.None,
        maxOutputTokens = 64,
    )

    private fun localAnswer(text: String) =
        Result.success(ModelResponse(listOf(TextOutput(text)), StopReason.END_TURN, null, null))

    private fun chatBody(text: String? = null, toolName: String? = null) =
        if (toolName != null) {
            """{"id":"x","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"","tool_calls":[{"id":"c1","type":"function","function":{"name":"$toolName","arguments":"{\"a\":1}"}}]},"finish_reason":"tool_calls"}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}"""
        } else {
            """{"id":"x","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"$text"},"finish_reason":"stop"}],"usage":{"prompt_tokens":3,"completion_tokens":4,"total_tokens":7}}"""
        }

    private fun adapter(
        routes: List<ResolvedCandidateRoute>,
        locality: Locality = Locality.TRUSTED_CLUSTER,
        resolve: (suspend (String, Locality) -> List<ResolvedCandidateRoute>)? = null,
        local: suspend (String, Locality, ModelRequest) -> Result<ModelResponse> = { _, _, _ -> localAnswer("local") },
        peerCall: suspend (NodeId, String, String, Locality, Long?) -> LinkMessage.DelegateResponse =
            { _, _, _, _, _ -> LinkMessage.DelegateResponse("r", 200, chatBody("from peer")) },
        quarantined: MutableList<String> = mutableListOf(),
        placements: MutableList<Placement> = mutableListOf(),
    ) = RoutedModelAdapter(
        resolveRoutes = resolve ?: { _, _ -> routes },
        callLocal = local,
        callPeer = peerCall,
        quarantine = { quarantined += it },
        modelId = "aidos:chat",
        locality = locality,
        onPlacementObserved = { placements += it },
    )

    private val peerRoute = ResolvedCandidateRoute.PeerDelegation(peer, "qwen-14b", Placement.CLUSTER)
    private val localRoute = ResolvedCandidateRoute.LocalModel("qwen-3b", Placement.LOCAL)

    @Test
    fun `a peer route sends the loop's request to the peer and reads its answer`() = runBlocking {
        var endpoint = ""; var body = ""; var seenLocality: Locality? = null
        val placements = mutableListOf<Placement>()
        val a = adapter(listOf(peerRoute), placements = placements, peerCall = { _, e, b, l, _ ->
            endpoint = e; body = b; seenLocality = l
            LinkMessage.DelegateResponse("r", 200, chatBody("from peer"))
        })
        val r = a.invoke(request).getOrThrow()
        assertEquals("from peer", (r.outputs.single() as TextOutput).text)
        assertEquals("/aidin/v1/chat/completions", endpoint)
        assertTrue("\"model\":\"qwen-14b\"" in body && "be brief" in body && "\"hi\"" in body, body)
        assertEquals(Locality.TRUSTED_CLUSTER, seenLocality)
        assertEquals(listOf(Placement.CLUSTER), placements)
        assertEquals(4, r.usage?.outputTokens)
    }

    @Test
    fun `a peer tool call comes back as a tool call for the loop`() = runBlocking {
        val a = adapter(listOf(peerRoute), peerCall = { _, _, _, _, _ -> LinkMessage.DelegateResponse("r", 200, chatBody(toolName = "lookup")) })
        val r = a.invoke(request).getOrThrow()
        val call = (r.outputs.single() as ToolCallOutput).call
        assertEquals("lookup", call.toolName)
        assertEquals("c1", call.callId)
        assertEquals(StopReason.TOOL_USE, r.stopReason)
    }

    @Test
    fun `a failing peer is quarantined and the next route answers`() = runBlocking {
        val q = mutableListOf<String>()
        val placements = mutableListOf<Placement>()
        val a = adapter(
            listOf(peerRoute, localRoute), quarantined = q, placements = placements,
            peerCall = { _, _, _, _, _ -> LinkMessage.DelegateResponse("r", 503, "secret prompt echo") },
        )
        val r = a.invoke(request).getOrThrow()
        assertEquals("local", (r.outputs.single() as TextOutput).text)
        assertEquals(listOf("peer:${peer.value}/qwen-14b"), q)
        assertEquals(listOf(Placement.LOCAL), placements)
    }

    @Test
    fun `a peer error does not leak its body into the failure`() = runBlocking {
        val a = adapter(listOf(peerRoute), peerCall = { _, _, _, _, _ -> LinkMessage.DelegateResponse("r", 500, "echoed: my secret prompt") })
        val e = a.invoke(request).exceptionOrNull()
        assertTrue(e is PeerModelException && "secret" !in e.message.orEmpty(), "got: $e")
    }

    @Test
    fun `a failed local model is not retried elsewhere`() = runBlocking {
        var peerCalled = false
        val a = adapter(
            listOf(localRoute, peerRoute),
            local = { _, _, _ -> Result.failure(IllegalStateException("model crashed")) },
            peerCall = { _, _, _, _, _ -> peerCalled = true; LinkMessage.DelegateResponse("r", 200, chatBody("x")) },
        )
        assertTrue(a.invoke(request).isFailure)
        assertTrue(!peerCalled, "a local failure must not silently move the prompt to another device")
    }

    @Test
    fun `the locality limit is the router's call and its refusal surfaces unchanged`() = runBlocking {
        var peerCalled = false
        val a = adapter(
            emptyList(), locality = Locality.LOCAL_ONLY,
            resolve = { id, loc -> throw LocalityViolationException(id, loc, Placement.CLUSTER) },
            peerCall = { _, _, _, _, _ -> peerCalled = true; LinkMessage.DelegateResponse("r", 200, chatBody("x")) },
        )
        assertTrue(a.invoke(request).exceptionOrNull() is LocalityViolationException)
        assertTrue(!peerCalled)
    }

    @Test
    fun `a split anchor route is skipped, not answered without tools`() = runBlocking {
        val a = adapter(listOf(ResolvedCandidateRoute.LocalSplitAnchor("big", Placement.CLUSTER, 1), localRoute))
        assertEquals("local", (a.invoke(request).getOrThrow().outputs.single() as TextOutput).text)
    }

    @Test
    fun `no usable route is a failure`() = runBlocking {
        assertFailsWith<IllegalStateException> { adapter(emptyList()).invoke(request).getOrThrow() }
        Unit
    }
}
