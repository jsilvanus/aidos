package fi.italeino.aidos.engine.inference

import android.content.Context
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.modelruntime.InferenceBackend
import java.io.File
import java.security.MessageDigest

/**
 * Android inference backend. Models live in the engine-private `files/models` directory,
 * the same location used by the download/install workflow.
 *
 * The installer verifies the publisher digest before installation. The backend re-hashes
 * the installed artifact when the runtime admits it.
 */
class AndroidLlamaCppInferenceBackend(
    context: Context,
    private val threads: Int = 4,
) : InferenceBackend {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
    private val liveAdapters = mutableMapOf<String, AndroidLlamaCppAdapter>()

    override suspend fun catalog(): List<ModelDescriptor> = installed()

    override suspend fun installed(): List<ModelDescriptor> =
        modelsDir.listFiles()
            ?.asSequence()
            ?.filter { it.isFile && it.extension.equals("gguf", ignoreCase = true) }
            ?.map { file ->
                ModelDescriptor(
                    id = file.nameWithoutExtension,
                    name = file.nameWithoutExtension,
                    kind = ModelKind.LLM,
                    providerId = "llama.cpp.android",
                    isLocal = true,
                    contextWindow = DEFAULT_CONTEXT,
                    sizeBytes = file.length(),
                    digest = sha256(file),
                )
            }
            ?.toList()
            ?: emptyList()

    override suspend fun computeDigest(modelId: String): String =
        sha256(resolveModelFile(modelId))

    override suspend fun computeDigest(modelId: String, artifactPath: String?): String =
        sha256(resolveArtifact(modelId, artifactPath))

    override suspend fun delete(modelId: String) {
        liveAdapters.remove(modelId)?.close()
        resolveModelFile(modelId).delete()
    }

    override suspend fun load(modelId: String): Result<ModelAdapter> =
        load(modelId, artifactPath = null)

    override suspend fun load(modelId: String, artifactPath: String?): Result<ModelAdapter> {
        val file = resolveArtifact(modelId, artifactPath)
        if (!file.isFile) return Result.failure(
            IllegalStateException("Model file not found for '$modelId' in ${modelsDir.absolutePath}")
        )
        return try {
            val adapter = AndroidLlamaCppAdapter(
                modelId = modelId,
                modelFile = file,
                contextWindow = DEFAULT_CONTEXT,
                threads = threads,
            )
            liveAdapters[modelId] = adapter
            Result.success(adapter)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun unload(modelId: String) {
        liveAdapters.remove(modelId)?.close()
    }

    /**
     * Resolve the exact caller-selected artifact when supplied. Paths are restricted to the
     * engine's model directory so a catalog record cannot make the runtime open an arbitrary
     * filesystem location.
     */
    private fun resolveArtifact(modelId: String, artifactPath: String?): File {
        if (artifactPath != null) {
            val candidate = File(artifactPath).canonicalFile
            val root = modelsDir.canonicalFile
            val relative = candidate.toPath().normalize()
            if (!relative.startsWith(root.toPath())) {
                throw IllegalArgumentException("Model artifact path is outside the engine model directory")
            }
            return candidate
        }
        return resolveModelFile(modelId)
    }

    /** Supports both exact ids and the `<model>_<quantization>.gguf` installer naming scheme. */
    private fun resolveModelFile(modelId: String): File {
        val exact = File(modelsDir, "$modelId.gguf")
        if (exact.isFile) return exact
        val safeId = modelId.replace(Regex("[^A-Za-z0-9._-]"), "_")
        return modelsDir.listFiles()
            ?.firstOrNull { it.isFile && it.extension.equals("gguf", true) && it.nameWithoutExtension.startsWith("${safeId}_") }
            ?: exact
    }

    private fun sha256(file: File): String {
        if (!file.isFile) return ""
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val DEFAULT_CONTEXT = 4096
    }
}
