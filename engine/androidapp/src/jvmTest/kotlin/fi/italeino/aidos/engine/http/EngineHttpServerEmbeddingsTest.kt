package fi.italeino.aidos.engine.http

import dev.aidos.kernel.EmbeddingModelAdapter
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.ModelStreamEvent
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Usage
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

private val embeddingTestJson = Json { encodeDefaults = true }

class EngineHttpServerEmbeddingsTest {
    @Test
    fun embeddings_returnsRealVectorsAndPreservesInputOrder() = testApplication {
        val runtime = EmbeddingTestRuntime()
        val tokenManager = TokenManager()
        val server = EngineHttpServer(tokenManager, runtime)
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/embeddings") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(embeddingTestJson.encodeToString(
                EmbeddingsRequest(
                    model = "test-embedding",
                    input = listOf("alpha", "beta"),
                )
            ))
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"index\":0"))
        assertTrue(body.contains("\"index\":1"))
        assertTrue(body.contains("0.5"))
        assertTrue(body.contains("-0.25"))
        assertTrue(!body.contains("0.0\"]"), "response must not be the old placeholder vector")
    }

    @Test
    fun embeddings_rejectsEmptyInput() = testApplication {
        val runtime = EmbeddingTestRuntime()
        val tokenManager = TokenManager()
        val server = EngineHttpServer(tokenManager, runtime)
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/embeddings") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(embeddingTestJson.encodeToString(
                EmbeddingsRequest(model = "test-embedding", input = emptyList())
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("input must contain at least one string"))
    }

    @Test
    fun embeddings_rejectsNonFloatEncodingFormat() = testApplication {
        val runtime = EmbeddingTestRuntime()
        val tokenManager = TokenManager()
        val server = EngineHttpServer(tokenManager, runtime)
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/embeddings") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(embeddingTestJson.encodeToString(
                EmbeddingsRequest(
                    model = "test-embedding",
                    input = listOf("alpha"),
                    encoding_format = "base64",
                )
            ))
        }

        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertTrue(response.bodyAsText().contains("only float embeddings are supported"))
    }
}

private class EmbeddingTestRuntime : ModelRuntime {
    private val adapter = EmbeddingTestAdapter()

    override suspend fun catalog(): List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "test-embedding",
            name = "Test Embedding",
            kind = ModelKind.EMBEDDING,
            providerId = "test",
            isLocal = true,
            contextWindow = 2048,
            sizeBytes = 1024L,
            digest = "test-embedding-digest",
        )
    )

    override suspend fun installed(): List<ModelDescriptor> = catalog()

    override suspend fun load(modelId: String): Result<ModelAdapter> =
        if (modelId == "test-embedding") Result.success(adapter)
        else Result.failure(IllegalStateException("Model $modelId is not installed"))

    override suspend fun unload(modelId: String) = Unit

    override fun loaded(): List<String> = listOf("test-embedding")
}

private class EmbeddingTestAdapter : EmbeddingModelAdapter {
    override val providerId = "test"
    override val modelId = "test-embedding"
    override val modelVersion = "1.0"
    override val contextWindow = 2048
    override val isLocal = true

    override fun supportsNativeToolCalls() = false

    override suspend fun embed(text: String): Result<FloatArray> =
        Result.success(
            when (text) {
                "alpha" -> floatArrayOf(0.5f, -0.25f, 0.125f, 0.75f)
                "beta" -> floatArrayOf(-0.5f, 0.25f, 0.375f, -0.75f)
                else -> floatArrayOf(0.1f, 0.2f, 0.3f, 0.4f)
            }
        )

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> =
        Result.failure(UnsupportedOperationException("embedding test adapter does not chat"))

    override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        emit(ModelStreamEvent.Failed(UnsupportedOperationException("embedding test adapter does not stream chat")))
    }
}
