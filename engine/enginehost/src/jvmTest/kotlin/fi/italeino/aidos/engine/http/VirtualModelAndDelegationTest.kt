package fi.italeino.aidos.engine.http

import dev.aidos.aidin.AIDIN_PROTOCOL_VERSION
import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.Heartbeat
import dev.aidos.aidin.InMemoryAidinStateStore
import dev.aidos.aidin.LinkMessage
import dev.aidos.aidin.ModelAdvert
import dev.aidos.aidin.NodeId
import dev.aidos.aidin.PairingStatus
import dev.aidos.aidin.PeerTracker
import dev.aidos.aidin.RouteInfo
import dev.aidos.aidin.SigningKey
import dev.aidos.aidin.Keys
import dev.aidos.aidin.nodeId
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.ModelStreamEvent
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Turn
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.Usage
import dev.aidos.modelruntime.GlobalModelRuntime
import dev.aidos.modelruntime.InferenceBackend
import dev.aidos.modelruntime.ModelRunSettings
import fi.italeino.aidos.engine.inference.HOP_HEADER
import fi.italeino.aidos.engine.inference.LOCALITY_HEADER
import fi.italeino.aidos.engine.inference.Locality
import fi.italeino.aidos.engine.inference.PLACEMENT_HEADER
import fi.italeino.aidos.engine.inference.Placement
import fi.italeino.aidos.engine.inference.ROUTE_HEADER
import fi.italeino.aidos.engine.inference.VirtualCandidate
import fi.italeino.aidos.engine.inference.VirtualModelConfig
import fi.italeino.aidos.engine.inference.originPlacementForPeerRoute
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

private val json = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

private class TagModelAdapter(
    override val modelId: String,
    private val replyPrefix: String,
    override val isLocal: Boolean = true,
) : ModelAdapter {
    override val providerId: String = "test"
    override val modelVersion: String = "1.0"
    override val contextWindow: Int = 4096
    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        val userText = request.messages.filterIsInstance<Turn.User>()
            .flatMap { it.content }
            .filterIsInstance<ContentBlock.Text>()
            .joinToString(" ") { it.text }
        return Result.success(
            ModelResponse(
                outputs = listOf(TextOutput("$replyPrefix:$userText")),
                stopReason = StopReason.END_TURN,
                usage = Usage(inputTokens = 2, outputTokens = 4, totalTokens = 6),
                model = ModelRef(id = modelId, version = modelVersion),
            )
        )
    }

    override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        val resp = invoke(request).getOrThrow()
        val text = resp.outputs.filterIsInstance<TextOutput>().joinToString("") { it.text }
        emit(ModelStreamEvent.Delta(text))
        emit(ModelStreamEvent.Done(resp))
    }
}

private class TestRuntime(
    private val descriptors: List<ModelDescriptor>,
    private val prefix: String,
) : ModelRuntime {
    val invokedModels = mutableListOf<String>()
    private val loadedSet = mutableSetOf<String>()

    override suspend fun catalog(): List<ModelDescriptor> = descriptors
    override suspend fun installed(): List<ModelDescriptor> = descriptors
    override suspend fun load(modelId: String): Result<ModelAdapter> {
        val desc = descriptors.firstOrNull { it.id == modelId }
            ?: return Result.failure(IllegalArgumentException("MODEL_NOT_INSTALLED: $modelId"))
        loadedSet += modelId
        invokedModels += modelId
        return Result.success(TagModelAdapter(modelId, "$prefix[$modelId]", desc.isLocal))
    }

    override suspend fun delete(modelId: String) {
        loadedSet -= modelId
    }

    override suspend fun unload(modelId: String) {
        loadedSet -= modelId
    }

    override fun loaded(): List<String> = loadedSet.toList()
}

/**
 * RFC-0105 Phase 2 & 3 tests for `aidos:chat` virtual model resolution, locality-first filtering,
 * peer delegation over mTLS `PeerLink`, origin split-route trust verification, mid-request fallback,
 * and warm local fallback pinning.
 */
class VirtualModelAndDelegationTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val nodes = mutableListOf<AidinNode>()

    @AfterTest
    fun tearDown() {
        nodes.forEach { runCatching { it.stop() } }
        scope.cancel()
    }

    private fun eventually(what: String, timeoutMillis: Long = 10_000, check: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMillis
        while (System.currentTimeMillis() < deadline) {
            if (check()) return
            Thread.sleep(20)
        }
        throw AssertionError("timed out waiting for: $what")
    }

    private fun createNode(models: List<ModelAdvert> = emptyList(), draining: () -> Boolean = { false }): AidinNode =
        AidinNode(
            key = SigningKey.generate(),
            stateStore = InMemoryAidinStateStore(),
            scope = scope,
            engineVersion = "test",
            tracker = PeerTracker(heartbeatIntervalMillis = 80, leaveAfterMillis = 2_000),
            heartbeatContent = {
                Heartbeat(
                    protocolVersion = AIDIN_PROTOCOL_VERSION,
                    engineVersion = "test",
                    contributionRamBytes = 8_000_000_000L,
                    charging = true,
                    batteryPercent = 100,
                    draining = draining(),
                    models = models,
                )
            },
        ).also {
            it.start("127.0.0.1")
            nodes += it
        }

    private fun pairNodes(a: AidinNode, b: AidinNode) {
        val invite = b.createInvite(listOf("127.0.0.1"))
        a.pairWith(invite)
        eventually("pairing views") { a.pairingViews.value.isNotEmpty() && b.pairingViews.value.isNotEmpty() }
        val aPairing = a.pairingViews.value.last()
        val bPairing = b.pairingViews.value.last()
        a.confirmPairing(aPairing.id, "peer-b")
        b.confirmPairing(bPairing.id, "peer-a")
        eventually("paired & heartbeats exchanged") {
            a.peers.value.any { it.nodeId == b.nodeId && it.connected && it.lastHeartbeat != null } &&
                b.peers.value.any { it.nodeId == a.nodeId && it.connected && it.lastHeartbeat != null }
        }
    }

    private fun modelDesc(id: String, isLocal: Boolean = true) = ModelDescriptor(
        id = id,
        name = id,
        kind = ModelKind.LLM,
        providerId = "llama.cpp",
        isLocal = isLocal,
        contextWindow = 4096,
        sizeBytes = 1_000_000L,
        digest = "sha256:$id",
    )

    private fun chatBody(model: String = "aidos:chat", prompt: String = "hello", stream: Boolean = false) =
        json.encodeToString(
            ChatCompletionRequest(
                model = model,
                messages = listOf(ChatMessage(role = "user", content = prompt)),
                stream = stream,
            )
        )

    @Test
    fun aidosChatResolvesLocallyOnDefaultLocalOnlyAndDelegatesToTrustedPeerOnTrustedCluster() = testApplication {
        val originNode = createNode(models = listOf(ModelAdvert("qwen2.5-3b", "llm", "sha256:3b", "local", loaded = true)))
        val peerNode = createNode(models = listOf(ModelAdvert("qwen2.5-14b", "llm", "sha256:14b", "local", loaded = true)))
        pairNodes(originNode, peerNode)

        val originRuntime = TestRuntime(listOf(modelDesc("qwen2.5-3b")), "origin")
        val peerRuntime = TestRuntime(listOf(modelDesc("qwen2.5-14b")), "peer")

        // Peer EngineHttpServer attaches its delegation handler to peerNode
        val peerTokens = TokenManager()
        EngineHttpServer(
            tokenManager = peerTokens,
            modelRuntime = peerRuntime,
            aidinNode = { peerNode },
        )

        val originTokens = TokenManager()
        val virtualConfig = VirtualModelConfig(
            id = "aidos:chat",
            kind = ModelKind.LLM,
            candidates = listOf(
                VirtualCandidate.Peer(peerNode.nodeId, "qwen2.5-14b"),
                VirtualCandidate.Local("qwen2.5-3b"),
            ),
        )
        val originServer = EngineHttpServer(
            tokenManager = originTokens,
            modelRuntime = originRuntime,
            aidinNode = { originNode },
            virtualModels = { listOf(virtualConfig) },
        )
        application { originServer.installInto(this) }
        val token = originTokens.generateNewToken().token

        // 1. GET /v1/models includes aidos:chat with widest candidate placement ("cluster")
        val modelsRes = client.get("/v1/models") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, modelsRes.status)
        val modelsPayload = json.decodeFromString<ModelsResponse>(modelsRes.bodyAsText())
        val chatCard = modelsPayload.data.firstOrNull { it.id == "aidos:chat" }
        assertNotNull(chatCard)
        assertEquals("cluster", chatCard.placement)

        // 2. Default request (no X-Aidos-Locality header -> local_only) filters out peer candidate
        //    BEFORE contacting peer and runs local:qwen2.5-3b
        val localRes = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(chatBody("aidos:chat", "ping"))
        }
        assertEquals(HttpStatusCode.OK, localRes.status)
        assertEquals("local", localRes.headers[PLACEMENT_HEADER])
        assertEquals("local", localRes.headers[ROUTE_HEADER])
        val localChat = json.decodeFromString<ChatCompletionResponse>(localRes.bodyAsText())
        assertEquals("origin[qwen2.5-3b]:ping", localChat.choices.single().message.content)
        assertTrue(peerRuntime.invokedModels.isEmpty(), "peer must not be contacted under local_only")

        // 3. Request with X-Aidos-Locality: trusted_cluster delegates to peer:<nodeId>/qwen2.5-14b
        val clusterRes = client.post("/v1/chat/completions") {
            bearerAuth(token)
            header(LOCALITY_HEADER, "trusted_cluster")
            contentType(ContentType.Application.Json)
            setBody(chatBody("aidos:chat", "cluster-ping"))
        }
        assertEquals(HttpStatusCode.OK, clusterRes.status)
        assertEquals("cluster", clusterRes.headers[PLACEMENT_HEADER])
        assertEquals("peer:${peerNode.nodeId.value}", clusterRes.headers[ROUTE_HEADER])
        val clusterChat = json.decodeFromString<ChatCompletionResponse>(clusterRes.bodyAsText())
        assertEquals("peer[qwen2.5-14b]:cluster-ping", clusterChat.choices.single().message.content)
    }

    @Test
    fun localityViolationReturns403WhileOfflinePermittedCandidatesReturn503ModelUnavailable() = testApplication {
        val originNode = createNode()
        val offlinePeerId = Keys.nodeIdOf(SigningKey.generate().publicKey)
        val originRuntime = TestRuntime(emptyList(), "origin")
        val tokens = TokenManager()

        // Virtual model has ONLY a peer candidate (no local fallback)
        val peerOnlyConfig = VirtualModelConfig(
            id = "aidos:chat",
            kind = ModelKind.LLM,
            candidates = listOf(VirtualCandidate.Peer(offlinePeerId, "qwen2.5-14b")),
        )
        val server = EngineHttpServer(
            tokenManager = tokens,
            modelRuntime = originRuntime,
            aidinNode = { originNode },
            virtualModels = { listOf(peerOnlyConfig) },
        )
        application { server.installInto(this) }
        val token = tokens.generateNewToken().token

        // Under local_only, locality removes all candidates -> 403 locality_violation
        val forbidden = client.post("/v1/chat/completions") {
            bearerAuth(token)
            header(LOCALITY_HEADER, "local_only")
            contentType(ContentType.Application.Json)
            setBody(chatBody("aidos:chat"))
        }
        assertEquals(HttpStatusCode.Forbidden, forbidden.status)
        assertTrue(forbidden.bodyAsText().contains("\"type\":\"locality_violation\""))

        // Under trusted_cluster, the candidate is permitted by locality but offline -> 503 model_unavailable
        val unavailable = client.post("/v1/chat/completions") {
            bearerAuth(token)
            header(LOCALITY_HEADER, "trusted_cluster")
            contentType(ContentType.Application.Json)
            setBody(chatBody("aidos:chat"))
        }
        assertEquals(HttpStatusCode.ServiceUnavailable, unavailable.status)
        assertTrue(unavailable.bodyAsText().contains("\"code\":\"model_unavailable\""))
    }

    @Test
    fun originVerifiesEverySplitHelperInRouteAndTreatsUntrustedHelperAsRemote() {
        val admin = createNode()
        val peerAnchor = createNode()
        val trustedHelper = createNode()
        val untrustedStranger = Keys.nodeIdOf(SigningKey.generate().publicKey)

        pairNodes(admin, peerAnchor)
        pairNodes(admin, trustedHelper)

        // 1. Split route where origin trusts both peerAnchor and trustedHelper -> Placement.CLUSTER
        val trustedSplitRoute = RouteInfo(
            model = "qwen2.5-32b",
            peerPlacement = "cluster",
            anchorNodeId = peerAnchor.nodeId.value,
            helperNodeIds = listOf(trustedHelper.nodeId.value),
            planId = "plan-1",
            planVersion = 1L,
        )
        assertEquals(
            Placement.CLUSTER,
            originPlacementForPeerRoute(admin, peerAnchor.nodeId, trustedSplitRoute),
        )

        // 2. Split route where peerAnchor uses a helper that origin does NOT trust -> Placement.REMOTE
        val untrustedHelperRoute = trustedSplitRoute.copy(
            helperNodeIds = listOf(trustedHelper.nodeId.value, untrustedStranger.value),
        )
        assertEquals(
            Placement.REMOTE,
            originPlacementForPeerRoute(admin, peerAnchor.nodeId, untrustedHelperRoute),
        )

        // 3. Stranger peer with no credential -> null (never contacted)
        assertNull(originPlacementForPeerRoute(admin, untrustedStranger, trustedSplitRoute))
    }

    @Test
    fun hopLimitOneRefusesSecondHopDelegation() = testApplication {
        val runtime = TestRuntime(listOf(modelDesc("qwen2.5-3b")), "peer")
        val tokens = TokenManager()
        val server = EngineHttpServer(tokens, runtime)
        application { server.installInto(this) }
        val token = tokens.generateNewToken().token

        val res = client.post("/aidin/v1/chat/completions") {
            bearerAuth(token)
            header(HOP_HEADER, "2")
            contentType(ContentType.Application.Json)
            setBody(chatBody("qwen2.5-3b"))
        }
        assertEquals(HttpStatusCode.Forbidden, res.status)
        assertTrue(res.bodyAsText().contains("hop_limit_exceeded"))
    }

    @Test
    fun nonStreamingRequestRetriesOnceOnLocalFallbackWhenPeerReturnsBusyOrPlanChanged() = testApplication {
        val originNode = createNode(models = listOf(ModelAdvert("qwen2.5-3b", "llm", "sha256:3b", "local", loaded = true)))
        val peerNode = createNode(models = listOf(ModelAdvert("qwen2.5-14b", "llm", "sha256:14b", "local", loaded = true)))
        pairNodes(originNode, peerNode)

        val originRuntime = TestRuntime(listOf(modelDesc("qwen2.5-3b")), "origin")
        val peerDraining = AtomicBoolean(false)
        val peerRuntime = TestRuntime(listOf(modelDesc("qwen2.5-14b")), "peer")

        // Peer server starts out not draining when /aidin/v1/route is queried, then returns 503 peer_busy on chat
        EngineHttpServer(
            tokenManager = TokenManager(),
            modelRuntime = peerRuntime,
            aidinNode = { peerNode },
            isPeerDraining = peerDraining::get,
        )
        val defaultHandler = peerNode.delegationHandler!!
        peerNode.delegationHandler = { from, req, emit ->
            if (req.endpoint == "/aidin/v1/chat/completions") {
                peerDraining.set(true)
            }
            defaultHandler(from, req, emit)
        }

        val tokens = TokenManager()
        val virtualConfig = VirtualModelConfig(
            id = "aidos:chat",
            kind = ModelKind.LLM,
            candidates = listOf(
                VirtualCandidate.Peer(peerNode.nodeId, "qwen2.5-14b"),
                VirtualCandidate.Local("qwen2.5-3b"),
            ),
        )
        val originServer = EngineHttpServer(
            tokenManager = tokens,
            modelRuntime = originRuntime,
            aidinNode = { originNode },
            virtualModels = { listOf(virtualConfig) },
        )
        application { originServer.installInto(this) }
        val token = tokens.generateNewToken().token

        val res = client.post("/v1/chat/completions") {
            bearerAuth(token)
            header(LOCALITY_HEADER, "trusted_cluster")
            contentType(ContentType.Application.Json)
            setBody(chatBody("aidos:chat", "retry-me"))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        // Because the peer returned 503 peer_busy, origin retried once on local:qwen2.5-3b
        assertEquals("local", res.headers[PLACEMENT_HEADER])
        assertEquals("local", res.headers[ROUTE_HEADER])
        val chat = json.decodeFromString<ChatCompletionResponse>(res.bodyAsText())
        assertEquals("origin[qwen2.5-3b]:retry-me", chat.choices.single().message.content)
    }

    @Test
    fun streamingMidRequestPeerLossEmitsPeerLostRetryableAndNextRequestFallsBackLocally() = testApplication {
        val originNode = createNode(models = listOf(ModelAdvert("qwen2.5-3b", "llm", "sha256:3b", "local", loaded = true)))
        val peerNode = createNode(models = listOf(ModelAdvert("qwen2.5-14b", "llm", "sha256:14b", "local", loaded = true)))
        pairNodes(originNode, peerNode)

        val originRuntime = TestRuntime(listOf(modelDesc("qwen2.5-3b")), "origin")

        // Peer answers /aidin/v1/route normally, then drops mid-stream during /aidin/v1/chat/completions
        peerNode.delegationHandler = { _, req, emit ->
            if (req.endpoint == "/aidin/v1/route") {
                val info = RouteInfo(
                    model = "qwen2.5-14b",
                    peerPlacement = "local",
                    anchorNodeId = peerNode.nodeId.value,
                )
                LinkMessage.DelegateResponse(req.requestId, 200, json.encodeToString(info))
            } else {
                emit(LinkMessage.DelegateStreamEvent(req.requestId, """{"type":"peer_lost","retryable":true}""", done = true))
                LinkMessage.DelegateResponse(req.requestId, 200, "{}")
            }
        }

        val tokens = TokenManager()
        val virtualConfig = VirtualModelConfig(
            id = "aidos:chat",
            kind = ModelKind.LLM,
            candidates = listOf(
                VirtualCandidate.Peer(peerNode.nodeId, "qwen2.5-14b"),
                VirtualCandidate.Local("qwen2.5-3b"),
            ),
        )
        val originServer = EngineHttpServer(
            tokenManager = tokens,
            modelRuntime = originRuntime,
            aidinNode = { originNode },
            virtualModels = { listOf(virtualConfig) },
        )
        application { originServer.installInto(this) }
        val token = tokens.generateNewToken().token

        // 1. Streaming request sees mid-stream peer loss event: {"type":"peer_lost","retryable":true}
        val streamRes = client.post("/v1/chat/completions") {
            bearerAuth(token)
            header(LOCALITY_HEADER, "trusted_cluster")
            contentType(ContentType.Application.Json)
            setBody(chatBody("aidos:chat", "stream-test", stream = true))
        }
        assertEquals(HttpStatusCode.OK, streamRes.status)
        val sseText = streamRes.bodyAsText()
        assertTrue(sseText.contains("""{"type":"peer_lost","retryable":true}"""), sseText)

        // 2. Immediately following request resolves to local:qwen2.5-3b without waiting for heartbeat timeout
        val nextRes = client.post("/v1/chat/completions") {
            bearerAuth(token)
            header(LOCALITY_HEADER, "trusted_cluster")
            contentType(ContentType.Application.Json)
            setBody(chatBody("aidos:chat", "after-stream-loss", stream = false))
        }
        assertEquals(HttpStatusCode.OK, nextRes.status)
        assertEquals("local", nextRes.headers[ROUTE_HEADER])
        val nextChat = json.decodeFromString<ChatCompletionResponse>(nextRes.bodyAsText())
        assertEquals("origin[qwen2.5-3b]:after-stream-loss", nextChat.choices.single().message.content)
    }

    @Test
    fun warmLocalFallbackStaysLoadedAndPinnedWhilePeerRouteIsUsed() = testApplication {
        val originNode = createNode(models = listOf(ModelAdvert("qwen2.5-3b", "llm", "sha256:3b", "local", loaded = true)))
        val peerNode = createNode(models = listOf(ModelAdvert("qwen2.5-14b", "llm", "sha256:14b", "local", loaded = true)))
        pairNodes(originNode, peerNode)

        val descriptors = listOf(modelDesc("qwen2.5-3b"), modelDesc("other-local-1"), modelDesc("other-local-2"))
        val loadCount = AtomicInteger(0)
        val backend = object : InferenceBackend {
            override suspend fun catalog(): List<ModelDescriptor> = descriptors
            override suspend fun installed(): List<ModelDescriptor> = descriptors
            override suspend fun computeDigest(modelId: String): String = "sha256:$modelId"
            override suspend fun load(modelId: String): Result<ModelAdapter> {
                if (modelId == "qwen2.5-3b") loadCount.incrementAndGet()
                return Result.success(TagModelAdapter(modelId, "warm[$modelId]"))
            }
            override suspend fun unload(modelId: String) = Unit
            override suspend fun delete(modelId: String) = Unit
        }
        val globalRuntime = GlobalModelRuntime(backend, maxResidentModels = 2)

        val peerRuntime = TestRuntime(listOf(modelDesc("qwen2.5-14b")), "peer")
        EngineHttpServer(TokenManager(), peerRuntime, aidinNode = { peerNode })

        val virtualConfig = VirtualModelConfig(
            id = "aidos:chat",
            kind = ModelKind.LLM,
            candidates = listOf(
                VirtualCandidate.Peer(peerNode.nodeId, "qwen2.5-14b"),
                VirtualCandidate.Local("qwen2.5-3b"),
            ),
        )
        val tokens = TokenManager()
        val originServer = EngineHttpServer(
            tokenManager = tokens,
            modelRuntime = globalRuntime,
            aidinNode = { originNode },
            virtualModels = { listOf(virtualConfig) },
        )
        application { originServer.installInto(this) }
        val token = tokens.generateNewToken().token

        // Resolving aidos:chat to the peer automatically warms and pins local:qwen2.5-3b
        val res = client.post("/v1/chat/completions") {
            bearerAuth(token)
            header(LOCALITY_HEADER, "trusted_cluster")
            contentType(ContentType.Application.Json)
            setBody(chatBody("aidos:chat", "warm-check"))
        }
        assertEquals(HttpStatusCode.OK, res.status)
        assertEquals("peer:${peerNode.nodeId.value}", res.headers[ROUTE_HEADER])
        assertTrue(globalRuntime.isWarmFallbackPinned("qwen2.5-3b"))
        assertTrue("qwen2.5-3b" in globalRuntime.loaded())
        assertEquals(1, loadCount.get())

        // Loading two other local models in a maxLoadedModels=2 runtime must NOT evict the pinned warm fallback
        globalRuntime.load("other-local-1").getOrThrow()
        globalRuntime.load("other-local-2").getOrThrow()
        assertTrue("qwen2.5-3b" in globalRuntime.loaded(), "warm fallback must stay pinned against LRU eviction")
    }
}
