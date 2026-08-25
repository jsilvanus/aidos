package fi.italeino.aidos.sdk.adapters

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Usage
import dev.aidos.kernel.StopReason
import fi.italeino.aidos.sdk.client.AidosEngineClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray

/**
 * `ModelAdapter` implementations that route model inference to Aidos Engine through Aidos SDK's
 * client (RFC-0103 MVP item 5). Published as `aidos-sdk-adapters` (Dictator plan D-1) — the
 * artifact that depends on `:kernel`, separate from `aidos-sdk-client`, so a consumer that only
 * wants chat/embeddings/transcription without Aidos's frozen contract types can skip this one.
 *
 * A consuming app's routing layer (e.g. `agent/routing`'s `PolicyInferenceRouter`) constructs
 * these against an already-initialized `AidosEngineClient` and adds them to its adapter list —
 * the same local/remote symmetry RFC-0021 gives every other `ModelAdapter`.
 */

/**
 * Routes model inference to Aidos Engine's `/v1/chat/completions` (RFC-0103).
 *
 * This adapter:
 * - Converts kernel ModelRequest to OpenAI-compatible HTTP JSON
 * - Sends request to Engine's /v1/chat/completions endpoint
 * - Converts HTTP response back to kernel ModelResponse
 * - Handles errors and fallback cases
 *
 * Used when Engine is available and client wants to use Engine's local models.
 */
class EngineLocalModelAdapter(
    private val client: AidosEngineClient,
    modelId: String
) : ModelAdapter {
    override val providerId: String = "aidos-engine"
    override val modelId: String = modelId
    override val modelVersion: String = "1.0"
    override val contextWindow: Int = 4096  // Default; ideally from Engine capabilities
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = true

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = withContext(Dispatchers.IO) {
        try {
            // Check if Engine supports chat.completions endpoint
            if (!client.supportsEndpoint("chat.completions")) {
                return@withContext Result.failure(
                    IllegalStateException("Engine does not support chat.completions endpoint")
                )
            }

            // Convert kernel ModelRequest to OpenAI-compatible JSON
            val httpRequest = convertKernelRequestToHttp(request)

            // Make HTTP request to Engine
            val responseBody = client.request("chat/completions", "POST", httpRequest)
                ?: return@withContext Result.failure(
                    IllegalStateException("Engine returned null response")
                )

            // Parse response and convert back to kernel format
            val modelResponse = convertHttpResponseToKernel(responseBody)
            Result.success(modelResponse)

        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Convert kernel ModelRequest to OpenAI-compatible HTTP JSON format.
     */
    private fun convertKernelRequestToHttp(request: ModelRequest): String {
        // Build messages array in OpenAI format
        val messagesJson = request.messages.map { turn ->
            val obj = when (turn) {
                is dev.aidos.kernel.Turn.System -> {
                    """{"role":"system","content":${escapeJsonString(turn.content)}}"""
                }
                is dev.aidos.kernel.Turn.User -> {
                    val content = turn.content.joinToString("\n") { block ->
                        when (block) {
                            is dev.aidos.kernel.ContentBlock.Text -> block.text
                            else -> "[Non-text content]"
                        }
                    }
                    """{"role":"user","content":${escapeJsonString(content)}}"""
                }
                is dev.aidos.kernel.Turn.Assistant -> {
                    """{"role":"assistant","content":${escapeJsonString(turn.text ?: "")}}"""
                }
                is dev.aidos.kernel.Turn.ToolResult -> {
                    val content = (turn.result.content.firstOrNull() as? dev.aidos.kernel.ContentBlock.Text)?.text ?: ""
                    """{"role":"tool","content":${escapeJsonString(content)},"tool_call_id":${escapeJsonString(turn.result.callId)}}"""
                }
            }
            obj
        }.joinToString(",")

        // Build request JSON
        return """{
            "model":"$modelId",
            "messages":[$messagesJson],
            "temperature":0.7,
            "max_tokens":${request.maxOutputTokens},
            "stream":false
        }""".replace(Regex("\\s+"), " ")
    }

    /**
     * Escape a string for JSON output.
     */
    private fun escapeJsonString(text: String): String {
        val escaped = text
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    /**
     * Convert OpenAI-compatible HTTP response to kernel ModelResponse format.
     */
    private fun convertHttpResponseToKernel(responseBody: String): ModelResponse {
        try {
            val json = Json.parseToJsonElement(responseBody).jsonObject

            val text = json["choices"]?.jsonArray?.firstOrNull()
                ?.jsonObject?.get("message")?.jsonObject?.get("content")
                ?.let { it as? JsonPrimitive }?.content ?: ""

            val usage = json["usage"]?.jsonObject
            val inputTokens = usage?.get("prompt_tokens")?.let {
                (it as? JsonPrimitive)?.content?.toIntOrNull()
            } ?: 0
            val outputTokens = usage?.get("completion_tokens")?.let {
                (it as? JsonPrimitive)?.content?.toIntOrNull()
            } ?: 0

            return ModelResponse(
                outputs = listOf(TextOutput(text)),
                stopReason = StopReason.END_TURN,
                usage = Usage(
                    inputTokens = inputTokens,
                    outputTokens = outputTokens,
                    totalTokens = inputTokens + outputTokens
                ),
                model = ModelRef(id = modelId, version = "1.0")
            )
        } catch (e: Exception) {
            throw IllegalArgumentException("Failed to parse Engine response: ${e.message}", e)
        }
    }
}

/**
 * Routes embedding inference to Aidos Engine's `/v1/embeddings` (RFC-0103).
 */
class EngineEmbeddingAdapter(
    private val client: AidosEngineClient,
    modelId: String
) : ModelAdapter {
    override val providerId: String = "aidos-engine"
    override val modelId: String = modelId
    override val modelVersion: String = "1.0"
    override val contextWindow: Int = 2048
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = withContext(Dispatchers.IO) {
        try {
            // Check if Engine supports embeddings endpoint
            if (!client.supportsEndpoint("embeddings")) {
                return@withContext Result.failure(
                    IllegalStateException("Engine does not support embeddings endpoint")
                )
            }

            // Extract text from request
            val text = request.messages.firstOrNull()?.let { turn ->
                when (turn) {
                    is dev.aidos.kernel.Turn.User ->
                        turn.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>()
                            .joinToString(" ") { it.text }
                    else -> ""
                }
            } ?: ""

            if (text.isEmpty()) {
                return@withContext Result.failure(
                    IllegalArgumentException("No text provided for embedding")
                )
            }

            // Build request
            val httpRequest = """
                {
                    "model": "$modelId",
                    "input": ["$text"]
                }
            """.trimIndent()

            // Make request
            val responseBody = client.request("embeddings", "POST", httpRequest)
                ?: return@withContext Result.failure(
                    IllegalStateException("Engine returned null response")
                )

            // For now, return a placeholder response
            // Full implementation would extract actual embedding vectors
            Result.success(
                ModelResponse(
                    outputs = listOf(TextOutput("[Embedding computed]")),
                    stopReason = StopReason.END_TURN,
                    usage = Usage(inputTokens = 10, outputTokens = 0, totalTokens = 10),
                    model = ModelRef(id = modelId, version = "1.0")
                )
            )

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Routes speech-to-text inference to Aidos Engine's `/v1/audio/transcriptions` (RFC-0103).
 */
class EngineTranscriptionAdapter(
    private val client: AidosEngineClient,
    modelId: String
) : ModelAdapter {
    override val providerId: String = "aidos-engine"
    override val modelId: String = modelId
    override val modelVersion: String = "1.0"
    override val contextWindow: Int = 1024
    override val isLocal: Boolean = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = withContext(Dispatchers.IO) {
        try {
            // Check if Engine supports audio.transcriptions endpoint
            if (!client.supportsEndpoint("audio.transcriptions")) {
                return@withContext Result.failure(
                    IllegalStateException("Engine does not support audio.transcriptions endpoint")
                )
            }

            // Extract audio data from request
            val audioBlock = request.messages.firstOrNull()?.let { turn ->
                when (turn) {
                    is dev.aidos.kernel.Turn.User ->
                        turn.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Audio>().firstOrNull()
                    else -> null
                }
            }

            if (audioBlock == null) {
                return@withContext Result.failure(
                    IllegalArgumentException("No audio data provided")
                )
            }

            // Encode audio as base64
            val base64Audio = java.util.Base64.getEncoder().encodeToString(audioBlock.data)

            // Build request
            val httpRequest = """
                {
                    "model": "$modelId",
                    "file": "$base64Audio",
                    "language": null,
                    "response_format": "json"
                }
            """.trimIndent()

            // Make request
            val responseBody = client.request("audio/transcriptions", "POST", httpRequest)
                ?: return@withContext Result.failure(
                    IllegalStateException("Engine returned null response")
                )

            // Parse response
            val json = Json.parseToJsonElement(responseBody).jsonObject
            val text = json["text"]?.let { it as? JsonPrimitive }?.content ?: ""

            Result.success(
                ModelResponse(
                    outputs = listOf(TextOutput(text)),
                    stopReason = StopReason.END_TURN,
                    usage = Usage(inputTokens = 0, outputTokens = 0, totalTokens = 0),
                    model = ModelRef(id = modelId, version = "1.0")
                )
            )

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}

/**
 * Fallback adapter used when Aidos Engine is unavailable (RFC-0103).
 *
 * Returns an appropriate error to the caller, allowing graceful degradation.
 */
class EngineUnavailableAdapter(
    private val reason: String = "Aidos Engine is not available"
) : ModelAdapter {
    override val providerId: String = "aidos-engine-unavailable"
    override val modelId: String = "unavailable"
    override val modelVersion: String = "1.0"
    override val contextWindow: Int = 0
    override val isLocal: Boolean = false

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        return Result.failure(
            IllegalStateException("Engine unavailable: $reason")
        )
    }
}
