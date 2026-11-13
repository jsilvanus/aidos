package fi.italeino.aidos.engine.http

import dev.aidos.kernel.CancellableModelAdapter
import dev.aidos.kernel.EmbeddingModelAdapter
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
import dev.aidos.kernel.Usage
import fi.italeino.aidos.engine.approval.AppApprovalStatus
import fi.italeino.aidos.engine.approval.InMemoryAppApprovalStore
import fi.italeino.aidos.engine.handshake.HandshakeCore
import fi.italeino.aidos.engine.handshake.HandshakeWire
import fi.italeino.aidos.engine.handshake.handshakeCapabilities
import fi.italeino.aidos.engine.inference.InferenceRequestManager
import fi.italeino.aidos.engine.inference.Locality
import fi.italeino.aidos.engine.license.LicenseNotAcceptedException
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URI
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val hardeningJson = Json {
    encodeDefaults = true
    ignoreUnknownKeys = true
}

/**
 * Hardening tests for [EngineHttpServer] (PIPELINE.md §2.7):
 * - Request size and generation limits
 * - Per-request timeout policy and native cancellation propagation
 * - Sanitized error responses (never leaking paths, stack traces, native internals, secrets, or prompts)
 * - Consistent status/error classification across all endpoints and model management routes
 * - Concurrent multi-client isolation and full service-restart lifecycle verification
 */
class EngineHttpServerHardeningTest {

    // -------------------------------------------------------------------------
    // 1. Request Size Limits & Generation Limits
    // -------------------------------------------------------------------------

    @Test
    fun requestSizeLimit_rejectsOversizedPayloadWith413AcrossEndpoints() = testApplication {
        val policy = EngineApiPolicy(maxRequestBytes = 256)
        val tokenManager = TokenManager()
        val server = EngineHttpServer(tokenManager, MockModelRuntime(), policy = policy)
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken().token

        val oversizedContent = "x".repeat(400)
        val chatResp = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-model",
                        messages = listOf(ChatMessage(role = "user", content = oversizedContent))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, chatResp.status)
        assertTrue(chatResp.bodyAsText().contains("\"code\":\"request_too_large\""))

        val embedResp = client.post("/v1/embeddings") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    EmbeddingsRequest(model = "test-model", input = listOf(oversizedContent))
                )
            )
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, embedResp.status)
        assertTrue(embedResp.bodyAsText().contains("\"code\":\"request_too_large\""))
    }

    @Test
    fun chatCompletions_enforcesGenerationAndPromptLimitsAndAppliesSafeDefaults() = testApplication {
        var capturedRequest: ModelRequest? = null
        val capturingAdapter = object : ModelAdapter by MockModelAdapter() {
            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
                capturedRequest = request
                return MockModelAdapter().invoke(request)
            }
        }
        val policy = EngineApiPolicy(
            defaultMaxOutputTokens = 512,
            maxOutputTokensLimit = 1024,
            maxMessagesCount = 3,
            maxPromptChars = 100,
        )
        val tokenManager = TokenManager()
        val server = EngineHttpServer(tokenManager, MockModelRuntime(capturingAdapter), policy = policy)
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken().token

        // 1. Safe default applied when max_tokens is omitted
        val defaultResp = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-model",
                        messages = listOf(ChatMessage(role = "user", content = "short"))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, defaultResp.status)
        assertEquals(512, capturedRequest?.maxOutputTokens)

        // 2. Rejects max_tokens > maxOutputTokensLimit
        val overGenLimit = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-model",
                        messages = listOf(ChatMessage(role = "user", content = "short")),
                        max_tokens = 2048
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, overGenLimit.status)
        val overGenBody = overGenLimit.bodyAsText()
        assertTrue(overGenBody.contains("\"code\":\"generation_limit_exceeded\""), overGenBody)
        assertTrue(overGenBody.contains("\"param\":\"max_tokens\""), overGenBody)

        // 3. Rejects non-positive max_tokens
        val zeroTokens = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-model",
                        messages = listOf(ChatMessage(role = "user", content = "short")),
                        max_tokens = 0
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, zeroTokens.status)
        assertTrue(zeroTokens.bodyAsText().contains("\"param\":\"max_tokens\""))

        // 4. Rejects too many messages
        val tooManyMessages = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-model",
                        messages = List(4) { ChatMessage(role = "user", content = "hi") }
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, tooManyMessages.status)
        assertTrue(tooManyMessages.bodyAsText().contains("\"code\":\"request_limit_exceeded\""))

        // 5. Rejects prompt exceeding maxPromptChars
        val promptTooLong = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-model",
                        messages = listOf(ChatMessage(role = "user", content = "a".repeat(120)))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, promptTooLong.status)
        assertTrue(promptTooLong.bodyAsText().contains("\"code\":\"request_limit_exceeded\""))

        // 6. Rejects empty messages or out-of-range temperature
        val emptyMsgs = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(hardeningJson.encodeToString(ChatCompletionRequest(model = "test-model", messages = emptyList())))
        }
        assertEquals(HttpStatusCode.BadRequest, emptyMsgs.status)

        val badTemp = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-model",
                        messages = listOf(ChatMessage(role = "user", content = "hi")),
                        temperature = 3.5f
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.BadRequest, badTemp.status)
        assertTrue(badTemp.bodyAsText().contains("\"param\":\"temperature\""))
    }

    @Test
    fun embeddingsAndTranscriptions_enforceInputAndAudioLimits() = testApplication {
        val policy = EngineApiPolicy(
            maxEmbeddingInputs = 2,
            maxEmbeddingInputChars = 20,
            maxAudioBytes = 16,
        )
        val tokenManager = TokenManager()
        val server = EngineHttpServer(tokenManager, MockModelRuntime(), policy = policy)
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken().token

        val tooManyInputs = client.post("/v1/embeddings") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(hardeningJson.encodeToString(EmbeddingsRequest(model = "test-model", input = listOf("a", "b", "c"))))
        }
        assertEquals(HttpStatusCode.BadRequest, tooManyInputs.status)
        assertTrue(tooManyInputs.bodyAsText().contains("\"code\":\"request_limit_exceeded\""))

        val tooManyChars = client.post("/v1/embeddings") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(hardeningJson.encodeToString(EmbeddingsRequest(model = "test-model", input = listOf("012345678901234567891"))))
        }
        assertEquals(HttpStatusCode.BadRequest, tooManyChars.status)
        assertTrue(tooManyChars.bodyAsText().contains("\"code\":\"request_limit_exceeded\""))

        val oversizedAudio = Base64.getEncoder().encodeToString(ByteArray(32) { 1 })
        val audioResp = client.post("/v1/audio/transcriptions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(hardeningJson.encodeToString(TranscriptionRequest(file = oversizedAudio, model = "test-model")))
        }
        assertEquals(HttpStatusCode.PayloadTooLarge, audioResp.status)
        assertTrue(audioResp.bodyAsText().contains("\"code\":\"request_too_large\""))
    }

    // -------------------------------------------------------------------------
    // 2. Timeout Policy & Native Cancellation Propagation
    // -------------------------------------------------------------------------

    @Test
    fun timeoutPolicy_cancelsStalledInferenceReturns504AndFreesAdmissionSlot() = testApplication {
        val cancelCalled = AtomicInteger(0)
        var fastMode = false
        val slowAdapter = object : CancellableModelAdapter {
            override val providerId = "test"
            override val modelId = "test-model"
            override val modelVersion = "1.0"
            override val contextWindow = 2048
            override val isLocal = true
            override fun supportsNativeToolCalls() = false

            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
                if (!fastMode) {
                    delay(5_000)
                }
                return Result.success(
                    ModelResponse(
                        outputs = listOf(TextOutput("recovered")),
                        stopReason = StopReason.END_TURN,
                        usage = Usage(1, 1, 2),
                        model = ModelRef(modelId, modelVersion)
                    )
                )
            }

            override fun cancelCurrentInference() {
                cancelCalled.incrementAndGet()
            }
        }

        val runtime = MockModelRuntime(slowAdapter)
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 1, maxQueuedRequests = 1)
        val policy = EngineApiPolicy(requestTimeoutMs = 120L)
        val tokenManager = TokenManager()
        val server = EngineHttpServer(tokenManager, runtime, manager, policy = policy)
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken().token

        val timedOut = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-model",
                        messages = listOf(ChatMessage(role = "user", content = "stall"))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.GatewayTimeout, timedOut.status)
        val body = timedOut.bodyAsText()
        assertTrue(body.contains("\"type\":\"timeout_error\""), body)
        assertTrue(body.contains("\"code\":\"request_timeout\""), body)
        assertEquals(1, cancelCalled.get(), "timeout must cancel the underlying CancellableModelAdapter")

        // Verify the admission slot was released and subsequent requests succeed
        fastMode = true
        val followUp = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-model",
                        messages = listOf(ChatMessage(role = "user", content = "quick"))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, followUp.status)
        assertTrue(followUp.bodyAsText().contains("recovered"))
    }

    // -------------------------------------------------------------------------
    // 3. Error Sanitization & Status/Error Classification
    // -------------------------------------------------------------------------

    @Test
    fun errorResponses_neverLeakPathsStackTracesNativePointersSecretsOrPrompts() = testApplication {
        val secretPrompt = "MY_SUPER_SECRET_PRIVATE_KEY_PROMPT_998877"
        val leakyError = RuntimeException(
            "java.io.IOException: Failed at /data/user/0/fi.italeino.aidos.engine/files/models/qwen2.5.gguf " +
                "and C:\\Users\\jsilv\\models\\secret.onnx [0x7ff81234abcd] token=hf_SecretTokenValue123456 " +
                "for input $secretPrompt\n\tat fi.italeino.aidos.Native.invoke(Native.kt:142)\n\tat java.lang.Thread.run(Thread.java:100)"
        )
        val leakyAdapter = object : ModelAdapter by MockModelAdapter() {
            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = Result.failure(leakyError)
            override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
                emit(ModelStreamEvent.Failed(leakyError))
            }
        }
        val tokenManager = TokenManager()
        val server = EngineHttpServer(tokenManager, MockModelRuntime(leakyAdapter))
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken().token

        // Non-streaming chat completion error sanitization
        val chatResp = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-model",
                        messages = listOf(ChatMessage(role = "user", content = secretPrompt))
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.InternalServerError, chatResp.status)
        val chatBody = chatResp.bodyAsText()
        assertSanitized(chatBody, secretPrompt)

        // Streaming chat completion error sanitization
        val streamResp = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody(
                hardeningJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-model",
                        messages = listOf(ChatMessage(role = "user", content = secretPrompt)),
                        stream = true
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, streamResp.status)
        val streamBody = streamResp.bodyAsText()
        assertSanitized(streamBody, secretPrompt)
        assertTrue(streamBody.trim().endsWith("data: [DONE]"))

        // Malformed JSON containing a secret prompt must never echo the prompt back
        val malformedResp = client.post("/v1/chat/completions") {
            bearerAuth(token)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"test-model","messages":[{"role":"user","content":"$secretPrompt"BROKEN_JSON""")
        }
        assertEquals(HttpStatusCode.BadRequest, malformedResp.status)
        val malformedBody = malformedResp.bodyAsText()
        assertSanitized(malformedBody, secretPrompt)
        assertTrue(malformedBody.contains("\"code\":\"invalid_json\""), malformedBody)
    }

    @Test
    fun classifyError_distinguishesAllEngineFailureCategories() {
        val server = EngineHttpServer(TokenManager(), MockModelRuntime())

        val (notInstalledStatus, notInstalledDetail) = server.classifyError(IllegalStateException("MODEL_NOT_INSTALLED: foo"))
        assertEquals(HttpStatusCode.NotFound, notInstalledStatus)
        assertEquals("model_not_installed", notInstalledDetail.code)

        val (memStatus, memDetail) = server.classifyError(IllegalStateException("INSUFFICIENT_MEMORY: need 4GB"))
        assertEquals(HttpStatusCode.ServiceUnavailable, memStatus)
        assertEquals("insufficient_memory", memDetail.code)

        val (nativeStatus, nativeDetail) = server.classifyError(UnsatisfiedLinkError("dlopen failed: /data/app/libjllama.so not found"))
        assertEquals(HttpStatusCode.InternalServerError, nativeStatus)
        assertEquals("native_failure", nativeDetail.code)
        assertFalse(nativeDetail.message.contains("/data/app"))
        assertFalse(nativeDetail.message.contains("libjllama.so"))

        val (unsupportedStatus, unsupportedDetail) = server.classifyError(UnsupportedOperationException("Model x does not support embeddings"))
        assertEquals(HttpStatusCode.BadRequest, unsupportedStatus)
        assertEquals("unsupported_capability", unsupportedDetail.code)

        val (timeoutStatus, timeoutDetail) = server.classifyError(EngineRequestTimeoutException("timed out"))
        assertEquals(HttpStatusCode.GatewayTimeout, timeoutStatus)
        assertEquals("request_timeout", timeoutDetail.code)

        val (licenseStatus, licenseDetail) = server.classifyError(
            LicenseNotAcceptedException("test-model", "1.0", "apache-2.0", "digest")
        )
        assertEquals(HttpStatusCode(451, "Unavailable For Legal Reasons"), licenseStatus)
        assertEquals("license_not_accepted", licenseDetail.code)
    }

    // -------------------------------------------------------------------------
    // 4. Model Management Endpoints & Lifecycle Boundary Enforcement
    // -------------------------------------------------------------------------

    @Test
    fun modelManagementEndpoints_requireAuthAndEnforceLifecycleManager() = testApplication {
        val runtime = StatefulModelRuntime()
        val manager = InferenceRequestManager(runtime)
        val tokenManager = TokenManager()
        val server = EngineHttpServer(tokenManager, runtime, manager)
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken().token

        // Unauthenticated calls are rejected
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/models/model-a/open").status)
        assertEquals(HttpStatusCode.Unauthorized, client.post("/v1/models/model-a/close").status)
        assertEquals(HttpStatusCode.Unauthorized, client.delete("/v1/models/model-a").status)

        // Open loads the model via InferenceRequestManager
        val openResp = client.post("/v1/models/model-a/open") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, openResp.status)
        assertTrue(runtime.loaded().contains("model-a"))

        // Close unloads the model while keeping it installed
        val closeResp = client.post("/v1/models/model-a/close") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, closeResp.status)
        assertFalse(runtime.loaded().contains("model-a"))
        assertTrue(runtime.installed().any { it.id == "model-a" })

        // Delete unloads and deletes the installed artifact
        val deleteResp = client.delete("/v1/models/model-a") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, deleteResp.status)
        assertFalse(runtime.installed().any { it.id == "model-a" })

        // Subsequent open on deleted model returns 404 model_not_installed
        val missingOpen = client.post("/v1/models/model-a/open") { bearerAuth(token) }
        assertEquals(HttpStatusCode.NotFound, missingOpen.status)
        assertTrue(missingOpen.bodyAsText().contains("\"code\":\"model_not_installed\""))
    }

    // -------------------------------------------------------------------------
    // 5. Concurrent Clients & Service-Restart Lifecycle Tests
    // -------------------------------------------------------------------------

    @Test
    fun concurrentClients_executeSafelyAndRespectPerSubjectRevocation() = testApplication {
        val store = InMemoryAppApprovalStore()
        val clients = listOf("app.one", "app.two", "app.three")
        clients.forEach { pkg ->
            store.recordFirstHandshake(pkg, pkg)
            store.approveApp(pkg)
        }

        val activeCount = AtomicInteger(0)
        val maxObservedConcurrency = AtomicInteger(0)
        val adapter = object : ModelAdapter by MockModelAdapter() {
            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
                val now = activeCount.incrementAndGet()
                maxObservedConcurrency.updateAndGet { maxOf(it, now) }
                delay(15)
                activeCount.decrementAndGet()
                return MockModelAdapter().invoke(request)
            }
        }

        val runtime = MockModelRuntime(adapter)
        val manager = InferenceRequestManager(runtime, maxConcurrentRequests = 2, maxQueuedRequests = 16)
        val tokenManager = TokenManager()
        val server = EngineHttpServer(
            tokenManager,
            runtime,
            manager,
            isSubjectApproved = { pkg -> store.getApproval(pkg)?.status == AppApprovalStatus.APPROVED }
        )
        application { server.installInto(this) }

        val tokensByApp = clients.associateWith { pkg -> tokenManager.generateNewToken(subject = pkg).token }

        coroutineScope {
            val jobs = (1..12).map { idx ->
                val pkg = clients[idx % clients.size]
                val token = tokensByApp.getValue(pkg)
                async {
                    client.post("/v1/chat/completions") {
                        bearerAuth(token)
                        contentType(ContentType.Application.Json)
                        setBody(
                            hardeningJson.encodeToString(
                                ChatCompletionRequest(
                                    model = "test-model",
                                    messages = listOf(ChatMessage(role = "user", content = "req-$idx"))
                                )
                            )
                        )
                    }
                }
            }
            val responses = jobs.awaitAll()
            assertTrue(responses.all { it.status == HttpStatusCode.OK })
        }

        // Per-model mutex serializes generation on the same model even when maxConcurrentRequests > 1
        assertEquals(1, maxObservedConcurrency.get(), "same-model generation must remain serialized")
        assertEquals(12L, manager.snapshotMetrics().completedRequests)

        // Revoking app.two immediately blocks app.two while app.one and app.three continue working
        store.revokeApproval("app.two")
        val revokedResp = client.get("/v1/models") { bearerAuth(tokensByApp.getValue("app.two")) }
        assertEquals(HttpStatusCode.Unauthorized, revokedResp.status)

        val stillApprovedOne = client.get("/v1/models") { bearerAuth(tokensByApp.getValue("app.one")) }
        val stillApprovedThree = client.get("/v1/models") { bearerAuth(tokensByApp.getValue("app.three")) }
        assertEquals(HttpStatusCode.OK, stillApprovedOne.status)
        assertEquals(HttpStatusCode.OK, stillApprovedThree.status)
    }

    @Test
    fun serviceRestart_drainsInFlightWorkInvalidatesStaleTokensAndServesFreshSession() = runBlocking {
        val store = InMemoryAppApprovalStore()
        val appPkg = "fi.italeino.aidos.dictator"
        store.recordFirstHandshake(appPkg, "Dictator")
        store.approveApp(appPkg)

        val startedGate = CompletableDeferred<Unit>()
        val cancelGate = CompletableDeferred<Unit>()
        val cancelCount = AtomicInteger(0)
        val unloadCount = AtomicInteger(0)

        val adapter = object : CancellableModelAdapter {
            override val providerId = "test"
            override val modelId = "test-model"
            override val modelVersion = "1.0"
            override val contextWindow = 2048
            override val isLocal = true
            override fun supportsNativeToolCalls() = false

            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
                val prompt = (request.messages.firstOrNull() as? dev.aidos.kernel.Turn.User)
                    ?.content?.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>()?.firstOrNull()?.text.orEmpty()
                if (prompt == "block-until-restart") {
                    startedGate.complete(Unit)
                    cancelGate.await()
                    throw kotlinx.coroutines.CancellationException("cancelled by restart")
                }
                return Result.success(
                    ModelResponse(
                        outputs = listOf(TextOutput("pong:$prompt")),
                        stopReason = StopReason.END_TURN,
                        usage = Usage(1, 1, 2),
                        model = ModelRef(modelId, modelVersion)
                    )
                )
            }

            override fun cancelCurrentInference() {
                cancelCount.incrementAndGet()
                cancelGate.complete(Unit)
            }
        }

        val runtime = object : ModelRuntime by MockModelRuntime(adapter) {
            override suspend fun unload(modelId: String) {
                unloadCount.incrementAndGet()
            }
        }

        // --- Generation 1 of EngineService ---
        val tokensGen1 = TokenManager()
        val managerGen1 = InferenceRequestManager(runtime)
        val serverGen1 = EngineHttpServer(
            tokensGen1,
            runtime,
            managerGen1,
            port = 0,
            isSubjectApproved = { pkg -> store.getApproval(pkg)?.status == AppApprovalStatus.APPROVED }
        )
        serverGen1.start()
        val portGen1 = serverGen1.getBoundPort()!!
        val coreGen1 = HandshakeCore(
            store = store,
            tokenManager = tokensGen1,
            boundPort = { serverGen1.getBoundPort() },
            capabilities = { runtime.handshakeCapabilities() }
        )

        try {
            val handshake1 = coreGen1.handshake(appPkg)
            assertEquals(HandshakeWire.STATUS_APPROVED, handshake1.status)
            assertEquals(portGen1, handshake1.port)

            // Start an in-flight request that blocks until restart drains it
            val inFlight = async(kotlinx.coroutines.Dispatchers.IO) {
                runCatching {
                    postChatHttp(portGen1, handshake1.token, "block-until-restart")
                }
            }
            startedGate.await()

            // Simulate EngineService.onDestroy teardown
            assertTrue(serverGen1.shutdownInference(timeoutMs = 2_000L))
            runtime.loaded().forEach { modelId ->
                assertTrue(serverGen1.waitUntilModelIdle(modelId, timeoutMs = 2_000L))
                runtime.unload(modelId)
            }
            tokensGen1.clearTokens()
            serverGen1.stop()
            inFlight.await()

            assertEquals(1, cancelCount.get(), "in-flight inference must be cancelled on service stop")
            assertEquals(1, unloadCount.get(), "loaded models must be unloaded after draining")

            // --- Generation 2 of EngineService (restarted) ---
            val tokensGen2 = TokenManager()
            val managerGen2 = InferenceRequestManager(runtime)
            val serverGen2 = EngineHttpServer(
                tokensGen2,
                runtime,
                managerGen2,
                port = 0,
                isSubjectApproved = { pkg -> store.getApproval(pkg)?.status == AppApprovalStatus.APPROVED }
            )
            serverGen2.start()
            val portGen2 = serverGen2.getBoundPort()!!
            val coreGen2 = HandshakeCore(
                store = store,
                tokenManager = tokensGen2,
                boundPort = { serverGen2.getBoundPort() },
                capabilities = { runtime.handshakeCapabilities() }
            )

            try {
                // Stale token from Generation 1 must be rejected with 401 on Generation 2
                val staleStatus = getModelsStatusHttp(portGen2, handshake1.token)
                assertEquals(401, staleStatus)

                // Re-handshake against Generation 2 issues a fresh token that works immediately
                val handshake2 = coreGen2.handshake(appPkg)
                assertEquals(HandshakeWire.STATUS_APPROVED, handshake2.status)
                assertNotEquals(handshake1.token, handshake2.token)

                val (status2, body2) = postChatHttp(portGen2, handshake2.token, "after-restart")
                assertEquals(200, status2)
                assertTrue(body2.contains("pong:after-restart"), body2)
            } finally {
                serverGen2.shutdownInference()
                serverGen2.stop()
            }
        } finally {
            serverGen1.stop()
        }
    }

    private fun assertSanitized(body: String, secretPrompt: String) {
        assertFalse(body.contains(secretPrompt), "Error response leaked prompt: $body")
        assertFalse(body.contains("/data/user"), "Error response leaked Unix path: $body")
        assertFalse(body.contains("C:\\Users"), "Error response leaked Windows path: $body")
        assertFalse(body.contains(".gguf"), "Error response leaked GGUF filename: $body")
        assertFalse(body.contains(".onnx"), "Error response leaked ONNX filename: $body")
        assertFalse(body.contains("0x7ff8"), "Error response leaked memory address: $body")
        assertFalse(body.contains("hf_SecretToken"), "Error response leaked secret token: $body")
        assertFalse(body.contains("Native.kt"), "Error response leaked stack trace frame: $body")
    }

    private fun getModelsStatusHttp(port: Int, token: String): Int {
        val conn = URI("http://127.0.0.1:$port/v1/models").toURL().openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "GET"
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            conn.responseCode
        } finally {
            conn.disconnect()
        }
    }

    private fun postChatHttp(port: Int, token: String, content: String): Pair<Int, String> {
        val conn = URI("http://127.0.0.1:$port/v1/chat/completions").toURL().openConnection() as HttpURLConnection
        return try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-Aidos-Locality", Locality.LOCAL_ONLY.wire)
            conn.connectTimeout = 2_000
            conn.readTimeout = 2_000
            val payload = hardeningJson.encodeToString(
                ChatCompletionRequest(
                    model = "test-model",
                    messages = listOf(ChatMessage(role = "user", content = content))
                )
            )
            conn.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.readText().orEmpty()
            code to body
        } finally {
            conn.disconnect()
        }
    }
}

private class StatefulModelRuntime : ModelRuntime {
    private val installedSet = ConcurrentHashMap.newKeySet<String>().apply { add("model-a") }
    private val loadedSet = ConcurrentHashMap.newKeySet<String>()
    private val adapter = MockModelAdapter()

    private fun descriptor(id: String) = ModelDescriptor(
        id = id,
        name = "Model $id",
        kind = ModelKind.LLM,
        providerId = "local",
        isLocal = true,
        contextWindow = 2048,
        sizeBytes = 1024L,
        digest = "sha256-model-a"
    )

    override suspend fun catalog(): List<ModelDescriptor> = listOf(descriptor("model-a"))

    override suspend fun installed(): List<ModelDescriptor> = installedSet.map(::descriptor)

    override suspend fun load(modelId: String): Result<ModelAdapter> {
        if (!installedSet.contains(modelId)) {
            return Result.failure(IllegalStateException("MODEL_NOT_INSTALLED: $modelId"))
        }
        loadedSet.add(modelId)
        return Result.success(adapter)
    }

    override suspend fun unload(modelId: String) {
        loadedSet.remove(modelId)
    }

    override suspend fun delete(modelId: String) {
        loadedSet.remove(modelId)
        installedSet.remove(modelId)
    }

    override fun loaded(): List<String> = loadedSet.toList()
}
