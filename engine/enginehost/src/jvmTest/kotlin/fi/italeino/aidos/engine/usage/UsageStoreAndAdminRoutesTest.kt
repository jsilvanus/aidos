package fi.italeino.aidos.engine.usage

import dev.aidos.decisions.DecisionModelAdapter
import dev.aidos.decisions.DecisionRequest
import dev.aidos.decisions.DecisionResult
import dev.aidos.decisions.DecisionUsage
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
import dev.aidos.stt.SttModelAdapter
import dev.aidos.stt.SttRequest
import dev.aidos.stt.SttResult
import fi.italeino.aidos.engine.http.ChatCompletionRequest
import fi.italeino.aidos.engine.http.ChatMessage
import fi.italeino.aidos.engine.http.EmbeddingsRequest
import fi.italeino.aidos.engine.http.EngineHttpServer
import fi.italeino.aidos.engine.http.TokenManager
import fi.italeino.aidos.engine.http.TranscriptionRequest
import fi.italeino.aidos.engine.http.UsageExportJson
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

private val usageTestJson = Json { ignoreUnknownKeys = true; encodeDefaults = true }

class UsageStoreAndAdminRoutesTest {

    @Test
    fun inMemoryUsageStore_tracksSessionLifetimeLastUsageAndLoad() {
        val store = InMemoryUsageStore(
            initialLifetime = mapOf(UsageCategory.CHAT to CategoryUsage(requests = 3L, tokens = 100L)),
            maxConcurrency = 4,
        )
        store.record(UsageCategory.CHAT, tokens = 42L, timestampMs = 1000L)
        store.record(UsageCategory.VISION, tokens = 55L, images = 2L, timestampMs = 2000L)
        store.record(UsageCategory.STT, audioSeconds = 3.5, timestampMs = 3000L)
        store.updateLoad(activeRequests = 2, queuedRequests = 1, maxConcurrency = 4)

        val snap = store.snapshot()
        assertEquals(2, snap.activeRequests)
        assertEquals(1, snap.queuedRequests)
        assertEquals(4, snap.maxConcurrency)
        assertEquals(UsageCategory.STT, snap.lastUsage?.category)
        assertEquals("3.5s audio", snap.lastUsage?.summary)
        assertEquals(3000L, snap.lastUsage?.timestampMs)

        assertEquals(1L, snap.session[UsageCategory.CHAT]?.requests)
        assertEquals(42L, snap.session[UsageCategory.CHAT]?.tokens)
        assertEquals(4L, snap.lifetime[UsageCategory.CHAT]?.requests)
        assertEquals(142L, snap.lifetime[UsageCategory.CHAT]?.tokens)

        assertEquals(1L, snap.session[UsageCategory.VISION]?.requests)
        assertEquals(2L, snap.session[UsageCategory.VISION]?.images)
        assertEquals(55L, snap.session[UsageCategory.VISION]?.tokens)
        assertEquals(3.5, snap.session[UsageCategory.STT]?.audioSeconds ?: 0.0, 0.001)
    }

    @Test
    fun adminUsageEndpoints_enforceAdminGuardAndExportJsonAndCsv() = testApplication {
        val tokenManager = TokenManager()
        val usageStore = InMemoryUsageStore(maxConcurrency = 2)
        usageStore.record(UsageCategory.CHAT, tokens = 120L, timestampMs = 10_000L)
        usageStore.record(UsageCategory.STT, audioSeconds = 4.2, timestampMs = 20_000L)

        val server = EngineHttpServer(tokenManager, UsageTestRuntime(), usageStore = usageStore)
        application { server.installInto(this) }

        val appToken = tokenManager.generateNewToken(subject = "com.example.app").token
        val adminToken = tokenManager.generateNewToken(subject = TokenManager.ENGINE_SUBJECT).token

        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/usage") { bearerAuth(appToken) }.status)
        assertEquals(HttpStatusCode.Forbidden, client.get("/v1/admin/usage/export?format=json") { bearerAuth(appToken) }.status)

        val usageResp = client.get("/v1/admin/usage") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, usageResp.status)
        val snap = usageTestJson.decodeFromString<EngineUsageSnapshot>(usageResp.bodyAsText())
        assertEquals(1L, snap.session[UsageCategory.CHAT]?.requests)
        assertEquals(120L, snap.session[UsageCategory.CHAT]?.tokens)
        assertEquals(1L, snap.session[UsageCategory.STT]?.requests)

        val jsonExportResp = client.get("/v1/admin/usage/export?format=json") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, jsonExportResp.status)
        val export = usageTestJson.decodeFromString<UsageExportJson>(jsonExportResp.bodyAsText())
        assertEquals("1.0", export.metadata.exportVersion)
        assertTrue(export.metadata.exportedAt.isNotBlank())
        assertTrue(export.metadata.engineVersion.isNotBlank())
        assertEquals(120L, export.session[UsageCategory.CHAT]?.tokens)
        assertEquals(120L, export.lifetime[UsageCategory.CHAT]?.tokens)

        val csvExportResp = client.get("/v1/admin/usage/export?format=csv") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.OK, csvExportResp.status)
        val csvLines = csvExportResp.bodyAsText().trim().lines()
        assertEquals("scope,category,requests,tokens,audio_seconds,images", csvLines.first())
        assertTrue(csvLines.any { it == "session,chat,1,120,0.000,0" })
        assertTrue(csvLines.any { it == "lifetime,stt,1,0,4.200,0" })

        val badFormat = client.get("/v1/admin/usage/export?format=xml") { bearerAuth(adminToken) }
        assertEquals(HttpStatusCode.BadRequest, badFormat.status)
    }

    @Test
    fun engineHttpServer_recordsUsageAcrossAllInferenceEndpoints() = testApplication {
        val tokenManager = TokenManager()
        val usageStore = InMemoryUsageStore(maxConcurrency = 1)
        val server = EngineHttpServer(tokenManager, UsageTestRuntime(), usageStore = usageStore)
        application { server.installInto(this) }

        val clientToken = tokenManager.generateNewToken().token

        // 1. CHAT
        val chatResp = client.post("/v1/chat/completions") {
            bearerAuth(clientToken)
            contentType(ContentType.Application.Json)
            setBody(
                usageTestJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-llm",
                        messages = listOf(ChatMessage(role = "user", content = "hello")),
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, chatResp.status)

        // 2. VISION (chat with images)
        val imgB64 = Base64.getEncoder().encodeToString(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2))
        val visionResp = client.post("/v1/chat/completions") {
            bearerAuth(clientToken)
            contentType(ContentType.Application.Json)
            setBody(
                usageTestJson.encodeToString(
                    ChatCompletionRequest(
                        model = "test-llm",
                        messages = listOf(ChatMessage(role = "user", content = "describe", images = listOf(imgB64))),
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, visionResp.status)

        // 3. EMBEDDINGS
        val embedResp = client.post("/v1/embeddings") {
            bearerAuth(clientToken)
            contentType(ContentType.Application.Json)
            setBody(usageTestJson.encodeToString(EmbeddingsRequest(model = "test-embed", input = listOf("hello world"))))
        }
        assertEquals(HttpStatusCode.OK, embedResp.status)

        // 4. DECISIONS
        val decisionResp = client.post("/v1/decisions") {
            bearerAuth(clientToken)
            contentType(ContentType.Application.Json)
            setBody("""{"model":"test-decision","state":"user asks about weather","questions":{"intent":{"type":"noul","instructions":"answer?"}}}""")
        }
        assertEquals(HttpStatusCode.OK, decisionResp.status)

        // 5. STT
        val pcmBytes = ByteArray(32000) // 1.0s at 16kHz 16-bit mono
        val sttResp = client.post("/v1/audio/transcriptions") {
            bearerAuth(clientToken)
            contentType(ContentType.Application.Json)
            setBody(
                usageTestJson.encodeToString(
                    TranscriptionRequest(
                        model = "test-stt",
                        file = Base64.getEncoder().encodeToString(pcmBytes),
                    )
                )
            )
        }
        assertEquals(HttpStatusCode.OK, sttResp.status)

        val snap = usageStore.snapshot()
        assertEquals(1L, snap.session[UsageCategory.CHAT]?.requests)
        assertEquals(15L, snap.session[UsageCategory.CHAT]?.tokens)
        assertEquals(1L, snap.session[UsageCategory.VISION]?.requests)
        assertEquals(1L, snap.session[UsageCategory.VISION]?.images)
        assertEquals(15L, snap.session[UsageCategory.VISION]?.tokens)
        assertEquals(1L, snap.session[UsageCategory.EMBEDDINGS]?.requests)
        assertTrue((snap.session[UsageCategory.EMBEDDINGS]?.tokens ?: 0L) > 0L)
        assertEquals(1L, snap.session[UsageCategory.DECISIONS]?.requests)
        assertTrue((snap.session[UsageCategory.DECISIONS]?.tokens ?: 0L) > 0L)
        assertEquals(1L, snap.session[UsageCategory.STT]?.requests)
        assertEquals(1.0, snap.session[UsageCategory.STT]?.audioSeconds ?: 0.0, 0.01)
        assertNotNull(snap.lastUsage)
        assertEquals(UsageCategory.STT, snap.lastUsage?.category)
    }

    private class UsageTestRuntime : ModelRuntime {
        private val models = listOf(
            ModelDescriptor(
                id = "test-llm",
                name = "Test LLM",
                kind = ModelKind.LLM,
                providerId = "mock",
                isLocal = true,
                contextWindow = 4096,
                sizeBytes = 1024L,
                digest = "test-llm-digest",
            ),
            ModelDescriptor(
                id = "test-embed",
                name = "Test Embed",
                kind = ModelKind.EMBEDDING,
                providerId = "mock",
                isLocal = true,
                contextWindow = 512,
                sizeBytes = 1024L,
                digest = "test-embed-digest",
            ),
            ModelDescriptor(
                id = "test-decision",
                name = "Test Decision",
                kind = ModelKind.LLM,
                providerId = "mock",
                isLocal = true,
                contextWindow = 512,
                sizeBytes = 1024L,
                digest = "test-decision-digest",
            ),
            ModelDescriptor(
                id = "test-stt",
                name = "Test STT",
                kind = ModelKind.STT,
                providerId = "mock",
                isLocal = true,
                contextWindow = 0,
                sizeBytes = 1024L,
                digest = "test-stt-digest",
            ),
        )

        override suspend fun catalog(): List<ModelDescriptor> = models
        override suspend fun installed(): List<ModelDescriptor> = models
        override suspend fun load(modelId: String): Result<ModelAdapter> = when (modelId) {
            "test-llm" -> Result.success(ChatAdapter(modelId))
            "test-embed" -> Result.success(EmbedAdapter(modelId))
            "test-decision" -> Result.success(DecisionAdapter(modelId))
            "test-stt" -> Result.success(SttAdapter(modelId))
            else -> Result.failure(NoSuchElementException(modelId))
        }
        override suspend fun unload(modelId: String) = Unit
        override fun loaded(): List<String> = emptyList()
        override suspend fun delete(modelId: String) = Unit
    }

    private class ChatAdapter(override val modelId: String) : ModelAdapter {
        override val providerId = "mock"
        override val modelVersion = "1.0"
        override val contextWindow = 4096
        override val isLocal = true
        override fun supportsNativeToolCalls() = false
        override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = Result.success(
            ModelResponse(
                model = ModelRef("mock", modelId),
                outputs = listOf(TextOutput("ok")),
                stopReason = StopReason.END_TURN,
                usage = Usage(inputTokens = 10, outputTokens = 5, totalTokens = 15),
            )
        )
        override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
            emit(ModelStreamEvent.Done(invoke(request).getOrThrow()))
        }
    }

    private class EmbedAdapter(override val modelId: String) : EmbeddingModelAdapter {
        override val providerId = "mock"
        override val modelVersion = "1.0"
        override val contextWindow = 512
        override val isLocal = true
        override fun supportsNativeToolCalls() = false
        override suspend fun embed(text: String): Result<FloatArray> = Result.success(floatArrayOf(0.5f, -0.5f))
        override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = Result.failure(UnsupportedOperationException())
        override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {}
    }

    private class DecisionAdapter(override val modelId: String) : DecisionModelAdapter {
        override val providerId = "mock"
        override val modelVersion = "1.0"
        override val contextWindow = 512
        override val isLocal = true
        override fun supportsNativeToolCalls() = false
        override suspend fun decide(request: DecisionRequest): Result<DecisionResult> = Result.success(
            DecisionResult(
                model = modelId,
                answers = emptyMap(),
                usage = DecisionUsage(inputTokens = 12, stateTokens = 8, stateTokensDropped = 0),
            )
        )
        override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = Result.failure(UnsupportedOperationException())
        override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {}
    }

    private class SttAdapter(override val modelId: String) : SttModelAdapter {
        override val providerId = "mock"
        override val modelVersion = "1.0"
        override val contextWindow = 0
        override val isLocal = true
        override fun supportsNativeToolCalls() = false
        override suspend fun transcribe(request: SttRequest): Result<SttResult> = Result.success(
            SttResult(text = "hello", language = "en", durationSeconds = 1.0)
        )
        override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = Result.failure(UnsupportedOperationException())
        override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {}
    }
}
