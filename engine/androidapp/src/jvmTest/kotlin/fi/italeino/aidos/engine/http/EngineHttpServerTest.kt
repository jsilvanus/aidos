package fi.italeino.aidos.engine.http

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.ModelStreamEvent
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Usage
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.application.*
import io.ktor.server.testing.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

// The test client has no ContentNegotiation plugin installed (only the server does, via
// EngineHttpServer's own setup) — setBody(someDataClass) would otherwise fail with "Fail to
// prepare request body for sending" (HttpSend.kt). Encoding requests manually avoids adding a
// client-side plugin dependency just for tests.
private val testJson = Json { encodeDefaults = true }

/**
 * Unit tests for EngineHttpServer (RFC-0103), against the real class via [EngineHttpServer.installInto]
 * rather than hand-rolled routing that duplicates (and can drift from) its actual handlers — see
 * docs/dictator-sdk-integration-plan.md's Risks section for why this file previously couldn't do
 * that at all (androidMain wasn't visible from jvmTest) and, once it could, why the hand-rolled
 * version turned out to assert behavior EngineHttpServer doesn't actually implement (e.g. a
 * "model name is required" 400 that was never wired up — an unknown model 404s instead, via
 * ModelRuntime.load() failing).
 */
class EngineHttpServerTest {

    private fun testServer(adapter: ModelAdapter = MockModelAdapter()): Pair<EngineHttpServer, TokenManager> {
        val tokenManager = TokenManager()
        val server = EngineHttpServer(tokenManager, MockModelRuntime(adapter))
        return server to tokenManager
    }

    @Test
    fun health_doesNotRequireAuthentication() = testApplication {
        val (server, _) = testServer()
        application { server.installInto(this) }

        val response = client.get("/health")

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("ok"))
    }

    @Test
    fun chatCompletions_requiresAuthentication() = testApplication {
        val (server, _) = testServer()
        application { server.installInto(this) }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(ChatCompletionRequest(model = "test-model", messages = listOf(ChatMessage(role = "user", content = "Hello")))))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun chatCompletions_returnsValidResponse() = testApplication {
        val (server, tokenManager) = testServer()
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/chat/completions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(ChatCompletionRequest(model = "test-model", messages = listOf(ChatMessage(role = "user", content = "Hello")))))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Mock response"))
        assertTrue(body.contains("\"model\":\"test-model\""))
        assertTrue(body.contains("chat.completion") && !body.contains("chat.completion.chunk"))
    }

    @Test
    fun chatCompletions_unknownModelReturnsNotFound() = testApplication {
        val (server, tokenManager) = testServer()
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/chat/completions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(ChatCompletionRequest(model = "unknown-model", messages = listOf(ChatMessage(role = "user", content = "Hello")))))
        }

        assertEquals(HttpStatusCode.NotFound, response.status)
        assertTrue(response.bodyAsText().contains("not installed"))
    }

    @Test
    fun chatCompletions_streamsRealPerTokenDeltasThenDone() = testApplication {
        // Exercises EngineHttpServer's actual routing and streamChatCompletions (RFC-0021
        // "Streaming"; Dictator plan S4) via a ModelAdapter whose invokeStreaming() emits several
        // discrete deltas before Done — unlike the pre-S4 version of this test, which never
        // called into EngineHttpServer at all and so could not have caught the real bug (Engine
        // buffering the whole response before "streaming" it).
        val (server, tokenManager) = testServer(StreamingMockModelAdapter(listOf("Hel", "lo", " world")))
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/chat/completions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(ChatCompletionRequest(
                model = "test-model",
                messages = listOf(ChatMessage(role = "user", content = "Hello")),
                stream = true
            )))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("text/event-stream", response.headers[HttpHeaders.ContentType]?.substringBefore(";"))

        val body = response.bodyAsText()
        val deltaContents = Regex("\"content\":\"([^\"]*)\"").findAll(body).map { it.groupValues[1] }.toList()
        // Three real deltas, in order, followed by the empty-content terminal chunk — proof this
        // came from three separate SSE frames, not one response chopped up after the fact.
        assertEquals(listOf("Hel", "lo", " world", ""), deltaContents)
        assertTrue(body.trim().endsWith("data: [DONE]"))
    }

    @Test
    fun chatCompletions_streamingFailureSendsErrorFrameThenDone() = testApplication {
        val failure = IllegalStateException("native generation crashed")
        val (server, tokenManager) = testServer(FailingStreamingModelAdapter(listOf("partial "), failure))
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/chat/completions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(ChatCompletionRequest(
                model = "test-model",
                messages = listOf(ChatMessage(role = "user", content = "Hello")),
                stream = true
            )))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("partial "), "partial output before the failure should still arrive")
        assertTrue(body.contains("native generation crashed"))
        assertTrue(body.trim().endsWith("data: [DONE]"), "stream must still terminate so a client's SSE parser doesn't hang")
    }

    @Test
    fun chatCompletions_nonStreamingResponseIsOneJsonObjectNotSse() = testApplication {
        val (server, tokenManager) = testServer()
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/chat/completions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(ChatCompletionRequest(
                model = "test-model",
                messages = listOf(ChatMessage(role = "user", content = "Hello")),
                stream = false
            )))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.headers[HttpHeaders.ContentType]?.startsWith("application/json") == true)
        val body = response.bodyAsText()
        assertTrue(body.contains("Mock response"))
        assertTrue(!body.contains("data:"), "non-streaming response must not be SSE-framed")
    }

    @Test
    fun transcriptions_requiresAuthentication() = testApplication {
        val (server, _) = testServer()
        application { server.installInto(this) }

        val response = client.post("/v1/audio/transcriptions") {
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(TranscriptionRequest(file = "", model = "test-model")))
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun transcriptions_returnsValidResponse() = testApplication {
        val (server, tokenManager) = testServer()
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val dummyAudio = byteArrayOf(0x52, 0x49, 0x46, 0x46)  // "RIFF" header
        val base64Audio = java.util.Base64.getEncoder().encodeToString(dummyAudio)

        val response = client.post("/v1/audio/transcriptions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(TranscriptionRequest(file = base64Audio, model = "test-model")))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        assertTrue(response.bodyAsText().contains("Mock response"))
    }

    @Test
    fun transcriptions_unknownModelReturnsNotFound() = testApplication {
        val (server, tokenManager) = testServer()
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/audio/transcriptions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(TranscriptionRequest(file = "", model = "unknown-model")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }

    @Test
    fun transcriptions_rejectsInvalidBase64() = testApplication {
        val (server, tokenManager) = testServer()
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/audio/transcriptions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(testJson.encodeToString(TranscriptionRequest(file = "invalid_base64!!!", model = "test-model")))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
    }
}

/**
 * Mock ModelRuntime for testing.
 */
class MockModelRuntime(private val adapter: ModelAdapter = MockModelAdapter()) : ModelRuntime {
    override suspend fun catalog(): List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "test-model",
            name = "Test Model",
            kind = ModelKind.LLM,
            providerId = "test",
            isLocal = true,
            contextWindow = 2048,
            sizeBytes = 1024L,
            digest = "test-digest"
        )
    )

    override suspend fun installed(): List<ModelDescriptor> = catalog()

    override suspend fun load(modelId: String): Result<ModelAdapter> {
        return if (modelId == "test-model") {
            Result.success(adapter)
        } else {
            Result.failure(IllegalStateException("Model $modelId is not installed"))
        }
    }

    override suspend fun unload(modelId: String) {
        // No-op for mock
    }

    override fun loaded(): List<String> = listOf("test-model")
}

/**
 * Mock ModelAdapter for testing.
 */
class MockModelAdapter : ModelAdapter {
    override val providerId: String = "test"
    override val modelId: String = "test-model"
    override val modelVersion: String = "1.0"
    override val contextWindow: Int = 2048
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        return Result.success(
            ModelResponse(
                outputs = listOf(TextOutput("Mock response")),
                stopReason = StopReason.END_TURN,
                usage = Usage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
                model = ModelRef(id = modelId, version = modelVersion)
            )
        )
    }
}

/**
 * ModelAdapter whose invokeStreaming() emits several real deltas before Done — for proving
 * EngineHttpServer.streamChatCompletions forwards them as separate SSE frames rather than only
 * ever completing after the whole response is ready (RFC-0021 "Streaming"; Dictator plan S4).
 */
class StreamingMockModelAdapter(private val deltas: List<String>) : ModelAdapter {
    override val providerId: String = "test"
    override val modelId: String = "test-model"
    override val modelVersion: String = "1.0"
    override val contextWindow: Int = 2048
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> =
        Result.success(finalResponse())

    override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        deltas.forEach { emit(ModelStreamEvent.Delta(it)) }
        emit(ModelStreamEvent.Done(finalResponse()))
    }

    private fun finalResponse() = ModelResponse(
        outputs = listOf(TextOutput(deltas.joinToString(""))),
        stopReason = StopReason.END_TURN,
        usage = Usage(inputTokens = 10, outputTokens = deltas.size, totalTokens = 10 + deltas.size),
        model = ModelRef(id = modelId, version = modelVersion)
    )
}

/** ModelAdapter whose invokeStreaming() emits partial output, then fails mid-generation. */
class FailingStreamingModelAdapter(
    private val deltasBeforeFailure: List<String>,
    private val failure: Throwable
) : ModelAdapter {
    override val providerId: String = "test"
    override val modelId: String = "test-model"
    override val modelVersion: String = "1.0"
    override val contextWindow: Int = 2048
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = Result.failure(failure)

    override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        deltasBeforeFailure.forEach { emit(ModelStreamEvent.Delta(it)) }
        emit(ModelStreamEvent.Failed(failure))
    }
}
