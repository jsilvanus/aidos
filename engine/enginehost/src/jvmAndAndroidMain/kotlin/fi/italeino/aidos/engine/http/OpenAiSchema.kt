package fi.italeino.aidos.engine.http

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * OpenAI-compatible request/response schema for Aidos Engine (RFC-0103).
 * Uses the wire format RFC-0021 already defines for remote providers.
 */

// Chat Completion Schema

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    /** Null when the client did not send it: the model's own run settings then apply (RFC-0022). */
    val temperature: Float? = null,
    val max_tokens: Int? = null,
    val top_p: Float? = null,
    val stream: Boolean = false,
    val tool_choice: String? = null,
    val tools: List<ToolDefinition>? = null
)

@Serializable
data class ChatMessage(
    val role: String,  // "system", "user", "assistant", "tool"
    val content: String? = null,
    val tool_calls: List<ToolCall>? = null,
    val tool_call_id: String? = null,
    val name: String? = null,
    /**
     * Pictures for a vision model, on `user` messages: base64 of the encoded image (PNG/JPEG/...),
     * or a `data:image/...;base64,` URL. Additive to API v1 (sdk/CONTRACT.md); the text in
     * [content] is the question about them.
     */
    val images: List<String>? = null
)

@Serializable
data class ToolDefinition(
    val type: String = "function",
    val function: FunctionDefinition
)

@Serializable
data class FunctionDefinition(
    val name: String,
    val description: String? = null,
    val parameters: JsonObject? = null
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: ToolFunctionCall
)

@Serializable
data class ToolFunctionCall(
    val name: String,
    val arguments: String  // JSON string
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val `object`: String = "chat.completion",
    val created: Long,
    val model: String,
    val choices: List<Choice>,
    val usage: TokenUsage
)

@Serializable
data class Choice(
    val index: Int,
    val message: ChatMessage,
    val finish_reason: String?
)

@Serializable
data class TokenUsage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

// Embeddings Schema

@Serializable
data class EmbeddingsRequest(
    val model: String,
    val input: List<String>,
    val encoding_format: String? = null
)

@Serializable
data class EmbeddingsResponse(
    val `object`: String = "list",
    val data: List<Embedding>,
    val model: String,
    val usage: TokenUsage
)

@Serializable
data class Embedding(
    val `object`: String = "embedding",
    val embedding: List<Float>,
    val index: Int
)

// Model Listing Schema

@Serializable
data class ModelsResponse(
    val `object`: String = "list",
    val data: List<ModelCard>
)

@Serializable
data class ModelCard(
    val id: String,
    val `object`: String = "model",
    val owned_by: String = "aidos-local",
    val kind: String,
    val capabilities: List<String>,
    val context_window: Int? = null,
    val format: String? = null,
    val size_bytes: Long? = null,
    val quantization: String? = null,
    val installed: Boolean,
    val loaded: Boolean,
    /** Where this model's requests are served: `local`, `cluster` or `remote` (RFC-0104). Additive within API v1. */
    val placement: String? = null,
    val metadata: Map<String, String> = emptyMap()
)

// Audio Transcription Schema

@Serializable
data class TranscriptionRequest(
    val file: String,  // Base64-encoded audio
    val model: String,
    val language: String? = null,
    val prompt: String? = null,
    val response_format: String? = "json",
    val temperature: Float = 0.0f
)

/** `POST /v1/audio/speech` body (RFC-0117 amendment), OpenAI-shaped. `response_format` is `wav` (default) or `pcm`. */
@Serializable
data class SpeechRequestBody(
    val model: String,
    val input: String,
    val voice: String? = null,
    val response_format: String? = "wav",
    val speed: Double? = 1.0,
)

@Serializable
data class TranscriptionResponse(
    val text: String
)

// Chat Completion Streaming Schema (RFC-0103, Phase C.2)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val `object`: String = "chat.completion.chunk",
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>,
    /** Set on the last chunk of a stream only; null on the ones before it. */
    val usage: TokenUsage? = null,
)

@Serializable
data class ChunkChoice(
    val index: Int,
    val delta: ChunkDelta,
    val finish_reason: String?
)

@Serializable
data class ChunkDelta(
    val content: String? = null,
    val role: String? = null,
    val tool_calls: List<ToolCall>? = null
)

// Error Schema

@Serializable
data class ErrorResponse(
    val error: ErrorDetail
)

@Serializable
data class ErrorDetail(
    val message: String,
    val type: String,
    val param: String? = null,
    val code: String? = null
)

// Handshake Response Schema (via Binder)

@Serializable
data class HandshakeResponse(
    val port: Int,
    val token: String,
    val apiVersion: Int = 1,
    val capabilities: Capabilities
)

@Serializable
data class Capabilities(
    val endpoints: List<String>,  // ["chat.completions", "embeddings", "audio.transcriptions"]
    val models: List<ModelInfo>
)

@Serializable
data class ModelInfo(
    val id: String,
    val `object`: String = "model",
    val owned_by: String = "aidos-local",
    val kind: String,  // "llm", "embedding", "stt", "tts"
    val context_window: Int? = null,
    val quantization: String? = null,
    /** `local`, `cluster` or `remote` (RFC-0104). Additive within API v1; absent from older Engines. */
    val placement: String? = null
)
