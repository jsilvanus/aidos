package dev.aidos.huggingface

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolOutcome
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Hugging Face model metadata from the API (RFC-0022).
 *
 * Fetched from the Hugging Face Hub API and cached locally.
 */
data class HuggingFaceModel(
    val modelId: String,
    val author: String,
    val displayName: String? = null,
    val description: String? = null,
    val tags: List<String> = emptyList(),
    val downloads: Long = 0,
    val likes: Long = 0,
    val pipeline: String? = null,
    val modelSize: Long? = null,
    val contextLength: Int? = null,
    val quantizations: List<Quantization> = emptyList(),
)

/**
 * A quantized variant of a model.
 */
data class Quantization(
    val name: String, // e.g., "Q4_K_M", "Q5_K_M", "fp16"
    val sizeBytes: Long,
    val downloadUrl: String,
    val sha256Digest: String? = null,
)

/**
 * Search result from HuggingFace API.
 */
data class HuggingFaceSearchResult(
    val total: Int,
    val models: List<HuggingFaceModel>,
)

/**
 * Hugging Face API client (RFC-0022).
 *
 * No external dependencies — uses HTTP via the broker's egress system (RFC-0030).
 * Implements model discovery and metadata fetching.
 */
class HuggingFaceClient(
    private val broker: EffectBroker,
    private val resourceHandle: ResourceHandle,
    private val capabilityId: CapabilityId? = null,
    private val apiBaseUrl: String = "https://huggingface.co/api/models",
) {

    /**
     * Search for models on Hugging Face.
     *
     * @param query search query (e.g., "qwen2.5 3b gguf")
     * @param filter optional filter (e.g., task:text-generation, library:gguf)
     * @param sort sort order (e.g., "downloads", "trendingScore")
     * @param limit max results to return
     * @return search results
     */
    suspend fun search(
        query: String? = null,
        filter: String? = null,
        sort: String = "trendingScore",
        limit: Int = 20,
    ): Result<HuggingFaceSearchResult> {
        return try {
            // Build query parameters. full=true and config=true to get metadata for verdicts.
            val params = mutableListOf("sort=$sort", "limit=$limit", "full=true", "config=true")
            if (!query.isNullOrBlank()) {
                params.add("search=$query")
            }
            if (filter != null) {
                params.add("filter=$filter")
            }
            val queryString = params.joinToString("&")
            val url = "$apiBaseUrl?$queryString"

            // Call HTTP tool via broker
            val result = broker.invoke(
                subjectId = "",
                call = ToolCall(
                    callId = "",
                    toolName = "http:get",
                    arguments = buildJsonObject {
                        put("url", url)
                    },
                    capabilityId = capabilityId,
                ),
                runTaint = dev.aidos.kernel.TrustLevel.UNTRUSTED,
            )

            // Check if successful
            if (result.outcome != ToolOutcome.Ok) {
                return Result.failure(Exception("HTTP request failed: ${result.outcome}"))
            }

            // Parse response
            val responseBody = result.content.filterIsInstance<ContentBlock.Text>()
                .firstOrNull()?.text ?: ""

            // Extract JSON from HTTP response (format: "HTTP 200\n\n{json}")
            val jsonPart = responseBody.substringAfter("\n\n").trim()
            if (jsonPart.isEmpty() || jsonPart == "[]") {
                return Result.success(HuggingFaceSearchResult(total = 0, models = emptyList()))
            }

            val jsonElement = Json.parseToJsonElement(jsonPart)
            
            // Hugging Face API /api/models returns an array of models, 
            // but some search endpoints might wrap it in an object.
            return when (jsonElement) {
                is JsonArray -> {
                    val models = jsonElement.mapNotNull { item ->
                        if (item is JsonObject) parseModel(item) else null
                    }
                    Result.success(HuggingFaceSearchResult(total = models.size, models = models))
                }
                is JsonObject -> {
                    val total = jsonElement["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                    // Handle both "results" and "models" keys if wrapped
                    val resultsArray = (jsonElement["results"] ?: jsonElement["models"])?.jsonArray
                    val models = resultsArray?.mapNotNull { item ->
                        if (item is JsonObject) parseModel(item) else null
                    } ?: emptyList()
                    Result.success(HuggingFaceSearchResult(total = if (total > 0) total else models.size, models = models))
                }
                else -> {
                    Result.success(HuggingFaceSearchResult(total = 0, models = emptyList()))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Fetch metadata for a specific model.
     *
     * @param modelId HuggingFace model ID (e.g., "TheBloke/Qwen2.5-3B-Instruct-GGUF")
     * @return model metadata
     */
    suspend fun getModel(modelId: String): Result<HuggingFaceModel> {
        return try {
            val url = "$apiBaseUrl/$modelId"

            // Call HTTP tool via broker
            val result = broker.invoke(
                subjectId = "",
                call = ToolCall(
                    callId = "",
                    toolName = "http:get",
                    arguments = buildJsonObject {
                        put("url", url)
                    },
                    capabilityId = capabilityId,
                ),
                runTaint = dev.aidos.kernel.TrustLevel.UNTRUSTED,
            )

            // Check if successful
            if (result.outcome != ToolOutcome.Ok) {
                return Result.failure(Exception("HTTP request failed: ${result.outcome}"))
            }

            // Parse response
            val responseBody = result.content.filterIsInstance<ContentBlock.Text>()
                .firstOrNull()?.text ?: ""

            // Extract JSON from HTTP response
            val jsonPart = responseBody.substringAfter("\n\n").trim()
            if (jsonPart.isEmpty()) {
                return Result.success(
                    HuggingFaceModel(
                        modelId = modelId,
                        author = "unknown",
                        displayName = modelId,
                    )
                )
            }

            val jsonElement = Json.parseToJsonElement(jsonPart)
            val jsonObj = jsonElement.jsonObject

            val model = parseModel(jsonObj) ?: HuggingFaceModel(
                modelId = modelId,
                author = "unknown",
                displayName = modelId,
            )

            Result.success(model)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * List quantizations for a model (files in the repo).
     *
     * @param modelId HuggingFace model ID
     * @return list of quantized variants
     */
    suspend fun listQuantizations(modelId: String): Result<List<Quantization>> {
        return try {
            val url = "$apiBaseUrl/$modelId"

            // Call HTTP tool via broker
            val result = broker.invoke(
                subjectId = "",
                call = ToolCall(
                    callId = "",
                    toolName = "http:get",
                    arguments = buildJsonObject {
                        put("url", url)
                    },
                    capabilityId = capabilityId,
                ),
                runTaint = dev.aidos.kernel.TrustLevel.UNTRUSTED,
            )

            // Check if successful
            if (result.outcome != ToolOutcome.Ok) {
                return Result.failure(Exception("HTTP request failed: ${result.outcome}"))
            }

            // Parse response
            val responseBody = result.content.filterIsInstance<ContentBlock.Text>()
                .firstOrNull()?.text ?: ""

            // Extract JSON from HTTP response
            val jsonPart = responseBody.substringAfter("\n\n").trim()
            if (jsonPart.isEmpty()) {
                return Result.success(emptyList())
            }

            val jsonElement = Json.parseToJsonElement(jsonPart)
            val jsonObj = jsonElement.jsonObject

            // Parse sibling files as quantizations
            val siblings = jsonObj["siblings"]?.jsonArray
                ?.mapNotNull { item ->
                    parseQuantization(item.jsonObject)
                } ?: emptyList()

            Result.success(siblings)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Map HuggingFace model tags to ModelKind.
     */
    fun inferModelKind(tags: List<String>, pipeline: String?): ModelKind =
        Companion.inferModelKind(tags, pipeline)

    companion object {
        fun inferModelKind(tags: List<String>, pipeline: String?): ModelKind {
            val tagString = tags.joinToString(" ").lowercase()
            val pipelineStr = pipeline?.lowercase() ?: ""

            return when {
                "text-generation" in tagString || "causal-lm" in tagString ||
                        "text-generation" in pipelineStr -> ModelKind.LLM

                "embedding" in tagString || "sentence-transformers" in tagString ||
                        "feature-extraction" in pipelineStr -> ModelKind.EMBEDDING

                "speech-recognition" in tagString || "automatic-speech-recognition" in tagString ||
                        "speech-recognition" in pipelineStr -> ModelKind.STT

                "text-to-speech" in tagString || "text-to-speech" in pipelineStr -> ModelKind.TTS

                "image-to-text" in tagString || "visual-question-answering" in tagString ||
                        "image-classification" in tagString -> ModelKind.VISION

                "ocr" in tagString || "object-detection" in tagString -> ModelKind.OCR

                "reranker" in tagString || "cross-encoder" in tagString -> ModelKind.RERANKER

                "translation" in tagString || "machine-translation" in tagString -> ModelKind.TRANSLATION

                else -> ModelKind.LLM // default to LLM
            }
        }
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    private fun parseModel(json: JsonObject): HuggingFaceModel? {
        return try {
            val modelId = json["id"]?.jsonPrimitive?.content ?: return null
            val author = json["author"]?.jsonPrimitive?.content ?: "unknown"
            val displayName = json["modelId"]?.jsonPrimitive?.content ?: modelId
            val description = json["description"]?.jsonPrimitive?.content
            val tags = json["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
            val downloads = json["downloads"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val likes = json["likes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val pipeline = json["pipeline_tag"]?.jsonPrimitive?.content
            
            // Try to extract context length from gguf object or config if available
            val contextLength = json["gguf"]?.jsonObject?.get("context_length")?.jsonPrimitive?.content?.toIntOrNull()
                ?: json["config"]?.jsonObject?.get("max_position_embeddings")?.jsonPrimitive?.content?.toIntOrNull()
                ?: json["config"]?.jsonObject?.get("n_ctx")?.jsonPrimitive?.content?.toIntOrNull()

            // Try to get total file size from gguf object
            val modelSize = json["gguf"]?.jsonObject?.get("totalFileSize")?.jsonPrimitive?.content?.toLongOrNull()

            // Parse sibling files as quantizations if available (only if full=true was used)
            val quantizations = json["siblings"]?.jsonArray?.mapNotNull { item ->
                parseQuantization(item.jsonObject)
            } ?: emptyList()

            HuggingFaceModel(
                modelId = modelId,
                author = author,
                displayName = displayName,
                description = description,
                tags = tags,
                downloads = downloads,
                likes = likes,
                pipeline = pipeline,
                contextLength = contextLength,
                modelSize = modelSize,
                quantizations = quantizations,
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun parseQuantization(json: JsonObject): Quantization? {
        return try {
            val filename = (json["filename"] ?: json["rfilename"])?.jsonPrimitive?.content ?: return null
            // Only include GGUF quantizations
            if (!filename.endsWith(".gguf")) return null

            val size = json["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
            val blob = json["blob"]?.jsonPrimitive?.content ?: ""
            val sha256 = (json["lfs"]?.jsonObject?.get("sha256") ?: json["sha256"])?.jsonPrimitive?.content

            Quantization(
                name = filename.substringAfterLast("/").removeSuffix(".gguf"),
                sizeBytes = size,
                downloadUrl = if (blob.isNotEmpty()) "https://huggingface.co/$blob" else "",
                sha256Digest = sha256,
            )
        } catch (e: Exception) {
            null
        }
    }
}

/**
 * Configuration for a user-registered custom endpoint (RFC-0021).
 *
 * Allows users to point Aidos at their own OpenAI-compatible API.
 */
data class CustomEndpointConfig(
    val name: String,
    val baseUrl: String,
    val modelName: String,
    val apiKeyId: String? = null, // Reference to vault.db secret ID
)
