package fi.italeino.aidos.sdk

import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Aidos Engine SDK type model (RFC-0103).
 *
 * These types represent the contract between client apps and Aidos Engine for:
 * - Handshake and token acquisition
 * - API version and capability negotiation
 * - Model discovery and status tracking
 * - HTTP endpoint specifications
 */

/**
 * Request sent to Aidos Engine's Binder handshake surface.
 *
 * Contains caller identity (resolved by OS via signature-protected permission).
 * Engine verifies signature match and returns token + connection details.
 */
@Serializable
data class HandshakeRequest(
    val callerPackage: String,
    val timestamp: Instant,
)

/**
 * Response from Aidos Engine's Binder handshake.
 *
 * - [port]: Ephemeral loopback port (127.0.0.1) chosen at Engine startup
 * - [token]: Short-lived bearer token (scoped to one handshake, expires on Engine restart)
 * - [apiVersion]: Strict integer for wire-format compatibility (RFC-0052 pattern)
 * - [capabilities]: Endpoints available and enabled models
 *
 * RFC-0103: Version alone breaks on every feature; capability negotiation alone
 * lets a client attempt a wire-incompatible call. Both together are needed.
 */
@Serializable
data class HandshakeResponse(
    val port: Int,
    val token: String,
    val apiVersion: Int,
    val capabilities: EngineCapabilities,
)

/**
 * Engine capability declaration: endpoints and model availability.
 *
 * - [endpoints]: Which HTTP endpoints are available (e.g., "chat.completions", "embeddings")
 * - [models]: Enabled set of models (resident, available-but-unloaded, or reachable remote)
 *
 * RFC-0103: "capabilities.models lists the enabled set, not the configured set, and never
 * anything unconfigured. A model sitting at configured·disabled or a whole provider that's
 * disabled must not appear here."
 *
 * A client app may never request a model not listed here; rejection is enforced at both
 * discovery time (not listed) and execution time (direct request denied).
 */
@Serializable
data class EngineCapabilities(
    val endpoints: List<String>,
    val models: List<ModelStatus>,
)

/**
 * Status of a single enabled model, as reported by Engine to clients.
 *
 * - [modelId]: Unique model identifier (e.g., "qwen2.5-3b-q4", "claude-sonnet-4.5")
 * - [modelKind]: Category (LLM, EMBEDDING, STT, etc.)
 * - [providerId]: Where the model runs ("aidos-engine", "anthropic", "openai", etc.)
 * - [status]: Current state (resident, available-but-unloaded, remote)
 * - [contextWindow]: Max context length in tokens (local only; remote models can be queried)
 *
 * RFC-0103 Discovery: "Client apps see the list and ask for a specific model;
 * Aidos Engine does not choose on their behalf."
 */
@Serializable
data class ModelStatus(
    val modelId: String,
    val modelKind: String,  // "LLM", "EMBEDDING", "STT", etc.
    val providerId: String,
    @SerialName("status")
    val loadStatus: String,  // "RESIDENT", "AVAILABLE", or "REMOTE"
    val contextWindow: Int?,  // null for remote models
)

/**
 * OpenAI-compatible `/v1/chat/completions` request (RFC-0103).
 *
 * Aidos Engine exposes this endpoint exactly as RFC-0021/0023 remote providers do,
 * so the local/remote symmetry holds: the same request body targets Engine or OpenAI.
 *
 * Streaming is via Server-Sent Events (SSE) if [stream] is true.
 */
@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val tools: List<ToolSchemaJson>? = null,
    @SerialName("tool_choice")
    val toolChoice: String? = null,  // "auto", "none", "required", or {"type": "function", "function": {"name": "..."}}
    @SerialName("max_tokens")
    val maxTokens: Int,
    val temperature: Double? = null,
    val stream: Boolean = false,
)

/**
 * OpenAI-compatible message format for chat endpoint.
 */
@Serializable
data class ChatMessage(
    val role: String,  // "system", "user", "assistant"
    val content: String? = null,  // text content or null for tool_result
    @SerialName("tool_calls")
    val toolCalls: List<ToolCallJson>? = null,  // assistant tool calls
    @SerialName("tool_call_id")
    val toolCallId: String? = null,  // tool result's originating call
)

/**
 * Tool call as represented in OpenAI-compatible JSON.
 */
@Serializable
data class ToolCallJson(
    val id: String,
    val type: String,  // "function"
    val function: ToolFunctionJson,
)

/**
 * Tool function definition (OpenAI-compatible).
 */
@Serializable
data class ToolFunctionJson(
    val name: String,
    val arguments: String,  // JSON string
)

/**
 * Tool schema as represented in OpenAI-compatible JSON.
 */
@Serializable
data class ToolSchemaJson(
    val type: String,  // "function"
    val function: ToolDefinitionJson,
)

/**
 * Tool definition schema (OpenAI-compatible).
 */
@Serializable
data class ToolDefinitionJson(
    val name: String,
    val description: String,
    val parameters: Map<String, Any>,  // JSON Schema
)

/**
 * OpenAI-compatible `/v1/chat/completions` response (non-streaming).
 */
@Serializable
data class ChatCompletionResponse(
    val id: String,
    val object: String,  // "chat.completion"
    val created: Long,
    val model: String,
    val choices: List<ChatCompletionChoice>,
    val usage: TokenUsageJson,
)

/**
 * Single choice in chat completion response.
 */
@Serializable
data class ChatCompletionChoice(
    val index: Int,
    val message: ChatMessage,
    @SerialName("finish_reason")
    val finishReason: String,  // "stop", "tool_calls", "length", "content_filter"
)

/**
 * Token usage for chat and embedding endpoints.
 */
@Serializable
data class TokenUsageJson(
    @SerialName("prompt_tokens")
    val promptTokens: Int,
    @SerialName("completion_tokens")
    val completionTokens: Int,
    @SerialName("total_tokens")
    val totalTokens: Int,
)

/**
 * OpenAI-compatible `/v1/embeddings` request.
 */
@Serializable
data class EmbeddingRequest(
    val model: String,
    val input: String,  // Single string; array support deferred
)

/**
 * OpenAI-compatible `/v1/embeddings` response.
 */
@Serializable
data class EmbeddingResponse(
    val object: String,  // "list"
    val data: List<EmbeddingData>,
    val model: String,
    val usage: TokenUsageJson,
)

/**
 * Single embedding in response.
 */
@Serializable
data class EmbeddingData(
    val object: String,  // "embedding"
    val embedding: List<Double>,
    val index: Int,
)

/**
 * OpenAI-compatible `/v1/audio/transcriptions` request.
 *
 * Sent as multipart/form-data (handled by HTTP client, not serialized here).
 */
data class TranscriptionRequest(
    val model: String,
    val audioData: ByteArray,  // Raw audio bytes
    val audioMimeType: String,  // e.g., "audio/wav", "audio/mpeg"
)

/**
 * OpenAI-compatible `/v1/audio/transcriptions` response.
 */
@Serializable
data class TranscriptionResponse(
    val text: String,
)

/**
 * Engine unavailability signal returned by SDK to callers.
 *
 * RFC-0103 Degradation: "Aidos SDK surfaces 'Engine not installed' and
 * 'handshake or version negotiation fails' as one signal — local inference
 * unavailable — which every consuming app, Aidos Agent included, handles
 * the same way rather than each inventing its own detection."
 */
sealed class EngineUnavailability(val message: String) {
    class NotInstalled(message: String = "Aidos Engine not installed") : EngineUnavailability(message)
    class HandshakeFailed(message: String) : EngineUnavailability(message)
    class VersionIncompatible(val clientRequired: Int, val serverHas: Int) :
        EngineUnavailability("API version mismatch: client requires $clientRequired, server has $serverHas")
    class ConnectionFailed(message: String) : EngineUnavailability(message)
}
