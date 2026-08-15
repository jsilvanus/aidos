package fi.italeino.aidos.engine.http

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * OpenAI-compatible chat completion request (RFC-0103).
 */
@Serializable
data class OpenAiChatCompletionRequest(
    val model: String,
    val messages: List<OpenAiMessage>,
    @SerialName("max_tokens")
    val maxTokens: Int? = null,
    val temperature: Float = 1.0f,
    @SerialName("top_p")
    val topP: Float = 1.0f,
    @SerialName("top_k")
    val topK: Int? = null,
    val stop: List<String>? = null,
    val stream: Boolean = false,
)

/**
 * OpenAI-compatible message format.
 */
@Serializable
data class OpenAiMessage(
    val role: String, // "system", "user", "assistant", "tool"
    val content: String? = null,
    @SerialName("tool_calls")
    val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id")
    val toolCallId: String? = null,
    val name: String? = null,
)

/**
 * OpenAI-compatible tool call.
 */
@Serializable
data class OpenAiToolCall(
    val id: String,
    val type: String = "function",
    val function: OpenAiFunction,
)

/**
 * OpenAI-compatible function call.
 */
@Serializable
data class OpenAiFunction(
    val name: String,
    val arguments: String, // JSON string
)

/**
 * OpenAI-compatible chat completion response.
 */
@Serializable
data class OpenAiChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<OpenAiChoice>,
    val usage: OpenAiUsage,
)

/**
 * Choice in chat completion response.
 */
@Serializable
data class OpenAiChoice(
    val index: Int = 0,
    val message: OpenAiMessage,
    @SerialName("finish_reason")
    val finishReason: String, // "stop", "length", "tool_calls"
)

/**
 * Token usage statistics.
 */
@Serializable
data class OpenAiUsage(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
)

/**
 * Error response following OpenAI format.
 */
@Serializable
data class OpenAiErrorResponse(
    val error: OpenAiError,
)

/**
 * Error details.
 */
@Serializable
data class OpenAiError(
    val message: String,
    val type: String = "internal_error",
    val param: String? = null,
    val code: String? = null,
)
