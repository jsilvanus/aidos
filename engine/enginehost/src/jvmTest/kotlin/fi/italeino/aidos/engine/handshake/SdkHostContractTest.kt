package fi.italeino.aidos.engine.handshake

import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.approval.InMemoryAppApprovalStore
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.MockModelAdapter
import fi.italeino.aidos.engine.http.MockModelRuntime
import fi.italeino.aidos.engine.http.StreamingMockModelAdapter
import fi.italeino.aidos.engine.http.TokenManager
import fi.italeino.aidos.sdk.client.AidosEngineClient
import fi.italeino.aidos.sdk.client.AidosEngineClientFactory
import fi.italeino.aidos.sdk.client.ChatCompletionRequest
import fi.italeino.aidos.sdk.client.ChatMessage
import fi.italeino.aidos.sdk.client.EngineAvailability
import fi.italeino.aidos.sdk.client.EngineCapabilities
import fi.italeino.aidos.sdk.client.EngineHandshakeResult
import fi.italeino.aidos.sdk.client.EngineHandshakeSource
import fi.italeino.aidos.sdk.client.EngineModel
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real Aidos SDK client against the real Engine host — the frozen contract (sdk/CONTRACT.md)
 * exercised from both ends in one process: [HandshakeCore] decides, [EngineHttpServer] listens on a
 * real loopback port, and the SDK's own transport (OkHttp, bearer auth, 401 → re-handshake, SSE)
 * talks to it. Only Binder is replaced by [asCaller]. The Bundle ↔ [HandshakeReply] mapping in
 * `EngineHandshakeImpl`/`EngineBinderHandshake` is the one hop this cannot cover.
 */
class SdkHostContractTest {

    private val dictator = "com.dictator.android"
    private val agent = "dev.aidos.agent"

    private val store = InMemoryAppApprovalStore()
    private val tokens = TokenManager()
    private val runtime = MockModelRuntime(StreamingMockModelAdapter(listOf("Hel", "lo", "!")))
    private val server = EngineHttpServer(
        tokens,
        runtime,
        isSubjectApproved = { pkg -> store.getApproval(pkg)?.status == AppApprovalStatus.APPROVED }
    ).also { it.start() }
    private val core = HandshakeCore(
        store = store,
        tokenManager = tokens,
        boundPort = { server.getBoundPort() },
        capabilities = { runtime.handshakeCapabilities() }
    )
    private val opened = mutableListOf<AidosEngineClient>()

    @AfterTest
    fun tearDown() {
        opened.forEach { it.close() }
        server.stop()
    }

    /** An SDK client whose "Binder caller" is [pkg]. */
    private fun asCaller(pkg: String): AidosEngineClient =
        AidosEngineClientFactory.create(EngineHandshakeSource {
            val reply = core.handshake(pkg)
            when (reply.status) {
                HandshakeWire.STATUS_APPROVED -> EngineHandshakeResult.Approved(
                    reply.port,
                    reply.token,
                    // Same JSON the Bundle carries, decoded by the SDK's own parser would be ideal;
                    // capabilities content is asserted separately below.
                    EngineCapabilities(listOf("chat.completions"), listOf(EngineModel("test-model", "llm", 2048)))
                )
                HandshakeWire.STATUS_PENDING_APPROVAL -> EngineHandshakeResult.PendingApproval
                HandshakeWire.STATUS_DENIED -> EngineHandshakeResult.Denied
                else -> EngineHandshakeResult.Failed
            }
        }).also { opened += it }

    private fun ask(client: AidosEngineClient) = runBlocking {
        client.chatCompletion(ChatCompletionRequest("test-model", listOf(ChatMessage("user", "hi"))))
            ?.choices?.firstOrNull()?.message?.content
    }

    @Test
    fun firstRequest_isPending_thenApprovalMakesTheSameClientAvailable() = runBlocking<Unit> {
        val client = asCaller(dictator)

        assertFalse(client.initialize())
        assertEquals(EngineAvailability.PendingApproval, client.availability())
        assertNull(ask(client), "no token yet, so no inference")
        // Engine has the request recorded for the user to act on.
        assertEquals(AppApprovalStatus.PENDING, store.getApproval(dictator)?.status)

        store.approveApp(dictator)

        assertTrue(client.initialize())
        assertEquals(EngineAvailability.Available, client.availability())
        assertNotNull(ask(client))
    }

    @Test
    fun approvedClient_streamsFromTheRealServer() = runBlocking<Unit> {
        store.recordFirstHandshake(dictator, "Dictator")
        store.approveApp(dictator)
        val client = asCaller(dictator)
        assertTrue(client.initialize())

        val chunks = client.streamChatCompletion(
            ChatCompletionRequest("test-model", listOf(ChatMessage("user", "hi")))
        ).toList()

        assertEquals("Hello!", chunks.joinToString("") { c -> c.choices.joinToString("") { it.delta.content.orEmpty() } })
    }

    @Test
    fun denied_isReportedAsDenied() = runBlocking<Unit> {
        store.recordFirstHandshake(dictator, "Dictator")
        store.denyApp(dictator)
        val client = asCaller(dictator)

        assertFalse(client.initialize())
        assertEquals(EngineAvailability.Denied, client.availability())
    }

    @Test
    fun revokingWhileConnected_makesTheNextCallFailAndReportPending() = runBlocking<Unit> {
        store.recordFirstHandshake(dictator, "Dictator")
        store.approveApp(dictator)
        val client = asCaller(dictator)
        assertTrue(client.initialize())
        assertNotNull(ask(client))

        store.revokeApproval(dictator)

        // 401 → the SDK re-handshakes once → Engine says PENDING → the call yields nothing.
        assertNull(ask(client))
        assertEquals(EngineAvailability.PendingApproval, client.availability())
    }

    @Test
    fun twoApprovedApps_workSideBySide() = runBlocking<Unit> {
        for (pkg in listOf(dictator, agent)) {
            store.recordFirstHandshake(pkg, pkg)
            store.approveApp(pkg)
        }
        val a = asCaller(dictator)
        val b = asCaller(agent)
        assertTrue(a.initialize())
        assertTrue(b.initialize())

        // Interleave; neither handshake evicts the other, and neither needs to re-handshake.
        assertNotNull(ask(a))
        assertNotNull(ask(b))
        assertNotNull(ask(a))
    }

    @Test
    fun enginesOwnInProcessClient_needsNoApproval_andSurvivesAppHandshakes() = runBlocking<Unit> {
        val own = AidosEngineClientFactory.create(
            InProcessHandshakeSource(tokens, { server.getBoundPort() }, { runtime.handshakeCapabilities() })
        ).also { opened += it }
        assertTrue(own.initialize())
        assertTrue(own.capabilities().models.any { it.id == "test-model" })
        assertNotNull(ask(own))

        store.recordFirstHandshake(dictator, "Dictator")
        store.approveApp(dictator)
        assertTrue(asCaller(dictator).also { c -> c.initialize() }.isAvailable())

        assertNotNull(ask(own))
    }
}
