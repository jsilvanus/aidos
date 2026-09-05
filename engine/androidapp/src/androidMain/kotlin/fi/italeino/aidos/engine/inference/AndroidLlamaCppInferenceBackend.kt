package fi.italeino.aidos.engine.inference

import android.content.Context
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.modelruntime.InferenceBackend
import dev.aidos.models.CatalogEntry
import dev.aidos.models.ModelCatalogManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.security.MessageDigest

/**
 * Android inference backend. Model identity and expected digests come from the persistent model
 * catalog; installed metadata comes from the same catalog database. The filesystem is treated as
 * an artifact store, not as an authoritative model catalog.
 */
class AndroidLlamaCppInferenceBackend(
    context: Context,
    private val catalogManager: ModelCatalogManager,
    private val threads: Int = 4,
) : InferenceBackend {
    private val modelsDir = File(context.filesDir, "models").apply { mkdirs() }
    private val liveAdapters = mutableMapOf<String, AndroidLlamaCppAdapter>()

    override suspend fun catalog(): List<ModelDescriptor> =
        catalogManager.listCatalog().getOrElse { throw it }.map { entry ->
            descriptorFromCatalog(entry)
        }

    override suspend fun installed(): List<ModelDescriptor> =
        catalogManager.listInstalled().getOrElse { throw it }
            .filter { File(it.path).isFile }
            .mapNotNull { installed ->
                val catalog = catalogManager.getCatalog(installed.modelId).getOrElse { throw it }
                catalog?.let { descriptorFromCatalog(it, installed.sizeBytes, installed.digest) }
            }

    override suspend fun computeDigest(modelId: String): String =
        sha256(resolveModelFile(modelId))

    override suspend fun delete(modelId: String) {
        liveAdapters.remove(modelId)?.close()
        val file = resolveModelFile(modelId)
        if (file.isFile) file.delete()
        catalogManager.uninstall(modelId).getOrThrow()
    }

    override suspend fun load(modelId: String): Result<ModelAdapter> {
        val catalog = catalogManager.getCatalog(modelId).getOrElse { return Result.failure(it) }
            ?: return Result.failure(IllegalStateException("Model $modelId is not in the model catalog"))
        val installed = catalogManager.listInstalled().getOrElse { return Result.failure(it) }
            .firstOrNull { it.modelId == modelId }
            ?: return Result.failure(IllegalStateException("Model $modelId is not installed"))
        val file = File(installed.path)
        if (!file.isFile) return Result.failure(
            IllegalStateException("MODEL_NOT_INSTALLED: model file not found for '$modelId': ${file.absolutePath}")
        )

        return try {
            if (installed.digest.isBlank()) {
                return Result.failure(IllegalStateException("MODEL_INTEGRITY_MISSING: no installed digest for '$modelId'"))
            }
            val actualDigest = sha256(file)
            if (!actualDigest.equals(installed.digest, ignoreCase = true)) {
                return Result.failure(
                    IllegalStateException(
                        "MODEL_INTEGRITY_MISMATCH: installed digest for '$modelId' does not match the model file"
                    )
                )
            }

            val adapter = AndroidLlamaCppAdapter(
                modelId = modelId,
                modelFile = file,
                contextWindow = contextWindow(catalog),
                threads = threads,
                embeddingMode = catalog.kind == ModelKind.EMBEDDING,
            )
            liveAdapters[modelId]?.close()
            liveAdapters[modelId] = adapter
            Result.success(adapter)
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun unload(modelId: String) {
        liveAdapters.remove(modelId)?.close()
    }

    private fun descriptorFromCatalog(
        entry: CatalogEntry,
        sizeBytes: Long? = null,
        installedDigest: String? = null,
    ): ModelDescriptor {
        val metadata = runCatching { Json.parseToJsonElement(entry.propertiesJson).jsonObject }.getOrNull()
        val expectedDigest = metadata?.get("sha256")?.jsonPrimitive?.content
        val format = metadata?.get("format")?.jsonPrimitive?.content
        val quantization = metadata?.get("quantization")?.jsonPrimitive?.content
        val extraMetadata = metadata?.entries
            ?.filter { it.value is kotlinx.serialization.json.JsonPrimitive }
            ?.associate { it.key to it.value.jsonPrimitive.content }
            ?: emptyMap()
        return ModelDescriptor(
            id = entry.id,
            name = entry.name,
            kind = entry.kind,
            providerId = entry.provider,
            isLocal = true,
            contextWindow = contextWindow(entry),
            sizeBytes = sizeBytes,
            digest = installedDigest ?: expectedDigest,
            format = format,
            quantization = quantization,
            metadata = extraMetadata,
        )
    }

    private fun contextWindow(entry: CatalogEntry): Int =
        runCatching {
            Json.parseToJsonElement(entry.propertiesJson).jsonObject["context_window"]?.jsonPrimitive?.content?.toIntOrNull()
                ?: DEFAULT_CONTEXT
        }.getOrDefault(DEFAULT_CONTEXT)

    /** Uses the persistent installed path; filename scanning is only a legacy fallback. */
    private suspend fun resolveModelFile(modelId: String): File {
        val installed = catalogManager.listInstalled().getOrThrow().firstOrNull { it.modelId == modelId }
        if (installed != null) return File(installed.path)
        return File(modelsDir, "$modelId.gguf")
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
