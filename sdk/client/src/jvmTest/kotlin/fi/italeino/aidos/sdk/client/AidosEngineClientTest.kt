package fi.italeino.aidos.sdk.client

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AidosEngineClientTest {

    // --- Capability JSON parsing -----------------------------------------------------------

    @Test
    fun parseCapabilitiesJson_decodesEngineWireShape() {
        // Mirrors fi.italeino.aidos.engine.http.Capabilities/ModelInfo's actual JSON shape,
        // including fields (object, owned_by) this module doesn't model — ignoreUnknownKeys must
        // tolerate those (RFC-0103's independent-versioning rationale for the Bundle switch).
        val json = """
            {"endpoints":["chat.completions","embeddings"],"models":[
                {"id":"llama-3-8b","object":"model","owned_by":"aidos-local","kind":"llm","context_window":4096,"quantization":"q4_k_m"}
            ]}
        """.trimIndent()

        val result = parseCapabilitiesJson(json)

        assertEquals(listOf("chat.completions", "embeddings"), result.endpoints)
        assertEquals(1, result.models.size)
        assertEquals("llama-3-8b", result.models[0].id)
        assertEquals("llm", result.models[0].kind)
        assertEquals(4096, result.models[0].context_window)
        assertEquals("q4_k_m", result.models[0].quantization)
    }

    // --- Handshake outcome -> availability() state machine ----------------------------------

    private fun approvedResponse(port: Int = 0, token: String = "test-token", apiVersion: Int = 1) =
        HandshakeResponse(
            port = port,
            token = token,
            apiVersion = apiVersion,
            capabilities = CapabilitiesResponse(
                endpoints = listOf("chat.completions"),
                models = listOf(ModelInfoResponse(id = "m1", kind = "llm"))
            )
        )

    @Test
    fun initialize_withApprovedHandshake_reportsAvailable() = runTest {
        val client = EngineClientImpl(HandshakePerformer { HandshakeOutcome.Approved(approvedResponse()) })

        assertTrue(client.initialize())
        assertEquals(EngineAvailability.Available, client.availability())
        assertTrue(client.isAvailable())
        assertEquals(1, client.apiVersion())

        val caps = client.capabilities()
        assertEquals(listOf("chat.completions"), caps.endpoints)
        assertEquals("m1", caps.models.single().id)
    }

    @Test
    fun initialize_withNotInstalled_reportsThatReason() = runTest {
        val client = EngineClientImpl(HandshakePerformer { HandshakeOutcome.NotInstalled })

        assertFalse(client.initialize())
        assertEquals(EngineAvailability.NotInstalled, client.availability())
        assertFalse(client.isAvailable())
    }

    @Test
    fun initialize_withPendingApproval_reportsThatReason() = runTest {
        val client = EngineClientImpl(HandshakePerformer { HandshakeOutcome.PendingApproval })

        assertFalse(client.initialize())
        assertEquals(EngineAvailability.PendingApproval, client.availability())
    }

    @Test
    fun initialize_withDenied_reportsThatReason() = runTest {
        val client = EngineClientImpl(HandshakePerformer { HandshakeOutcome.Denied })

        assertFalse(client.initialize())
        assertEquals(EngineAvailability.Denied, client.availability())
    }

    @Test
    fun initialize_withIncompatibleApiVersion_reportsThatReasonButKeepsApiVersion() = runTest {
        val client = EngineClientImpl(
            handshakePerformer = HandshakePerformer { HandshakeOutcome.Approved(approvedResponse(apiVersion = 2)) },
            requiredApiVersion = 1
        )

        assertFalse(client.initialize())
        assertEquals(EngineAvailability.IncompatibleVersion, client.availability())
        // Engine's actual version is still readable so a caller can report/log what it saw.
        assertEquals(2, client.apiVersion())
    }

    @Test
    fun initialize_whenPerformerThrows_reportsHandshakeFailedInsteadOfPropagating() = runTest {
        val client = EngineClientImpl(HandshakePerformer { throw IllegalStateException("bind failed") })

        assertFalse(client.initialize())
        assertEquals(EngineAvailability.HandshakeFailed, client.availability())
    }

    @Test
    fun defaultFactoryClient_neverFindsEngine() = runTest {
        // No Binder on the jvm() target this factory also builds for (see its KDoc).
        val client = AidosEngineClientFactory.createClient()

        assertFalse(client.initialize())
        assertNull(client.request("chat/completions"))
    }

    // --- SseFrameParser ----------------------------------------------------------------------

    @Test
    fun sseFrameParser_singleLineEventFiresOnBlankLine() {
        val parser = SseFrameParser()

        assertNull(parser.onLine("data: {\"a\":1}"))
        assertEquals("{\"a\":1}", parser.onLine(""))
    }

    @Test
    fun sseFrameParser_multiLineDataJoinsWithNewline() {
        val parser = SseFrameParser()

        assertNull(parser.onLine("data: line one"))
        assertNull(parser.onLine("data: line two"))
        assertEquals("line one\nline two", parser.onLine(""))
    }

    @Test
    fun sseFrameParser_blankLineWithNothingAccumulatedIsIgnored() {
        val parser = SseFrameParser()

        assertNull(parser.onLine(""))
        assertNull(parser.onLine("data: after a stray blank line"))
        assertEquals("after a stray blank line", parser.onLine(""))
    }

    @Test
    fun sseFrameParser_doneSentinelPassesThroughAsPayload() {
        val parser = SseFrameParser()

        assertNull(parser.onLine("data: [DONE]"))
        assertEquals("[DONE]", parser.onLine(""))
    }

    // --- HTTP transport, against a real (mock) server -----------------------------------------

    private lateinit var server: MockWebServer

    @BeforeTest
    fun startServer() {
        server = MockWebServer()
        server.start()
    }

    @AfterTest
    fun stopServer() {
        server.shutdown()
    }

    private fun clientPointedAtServer(token: String = "test-token") = EngineClientImpl().apply {
        setHandshakeResult(approvedResponse(port = server.port, token = token))
    }

    @Test
    fun chatCompletion_postsWithBearerTokenAndDecodesResponse() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"id":"c1","created":1,"model":"m","choices":[{"index":0,"message":{"role":"assistant","content":"hi"},"finish_reason":"stop"}],"usage":{"prompt_tokens":1,"completion_tokens":1,"total_tokens":2}}"""
            )
        )
        val client = clientPointedAtServer(token = "abc123")

        val response = client.chatCompletion(
            ChatCompletionRequest(model = "m", messages = listOf(ChatMessage(role = "user", content = "hi")))
        )

        assertEquals("hi", response?.choices?.single()?.message?.content)
        val recorded = server.takeRequest()
        assertEquals("Bearer abc123", recorded.getHeader("Authorization"))
        assertEquals("/v1/chat/completions", recorded.path)
    }

    @Test
    fun request_on401_reHandshakesOnceAndRetriesWithFreshToken() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("{}"))

        val client = EngineClientImpl(
            handshakePerformer = HandshakePerformer {
                HandshakeOutcome.Approved(approvedResponse(port = server.port, token = "fresh-token"))
            }
        )
        client.setHandshakeResult(approvedResponse(port = server.port, token = "stale-token"))

        val result = client.request("chat/completions")

        assertEquals("{}", result)
        assertEquals(2, server.requestCount)
        assertEquals("Bearer stale-token", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh-token", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun streamChatCompletion_emitsChunksAndStopsAtDone() = runTest {
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "text/event-stream")
                .setBody(
                    "data: {\"id\":\"1\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}]}\n\n" +
                        "data: {\"id\":\"1\",\"created\":1,\"model\":\"m\",\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n\n" +
                        "data: [DONE]\n\n"
                )
        )
        val client = clientPointedAtServer()

        val chunks = client.streamChatCompletion(
            ChatCompletionRequest(model = "m", messages = listOf(ChatMessage(role = "user", content = "hi")))
        ).toList()

        assertEquals(listOf("Hel", "lo"), chunks.map { it.choices.single().delta.content })
    }

    @Test
    fun embeddings_and_transcribe_roundTrip() = runTest {
        server.enqueue(
            MockResponse().setBody(
                """{"data":[{"embedding":[0.1,0.2],"index":0}],"model":"m","usage":{"prompt_tokens":1,"completion_tokens":0,"total_tokens":1}}"""
            )
        )
        server.enqueue(MockResponse().setBody("""{"text":"hello world"}"""))
        val client = clientPointedAtServer()

        val embeddings = client.embeddings(EmbeddingsRequest(model = "m", input = listOf("hi")))
        assertEquals(listOf(0.1f, 0.2f), embeddings?.data?.single()?.embedding)

        val transcription = client.transcribe(TranscriptionRequest(file = "base64", model = "whisper"))
        assertEquals("hello world", transcription?.text)
    }
}
