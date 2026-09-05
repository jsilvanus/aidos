package fi.italeino.aidos.engine.http

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRuntime
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import io.ktor.server.testing.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private val integrityTestJson = Json { encodeDefaults = true }

class EngineHttpServerIntegrityTest {
    @Test
    fun chatCompletions_integrityMismatchReturnsUnprocessableEntity() = testApplication {
        val tokenManager = TokenManager()
        val runtime = IntegrityFailureRuntime("MODEL_INTEGRITY_MISMATCH: test-model")
        val server = EngineHttpServer(tokenManager, runtime)
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/chat/completions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(integrityTestJson.encodeToString(
                ChatCompletionRequest(
                    model = "test-model",
                    messages = listOf(ChatMessage(role = "user", content = "hello")),
                )
            ))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("model_integrity_mismatch"))
        assertTrue(!body.contains("MODEL_INTEGRITY_MISMATCH"))
    }

    @Test
    fun chatCompletions_missingIntegrityReturnsUnprocessableEntity() = testApplication {
        val tokenManager = TokenManager()
        val runtime = IntegrityFailureRuntime("MODEL_INTEGRITY_MISSING: test-model")
        val server = EngineHttpServer(tokenManager, runtime)
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.post("/v1/chat/completions") {
            bearerAuth(token.token)
            contentType(ContentType.Application.Json)
            setBody(integrityTestJson.encodeToString(
                ChatCompletionRequest(
                    model = "test-model",
                    messages = listOf(ChatMessage(role = "user", content = "hello")),
                )
            ))
        }

        assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
        assertTrue(response.bodyAsText().contains("model_integrity_missing"))
    }

    @Test
    fun models_exposesCatalogMetadataWithoutFilenameInference() = testApplication {
        val tokenManager = TokenManager()
        val runtime = MetadataRuntime()
        val server = EngineHttpServer(tokenManager, runtime)
        application { server.installInto(this) }
        val token = tokenManager.generateNewToken()

        val response = client.get("/v1/models") {
            bearerAuth(token.token)
        }

        assertEquals(HttpStatusCode.OK, response.status)
        val body = response.bodyAsText()
        assertTrue(body.contains("\"format\":\"gguf\""))
        assertTrue(body.contains("\"quantization\":\"Q5_K_M\""))
        assertTrue(body.contains("\"family\":\"test\""))
        assertTrue(!body.contains("\"quantization\":\"q4_k_m\""))
    }
}

private class IntegrityFailureRuntime(private val message: String) : ModelRuntime {
    override suspend fun catalog(): List<ModelDescriptor> = descriptor()
    override suspend fun installed(): List<ModelDescriptor> = descriptor()
    override suspend fun load(modelId: String): Result<ModelAdapter> = Result.failure(IllegalStateException(message))
    override suspend fun unload(modelId: String) = Unit
    override fun loaded(): List<String> = emptyList()

    private fun descriptor() = listOf(
        ModelDescriptor(
            id = "test-model",
            name = "Test Model",
            kind = ModelKind.LLM,
            providerId = "test",
            isLocal = true,
            contextWindow = 2048,
            sizeBytes = 1L,
            digest = "digest",
        )
    )
}

private class MetadataRuntime : ModelRuntime {
    private val descriptor = ModelDescriptor(
        id = "model-q4_k_m",
        name = "Metadata Model",
        kind = ModelKind.LLM,
        providerId = "test",
        isLocal = true,
        contextWindow = 4096,
        sizeBytes = 123L,
        digest = "abc",
        format = "gguf",
        quantization = "Q5_K_M",
        metadata = mapOf("family" to "test"),
    )

    override suspend fun catalog(): List<ModelDescriptor> = listOf(descriptor)
    override suspend fun installed(): List<ModelDescriptor> = listOf(descriptor)
    override suspend fun load(modelId: String): Result<ModelAdapter> = Result.failure(IllegalStateException("unused"))
    override suspend fun unload(modelId: String) = Unit
    override fun loaded(): List<String> = emptyList()
}
