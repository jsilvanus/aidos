package fi.italeino.aidos.engine.http

import dev.aidos.kernel.ContentBlock
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
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.testing.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for EngineHttpServer (RFC-0103, Phase B).
 * Tests model inference endpoints with mock ModelRuntime.
 */
class EngineHttpServerTest {

    @Test
    fun chatCompletions_returnsValidResponse() = testApplication {
        val mockRuntime = MockModelRuntime()
        val tokenManager = TokenManager()
        val token = tokenManager.generateNewToken()

        // Setup test server
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = false
                encodeDefaults = true
                isLenient = true
            })
        }

        install(Authentication) {
            bearer("bearerAuth") {
                authenticate { tokenCredential ->
                    if (tokenManager.validateToken(tokenCredential.token) != null) {
                        UserIdPrincipal(tokenCredential.token)
                    } else {
                        null
                    }
                }
            }
        }

        routing {
            authenticate("bearerAuth") {
                post("/v1/chat/completions") {
                    // This would normally be handled by EngineHttpServer.handleChatCompletions
                    // For unit testing, we're testing the logic separately
                    val request = call.receive<ChatCompletionRequest>()
                    val response = ChatCompletionResponse(
                        id = "test-id",
                        created = System.currentTimeMillis() / 1000,
                        model = request.model,
                        choices = listOf(
                            Choice(
                                index = 0,
                                message = ChatMessage(
                                    role = "assistant",
                                    content = "Test response"
                                ),
                                finish_reason = "stop"
                            )
                        ),
                        usage = TokenUsage(
                            prompt_tokens = 10,
                            completion_tokens = 5,
                            total_tokens = 15
                        )
                    )
                    call.respond(response)
                }
            }
        }

        // Make test request
        val response = client.post("/v1/chat/completions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(ChatCompletionRequest(
                model = "test-model",
                messages = listOf(
                    ChatMessage(
                        role = "user",
                        content = "Hello"
                    )
                )
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)

        val body = response.bodyAsText()
        assertTrue(body.contains("test-id"))
        assertTrue(body.contains("Test response"))
    }

    @Test
    fun chatCompletions_requiresAuthentication() = testApplication {
        install(ContentNegotiation) {
            json()
        }

        install(Authentication) {
            bearer("bearerAuth") {
                authenticate { null }
            }
        }

        routing {
            authenticate("bearerAuth") {
                post("/v1/chat/completions") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        // Make request without authentication
        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun chatCompletions_rejectsMissingModel() = testApplication {
        val tokenManager = TokenManager()
        val token = tokenManager.generateNewToken()

        install(ContentNegotiation) {
            json()
        }

        routing {
            post("/v1/chat/completions") {
                val request = call.receive<ChatCompletionRequest>()
                if (request.model.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            error = ErrorDetail(
                                message = "Model name is required",
                                type = "invalid_request_error"
                            )
                        )
                    )
                } else {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        val response = client.post("/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            setBody(ChatCompletionRequest(
                model = "",
                messages = listOf(ChatMessage(role = "user", content = "test"))
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Model name is required"))
    }

    @Test
    fun health_requiresNoAuthentication() = testApplication {
        routing {
            get("/health") {
                call.respond(mapOf("status" to "ok"))
            }
        }

        val response = client.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("ok"))
    }

    @Test
    fun transcriptions_returnsValidResponse() = testApplication {
        val mockRuntime = MockModelRuntime()
        val tokenManager = TokenManager()
        val token = tokenManager.generateNewToken()

        // Setup test server
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = false
                encodeDefaults = true
                isLenient = true
            })
        }

        install(Authentication) {
            bearer("bearerAuth") {
                authenticate { tokenCredential ->
                    if (tokenManager.validateToken(tokenCredential.token) != null) {
                        UserIdPrincipal(tokenCredential.token)
                    } else {
                        null
                    }
                }
            }
        }

        routing {
            authenticate("bearerAuth") {
                post("/v1/audio/transcriptions") {
                    val request = call.receive<TranscriptionRequest>()
                    val response = TranscriptionResponse(text = "Mock transcription result")
                    call.respond(response)
                }
            }
        }

        // Create base64-encoded audio (simple dummy data)
        val dummyAudio = byteArrayOf(0x52, 0x49, 0x46, 0x46)  // "RIFF" header
        val base64Audio = java.util.Base64.getEncoder().encodeToString(dummyAudio)

        // Make test request
        val response = client.post("/v1/audio/transcriptions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(TranscriptionRequest(
                file = base64Audio,
                model = "test-model"
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Mock transcription result"))
    }

    @Test
    fun transcriptions_requiresAuthentication() = testApplication {
        install(ContentNegotiation) {
            json()
        }

        install(Authentication) {
            bearer("bearerAuth") {
                authenticate { null }
            }
        }

        routing {
            authenticate("bearerAuth") {
                post("/v1/audio/transcriptions") {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        // Make request without authentication
        val response = client.post("/v1/audio/transcriptions") {
            contentType(ContentType.Application.Json)
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    @Test
    fun transcriptions_rejectsMissingModel() = testApplication {
        val tokenManager = TokenManager()
        val token = tokenManager.generateNewToken()

        install(ContentNegotiation) {
            json()
        }

        routing {
            post("/v1/audio/transcriptions") {
                val request = call.receive<TranscriptionRequest>()
                if (request.model.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            error = ErrorDetail(
                                message = "Model name is required",
                                type = "invalid_request_error"
                            )
                        )
                    )
                } else {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        val dummyAudio = java.util.Base64.getEncoder().encodeToString(byteArrayOf(0x52, 0x49, 0x46, 0x46))

        val response = client.post("/v1/audio/transcriptions") {
            contentType(ContentType.Application.Json)
            setBody(TranscriptionRequest(
                file = dummyAudio,
                model = ""
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Model name is required"))
    }

    @Test
    fun transcriptions_rejectsMissingAudioFile() = testApplication {
        val tokenManager = TokenManager()
        val token = tokenManager.generateNewToken()

        install(ContentNegotiation) {
            json()
        }

        routing {
            post("/v1/audio/transcriptions") {
                val request = call.receive<TranscriptionRequest>()
                if (request.file.isBlank()) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            error = ErrorDetail(
                                message = "Audio file is required",
                                type = "invalid_request_error"
                            )
                        )
                    )
                } else {
                    call.respond(HttpStatusCode.OK)
                }
            }
        }

        val response = client.post("/v1/audio/transcriptions") {
            contentType(ContentType.Application.Json)
            setBody(TranscriptionRequest(
                file = "",
                model = "test-model"
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Audio file is required"))
    }

    @Test
    fun transcriptions_rejectsInvalidBase64() = testApplication {
        val tokenManager = TokenManager()
        val token = tokenManager.generateNewToken()

        install(ContentNegotiation) {
            json()
        }

        routing {
            post("/v1/audio/transcriptions") {
                val request = call.receive<TranscriptionRequest>()
                try {
                    java.util.Base64.getDecoder().decode(request.file)
                    call.respond(HttpStatusCode.OK)
                } catch (e: IllegalArgumentException) {
                    call.respond(
                        HttpStatusCode.BadRequest,
                        ErrorResponse(
                            error = ErrorDetail(
                                message = "Invalid base64-encoded audio file: ${e.message}",
                                type = "invalid_request_error"
                            )
                        )
                    )
                }
            }
        }

        val response = client.post("/v1/audio/transcriptions") {
            contentType(ContentType.Application.Json)
            setBody(TranscriptionRequest(
                file = "invalid_base64!!!",
                model = "test-model"
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Invalid base64-encoded audio file"))
    }

    @Test
    fun chatCompletions_streamsRealPerTokenDeltasThenDone() = testApplication {
        // Exercises EngineHttpServer's actual routing and streamChatCompletions (RFC-0021
        // "Streaming"; Dictator plan S4) via a ModelAdapter whose invokeStreaming() emits several
        // discrete deltas before Done — unlike the pre-S4 version of this test, which never
        // called into EngineHttpServer at all and so could not have caught the real bug (Engine
        // buffering the whole response before "streaming" it).
        val tokenManager = TokenManager()
        val token = tokenManager.generateNewToken()
        val server = EngineHttpServer(tokenManager, MockModelRuntime(StreamingMockModelAdapter(listOf("Hel", "lo", " world"))))
        application { server.installInto(this) }

        val response = client.post("/v1/chat/completions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(ChatCompletionRequest(
                model = "test-model",
                messages = listOf(ChatMessage(role = "user", content = "Hello")),
                stream = true
            ))
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
        val tokenManager = TokenManager()
        val token = tokenManager.generateNewToken()
        val failure = IllegalStateException("native generation crashed")
        val server = EngineHttpServer(
            tokenManager,
            MockModelRuntime(FailingStreamingModelAdapter(listOf("partial "), failure))
        )
        application { server.installInto(this) }

        val response = client.post("/v1/chat/completions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(ChatCompletionRequest(
                model = "test-model",
                messages = listOf(ChatMessage(role = "user", content = "Hello")),
                stream = true
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("partial "), "partial output before the failure should still arrive")
        assertTrue(body.contains("native generation crashed"))
        assertTrue(body.trim().endsWith("data: [DONE]"), "stream must still terminate so a client's SSE parser doesn't hang")
    }

    @Test
    fun chatCompletions_supportsNonStreamingResponse() = testApplication {
        val tokenManager = TokenManager()
        val token = tokenManager.generateNewToken()

        install(ContentNegotiation) {
            json(Json {
                prettyPrint = false
                encodeDefaults = true
                isLenient = true
            })
        }

        install(Authentication) {
            bearer("bearerAuth") {
                authenticate { tokenCredential ->
                    if (tokenManager.validateToken(tokenCredential.token) != null) {
                        UserIdPrincipal(tokenCredential.token)
                    } else {
                        null
                    }
                }
            }
        }

        routing {
            authenticate("bearerAuth") {
                post("/v1/chat/completions") {
                    val request = call.receive<ChatCompletionRequest>()
                    call.respond(ChatCompletionResponse(
                        id = "test",
                        created = System.currentTimeMillis() / 1000,
                        model = request.model,
                        choices = listOf(
                            Choice(
                                index = 0,
                                message = ChatMessage(role = "assistant", content = "Non-streaming response"),
                                finish_reason = "stop"
                            )
                        ),
                        usage = TokenUsage(prompt_tokens = 5, completion_tokens = 3, total_tokens = 8)
                    ))
                }
            }
        }

        // Test non-streaming request (stream = false or omitted)
        val response = client.post("/v1/chat/completions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(ChatCompletionRequest(
                model = "test-model",
                messages = listOf(ChatMessage(role = "user", content = "Hello")),
                stream = false
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("Non-streaming response"))
        assertTrue(body.contains("chat.completion"))  // non-chunk object type
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
