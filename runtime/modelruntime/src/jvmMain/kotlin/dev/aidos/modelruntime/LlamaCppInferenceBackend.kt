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
     *
     * M20 (RFC-0022, RFC-0054, RFC-0045): each entry's [ModelDescriptor.digest] is the real
     * SHA-256 published by the model's own Hugging Face repository (its LFS pointer's `oid`,
     * the same hash Git LFS itself verifies a download against) for the exact file named below —
     * not a placeholder. [GlobalModelRuntime.load] compares an installed file's freshly computed
     * hash against *this* pinned value, not a second hash of the same file, so a corrupted or
     * substituted download is actually detectable. Re-derive by fetching
     * `https://huggingface.co/api/models/<repo>/tree/main` and reading the matching file's
     * `lfs.oid` if a catalog entry ever needs to point at a different quantization or revision.
     */
    override suspend fun catalog(): List<ModelDescriptor> = listOf(
        ModelDescriptor(
            id = "nomic-embed-text-v1.5",
            name = "Nomic Embed Text v1.5",
            kind = ModelKind.EMBEDDING,
            providerId = "huggingface",
            isLocal = true,
            contextWindow = 2048,
            // nomic-ai/nomic-embed-text-v1.5-GGUF, nomic-embed-text-v1.5.Q4_0.gguf
            sizeBytes = 77_802_880L, // 74 MB Q4_0
            digest = "8d88b9d579f2dcce28f65de1ad3946453adc281d7b784f2a75afe25158136d44",
        ),
        ModelDescriptor(
            id = "qwen2.5-3b-instruct-q4_k_m",
            name = "Qwen2.5 3B Instruct Q4_K_M",
            kind = ModelKind.LLM,
            providerId = "huggingface",
            isLocal = true,
            contextWindow = 32768,
            // Qwen/Qwen2.5-3B-Instruct-GGUF, qwen2.5-3b-instruct-q4_k_m.gguf
            sizeBytes = 2_104_932_768L, // 2.1 GB Q4_K_M
            digest = "626b4a6678b86442240e33df819e00132d3ba7dddfe1cdc4fbb18e0a9615c62d",
        ),
        ModelDescriptor(
            id = "llama-2-7b-chat-q4_k_m",
            name = "Llama 2 7B Chat Q4_K_M",
            kind = ModelKind.LLM,
            providerId = "huggingface",
            isLocal = true,
            contextWindow = 4096,
            // TheBloke/Llama-2-7B-Chat-GGUF, llama-2-7b-chat.Q4_K_M.gguf
            sizeBytes = 4_081_004_224L, // 3.8 GB Q4_K_M
            digest = "08a5566d61d7cb6b420c3e4387a39e0078e1f2fe5f055f3a03887385304d4bfa",
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
            // modelId has no ".gguf" suffix (see modelFile()) -- file.name already carries it,
            // so passing file.name here (pre-M20-fix) built "<name>.gguf.gguf", which never
            // existed on disk, and computeDigest() silently returned "" for every installed
            // model. file.nameWithoutExtension is the correct modelId.
            val digest = computeDigest(file.nameWithoutExtension)

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
            // DigestUtils has no File overload -- ByteArray/InputStream/String only.
            file.inputStream().use { DigestUtils.sha256Hex(it) }
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
     * Uses llama-cpp-java JNI binding to load and run GGUF models.
     * In production (M21), this creates a real LlamaCppAdapter with
     * constrained decoding via GBNF grammars (RFC-0021).
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
            // M21: Load real llama.cpp model with JNI binding
            val adapter = LlamaCppAdapter(modelId, file, metadata)
            Result.success(adapter)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Unload a model from memory.
     * With the admission queue (RFC-0022), only one model is loaded at a time.
     * Calls close() on the adapter if it's a LlamaCppAdapter to free native resources.
     */
    override suspend fun unload(modelId: String) {
        // TODO: M21 — Keep track of loaded models and call close() on them
        // For now, the adapter lifecycle is managed by GlobalModelRuntime
    }

    private fun modelFile(modelId: String): File =
        File(modelsDir, "$modelId.gguf")
}
