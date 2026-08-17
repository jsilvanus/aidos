package dev.aidos.huggingface

import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

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
    val artifacts: List<ModelArtifact> = emptyList(),
    /** GGUF artifacts retained as quantizations for compatibility with the Android UI. */
    val quantizations: List<Quantization> = emptyList(),
)

data class Quantization(
    val name: String,
    val sizeBytes: Long,
    val downloadUrl: String,
    val sha256Digest: String? = null,
)

data class HuggingFaceSearchResult(
    val total: Int,
    val models: List<HuggingFaceModel>,
)

class HuggingFaceClient(
    private val broker: EffectBroker,
    private val resourceHandle: ResourceHandle,
    private val capabilityId: CapabilityId? = null,
    private val apiBaseUrl: String = "https://huggingface.co/api/models",
) {
    suspend fun search(query: String? = null, filter: String? = null, sort: String = "trendingScore", limit: Int = 20): Result<HuggingFaceSearchResult> = try {
        val params = mutableListOf("sort=$sort", "limit=$limit", "full=true", "config=true")
        if (!query.isNullOrBlank()) params.add("search=$query")
        if (filter != null) params.add("filter=$filter")
        val result = broker.invoke(
            subjectId = "",
            call = ToolCall(callId = "", toolName = "http:get", arguments = buildJsonObject { put("url", "$apiBaseUrl?${params.joinToString("&")}") }, capabilityId = capabilityId),
            runTaint = TrustLevel.UNTRUSTED,
        )
        if (result.outcome != ToolOutcome.Ok) return Result.failure(Exception("HTTP request failed: ${result.outcome}"))
        val jsonPart = result.content.filterIsInstance<ContentBlock.Text>().firstOrNull()?.text?.substringAfter("\n\n")?.trim() ?: ""
        if (jsonPart.isEmpty() || jsonPart == "[]") return Result.success(HuggingFaceSearchResult(0, emptyList()))
        when (val jsonElement = Json.parseToJsonElement(jsonPart)) {
            is JsonArray -> {
                val models = jsonElement.mapNotNull { item -> if (item is JsonObject) parseModel(item) else null }
                Result.success(HuggingFaceSearchResult(models.size, models))
            }
            is JsonObject -> {
                val total = jsonElement["total"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                val resultsArray = (jsonElement["results"] ?: jsonElement["models"])?.jsonArray
                val models = resultsArray?.mapNotNull { item -> if (item is JsonObject) parseModel(item) else null } ?: emptyList()
                Result.success(HuggingFaceSearchResult(if (total > 0) total else models.size, models))
            }
            else -> Result.success(HuggingFaceSearchResult(0, emptyList()))
        }
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun getModel(modelId: String): Result<HuggingFaceModel> = try {
        val result = broker.invoke(
            subjectId = "",
            call = ToolCall(callId = "", toolName = "http:get", arguments = buildJsonObject { put("url", "$apiBaseUrl/$modelId") }, capabilityId = capabilityId),
            runTaint = TrustLevel.UNTRUSTED,
        )
        if (result.outcome != ToolOutcome.Ok) return Result.failure(Exception("HTTP request failed: ${result.outcome}"))
        val jsonPart = result.content.filterIsInstance<ContentBlock.Text>().firstOrNull()?.text?.substringAfter("\n\n")?.trim() ?: ""
        if (jsonPart.isEmpty()) return Result.success(HuggingFaceModel(modelId, "unknown", modelId))
        Result.success(parseModel(Json.parseToJsonElement(jsonPart).jsonObject) ?: HuggingFaceModel(modelId, "unknown", modelId))
    } catch (e: Exception) {
        Result.failure(e)
    }

    suspend fun listQuantizations(modelId: String): Result<List<Quantization>> = getModel(modelId).map { it.quantizations }

    /** Returns every recognized downloadable artifact, not only GGUF files. */
    suspend fun listArtifacts(modelId: String): Result<List<ModelArtifact>> = getModel(modelId).map { it.artifacts }

    fun inferModelKind(tags: List<String>, pipeline: String?): ModelKind = Companion.inferModelKind(tags, pipeline)

    companion object {
        fun inferModelKind(tags: List<String>, pipeline: String?): ModelKind {
            val tagString = tags.joinToString(" ").lowercase()
            val pipelineStr = pipeline?.lowercase() ?: ""
            return when {
                "text-generation" in tagString || "causal-lm" in tagString || "text-generation" in pipelineStr -> ModelKind.LLM
                "embedding" in tagString || "sentence-transformers" in tagString || "feature-extraction" in pipelineStr -> ModelKind.EMBEDDING
                "speech-recognition" in tagString || "automatic-speech-recognition" in tagString || "speech-recognition" in pipelineStr -> ModelKind.STT
                "text-to-speech" in tagString || "text-to-speech" in pipelineStr -> ModelKind.TTS
                "image-to-text" in tagString || "visual-question-answering" in tagString || "image-classification" in tagString -> ModelKind.VISION
                "ocr" in tagString || "object-detection" in tagString -> ModelKind.OCR
                "reranker" in tagString || "cross-encoder" in tagString -> ModelKind.RERANKER
                "translation" in tagString || "machine-translation" in tagString -> ModelKind.TRANSLATION
                else -> ModelKind.LLM
            }
        }
    }

    private fun parseModel(json: JsonObject): HuggingFaceModel? = try {
        val modelId = json["id"]?.jsonPrimitive?.content ?: return null
        val author = json["author"]?.jsonPrimitive?.content ?: "unknown"
        val displayName = json["modelId"]?.jsonPrimitive?.content ?: modelId
        val description = json["description"]?.jsonPrimitive?.content
        val tags = json["tags"]?.jsonArray?.mapNotNull { it.jsonPrimitive.content } ?: emptyList()
        val downloads = json["downloads"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val likes = json["likes"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val pipeline = json["pipeline_tag"]?.jsonPrimitive?.content
        val contextLength = json["gguf"]?.jsonObject?.get("context_length")?.jsonPrimitive?.content?.toIntOrNull()
            ?: json["config"]?.jsonObject?.get("max_position_embeddings")?.jsonPrimitive?.content?.toIntOrNull()
            ?: json["config"]?.jsonObject?.get("n_ctx")?.jsonPrimitive?.content?.toIntOrNull()
        val modelSize = json["gguf"]?.jsonObject?.get("totalFileSize")?.jsonPrimitive?.content?.toLongOrNull()
        val artifacts = json["siblings"]?.jsonArray?.mapNotNull { parseArtifact(modelId, it.jsonObject) } ?: emptyList()
        val quantizations = artifacts.filter { it.format == ModelFormat.GGUF }.map {
            Quantization(it.filename.removeSuffix(".gguf"), it.sizeBytes, it.downloadUrl, it.sha256Digest)
        }
        HuggingFaceModel(modelId, author, displayName, description, tags, downloads, likes, pipeline, modelSize, contextLength, artifacts, quantizations)
    } catch (_: Exception) {
        null
    }

    private fun parseArtifact(modelId: String, json: JsonObject): ModelArtifact? = try {
        val filename = (json["filename"] ?: json["rfilename"])?.jsonPrimitive?.content ?: return null
        val format = ModelFormat.fromFilename(filename)
        if (format == ModelFormat.UNKNOWN) return null
        val size = json["size"]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L
        val sha256 = (json["lfs"]?.jsonObject?.get("sha256") ?: json["sha256"])?.jsonPrimitive?.content
        ModelArtifact(
            filename = filename,
            sizeBytes = size,
            downloadUrl = "https://huggingface.co/$modelId/resolve/main/$filename",
            sha256Digest = sha256,
            format = format,
        )
    } catch (_: Exception) {
        null
    }
}

data class CustomEndpointConfig(
    val name: String,
    val baseUrl: String,
    val modelName: String,
    val apiKeyId: String? = null,
)
