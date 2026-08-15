package fi.italeino.aidos.sdk

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TokenUsage

/**
 * Embedding ModelAdapter for Aidos Engine (RFC-0103, RFC-0021).
 *
 * Adapts Aidos Engine's OpenAI-compatible `/v1/embeddings` endpoint
 * to the RFC-0021 ModelAdapter interface for semantic embedding operations.
 *
 * RFC-0021: "The interface is symmetric between local and remote providers,
 * carrying no session_id, keeping acquisition (ModelRuntime) separate from
 * inference, and using ModelAdapter as the seam."
 *
 * Note: The current ModelRequest type is designed for LLM inference (messages, tools).
 * For MVP, embedding is handled through a separate pathway outside the standard
 * ModelAdapter flow, or this adapter synthesizes a compatible request format.
 * Future work: Add EmbeddingRequest variant to ModelRequest union type.
 */
class EngineEmbeddingAdapter(
    override val modelId: String,
    override val modelVersion: String,
    override val contextWindow: Int,  // Not used for embeddings, but required by interface
    private val httpClient: EngineHttpClient,
) : ModelAdapter {
    override val providerId = "aidos-engine"
    override val isLocal = true

    override fun supportsNativeToolCalls(): Boolean = false

    /**
     * Run embedding inference via Aidos Engine's embeddings endpoint.
     *
     * Note: This adapter's invoke() signature is designed for LLM inference (ModelRequest
     * contains messages, tools, etc.). For embedding, this is a workaround:
     * - Extract text from the first message if present
     * - Return an error if messages don't contain usable text
     * - Embedding results are returned as a single token (representing the vector)
     *
     * TODO(RFC-0103): Add ModelKind.EMBEDDING variant to routing that provides
     * a different request type optimized for embedding (just text, no tools).
     * For now, this adapter handles the mismatch gracefully.
     */
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        return try {
            // Extract text from first user/system message
            val textToEmbed = request.messages.firstNotNullOfOrNull { turn ->
                when (turn) {
                    is dev.aidos.kernel.Turn.System -> turn.content
                    is dev.aidos.kernel.Turn.User -> {
                        turn.content.firstNotNullOfOrNull { block ->
                            if (block is dev.aidos.kernel.ContentBlock.Text) block.text else null
                        }
                    }
                    else -> null
                }
            } ?: return Result.failure(
                IllegalArgumentException("Embedding request must contain text in system or user message")
            )

            // Call Engine's embeddings endpoint
            val embeddingRequest = EmbeddingRequest(
                model = modelId,
                input = textToEmbed,
            )

            val embeddingResponse = httpClient.embeddings(embeddingRequest).getOrElse { error ->
                return Result.failure(error)
            }

            // Extract the first (and usually only) embedding
            val embedding = embeddingResponse.data.firstOrNull()
                ?: return Result.failure(IllegalStateException("No embedding in response"))

            // Embeddings are typically used for similarity search, not for generating text.
            // We return a special marker indicating this is an embedding result.
            // The actual embedding vector is not transmitted through ModelResponse.text,
            // which is designed for text generation. This is a future refinement (ModelKind.EMBEDDING).
            // For now, we return success with a marker that embedding is available.
            val modelResponse = ModelResponse(
                text = "[embedding vector: ${embedding.embedding.size} dimensions]",
                toolCalls = emptyList(),
                stopReason = StopReason.END_TURN,
                usage = TokenUsage(
                    inputTokens = embeddingResponse.usage.promptTokens,
                    outputTokens = 0,  // Embeddings don't generate tokens
                ),
                modelId = modelId,
                modelVersion = modelVersion,
            )

            Result.success(modelResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
