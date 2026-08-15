package fi.italeino.aidos.engine.http

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.request.*
import io.ktor.client.statement.*
import io.ktor.http.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString

/**
 * HTTP client for calling Aidos Engine's /v1/chat/completions endpoint (RFC-0103, Phase E).
 *
 * Wraps HTTP communication with the Engine's local inference server.
 * Handles OpenAI-compatible chat completion requests and responses.
 *
 * Usage:
 * ```
 * val client = HttpModelClient(port = 8000, token = "abc123...")
 * val response = client.chatCompletions(
 *     modelId = "qwen2.5-3b",
 *     messages = listOf(
 *         ChatMessage(role = "user", content = "Hello, how are you?")
 *     )
 * )
 * ```
 */
class HttpModelClient(
    private val port: Int,
    private val token: String,
    private val host: String = "127.0.0.1",
    private val timeout: Long = 30_000L  // 30 seconds default timeout
) {
    private val httpClient = HttpClient()
    private val baseUrl = "http://$host:$port"
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        isLenient = true
    }

    /**
     * Send a chat completion request to the Engine.
     *
     * @param modelId The model to use for inference
     * @param messages List of messages in OpenAI format
     * @param temperature Sampling temperature (0.0-1.0)
     * @param maxTokens Maximum tokens in response
     * @return ChatCompletionResponse with generated text and metrics
     * @throws Exception if the request fails or model is not found
     */
    suspend fun chatCompletions(
        modelId: String,
        messages: List<ChatMessage>,
        temperature: Float = 0.7f,
        maxTokens: Int = 512
    ): ChatCompletionResponse {
        val request = ChatCompletionRequest(
            model = modelId,
            messages = messages,
            temperature = temperature,
            max_tokens = maxTokens
        )

        val response = httpClient.post("$baseUrl/v1/chat/completions") {
            contentType(ContentType.Application.Json)
            bearerAuth(token)
            setBody(json.encodeToString(request))
        }

        return when {
            response.status == HttpStatusCode.NotFound -> {
                throw Exception("Model not found: $modelId")
            }
            response.status == HttpStatusCode.Unauthorized -> {
                throw Exception("Unauthorized: Invalid or expired token")
            }
            response.status.isSuccess() -> {
                response.body<ChatCompletionResponse>()
            }
            else -> {
                val errorBody = response.bodyAsText()
                throw Exception("HTTP ${response.status}: $errorBody")
            }
        }
    }

    /**
     * Check if the Engine is responding and accessible.
     *
     * @return true if health check passes
     */
    suspend fun healthCheck(): Boolean {
        return try {
            val response = httpClient.get("$baseUrl/health")
            response.status.isSuccess()
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Close the HTTP client and release resources.
     */
    fun close() {
        httpClient.close()
    }
}

/**
 * OpenAI-compatible chat message format.
 *
 * @param role "user", "assistant", or "system"
 * @param content The message text
 */
data class ChatMessage(
    val role: String,
    val content: String
)

/**
 * OpenAI-compatible chat completion request.
 *
 * Serialized to JSON for HTTP POST to /v1/chat/completions.
 */
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float = 0.7f,
    val max_tokens: Int = 512,
    val top_p: Float = 1.0f,
    val frequency_penalty: Float = 0.0f,
    val presence_penalty: Float = 0.0f
)

/**
 * OpenAI-compatible chat completion response.
 *
 * Deserialized from JSON response of /v1/chat/completions.
 */
data class ChatCompletionResponse(
    val id: String = "",
    val object: String = "chat.completion",
    val created: Long = System.currentTimeMillis() / 1000,
    val model: String = "",
    val choices: List<Choice> = emptyList(),
    val usage: TokenUsage = TokenUsage()
) {
    /**
     * Convenience property to get the first choice's text content.
     */
    val firstContent: String
        get() = choices.firstOrNull()?.message?.content ?: ""

    /**
     * Convenience property to get total tokens used.
     */
    val totalTokens: Int
        get() = usage.total_tokens
}

/**
 * A single choice from the model (usually just one in OpenAI API).
 */
data class Choice(
    val index: Int = 0,
    val message: Message = Message(),
    val finish_reason: String = "stop"
)

/**
 * Message within a choice.
 */
data class Message(
    val role: String = "assistant",
    val content: String = ""
)

/**
 * Token usage statistics from a completion.
 */
data class TokenUsage(
    val prompt_tokens: Int = 0,
    val completion_tokens: Int = 0,
    val total_tokens: Int = 0
)
