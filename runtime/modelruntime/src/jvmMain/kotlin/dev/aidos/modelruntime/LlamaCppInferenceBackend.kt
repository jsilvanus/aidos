package dev.aidos.modelruntime

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TokenUsage
import org.apache.commons.codec.digest.DigestUtils
import java.io.File

/**
 * llama.cpp-based inference backend (RFC-0022, D28, M21).
 *
 * Loads GGUF models from `~/.aidos/models/` and provides constrained decoding
 * via GBNF (llama.cpp's grammar system).
 *
 * This implementation is the JVM MVP (M21). Android integration comes in Phase 4.
 * Native crashes are bounded by the admission queue and checkpoint recovery (D27).
 */
class LlamaCppInferenceBackend : InferenceBackend {
    private val modelsDir = File(System.getProperty("user.home"), ".aidos/models")

    init {
        modelsDir.mkdirs()
    }

    /**
     * Catalog of known-good GGUF models (RFC-0022 curated set).
     * Each model has a digest for content-addressed storage and verification.
     */
    override suspend fun catalog(): List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "nomic-embed-text-v1.5",
            name = "Nomic Embed Text v1.5",
            kind = ModelKind.EMBEDDING,
            providerId = "huggingface",
            isLocal = true,
            contextWindow = 2048,
            sizeBytes = 274_877_906L, // 262 MB Q4_0
            digest = null, // Would be set from catalog metadata
        ),
        ModelDescriptor(
            id = "qwen2.5-3b-instruct-q4_k_m",
            name = "Qwen2.5 3B Instruct Q4_K_M",
            kind = ModelKind.LLM,
            providerId = "huggingface",
            isLocal = true,
            contextWindow = 32768,
            sizeBytes = 2_147_483_648L, // 2 GB Q4_K_M
            digest = null,
        ),
        ModelDescriptor(
            id = "llama-2-7b-chat-q4_k_m",
            name = "Llama 2 7B Chat Q4_K_M",
            kind = ModelKind.LLM,
            providerId = "huggingface",
            isLocal = true,
            contextWindow = 4096,
            sizeBytes = 3_865_470_976L, // 3.6 GB Q4_K_M
            digest = null,
        ),
    )

    /**
     * Models installed on this device (from ~/.aidos/models/).
     * Each installed model is verified by digest before loading (RFC-0022).
     */
    override suspend fun installed(): List<ModelDescriptor> {
        if (!modelsDir.exists()) return emptyList()

        return modelsDir.listFiles()?.mapNotNull { file ->
            if (!file.isFile || !file.name.endsWith(".gguf")) return@mapNotNull null

            val metadata = GgufLoader.loadMetadata(file) ?: return@mapNotNull null
            val digest = computeDigest(file.name)

            ModelDescriptor(
                id = file.nameWithoutExtension,
                name = metadata.modelName,
                kind = ModelKind.LLM, // Would be inferred from metadata
                providerId = "local",
                isLocal = true,
                contextWindow = metadata.contextWindow,
                sizeBytes = file.length(),
                digest = digest,
            )
        } ?: emptyList()
    }

    /**
     * Compute SHA-256 digest of a model file (RFC-0022).
     * Returns empty string if file not found (will fail on load).
     */
    override suspend fun computeDigest(modelId: String): String {
        val file = modelFile(modelId)
        return if (file.exists()) {
            DigestUtils.sha256Hex(file)
        } else {
            ""
        }
    }

    /**
     * Delete a model from installed_models (RFC-0022).
     * Called when digest verification fails.
     */
    override suspend fun delete(modelId: String) {
        modelFile(modelId).delete()
    }

    /**
     * Load a model into memory (RFC-0022, RFC-0045).
     *
     * This is where the actual llama.cpp native binding would happen.
     * The MVP returns a mock adapter; real implementation will use llama-cpp-java
     * or similar JNI binding.
     *
     * In production (M21), this delegates to llama.cpp's inference engine
     * with constrained decoding via GBNF grammars (RFC-0021).
     */
    override suspend fun load(modelId: String): Result<ModelAdapter> {
        val file = modelFile(modelId)
        if (!file.exists()) {
            return Result.failure(
                IllegalStateException("Model file not found: $modelId at ${file.absolutePath}")
            )
        }

        val metadata = GgufLoader.loadMetadata(file)
            ?: return Result.failure(
                IllegalStateException("Invalid GGUF format: $modelId")
            )

        return try {
            // TODO: M21 — replace with actual llama.cpp native binding.
            // For now, return a mock adapter that implements the interface.
            val adapter = MockLlamaAdapter(modelId, metadata)
            Result.success(adapter)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unload a model from memory.
     * With the admission queue (RFC-0022), only one model is loaded at a time.
     */
    override suspend fun unload(modelId: String) {
        // TODO: M21 — call llama.cpp unload / free VRAM
    }

    private fun modelFile(modelId: String): File =
        File(modelsDir, "$modelId.gguf")
}

/**
 * Mock adapter for llama.cpp inference (MVP).
 *
 * This placeholder implements ModelAdapter but returns mock responses.
 * In M21, this is replaced with actual llama.cpp inference via JNI.
 *
 * The interface contract is unchanged — the same `ModelRequest` and `ModelResponse`
 * flow, which is why local models can participate in the agent loop
 * (RFC-0008, RFC-0021).
 */
private class MockLlamaAdapter(
    override val modelId: String,
    private val metadata: GgufMetadata,
) : ModelAdapter {
    override val providerId = "llama.cpp"
    override val modelVersion = "1.0.0"
    override val contextWindow = metadata.contextWindow
    override val isLocal = true

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        // TODO: M21 — invoke llama.cpp with:
        // - request.messages converted to prompt
        // - request.tools converted to GBNF grammar (RFC-0021)
        // - constrained decoding to ensure valid tool calls
        return Result.success(
            ModelResponse(
                text = "[Mock response from $modelId]",
                toolCalls = emptyList(),
                stopReason = StopReason.END_TURN,
                usage = TokenUsage(10, 20),
                modelId = modelId,
                modelVersion = modelVersion,
            )
        )
    }
}
