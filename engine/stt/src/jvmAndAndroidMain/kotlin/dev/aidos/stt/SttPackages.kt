package dev.aidos.stt

import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import java.io.File
import java.security.MessageDigest

/**
 * The speech-to-text models installed under [modelsDir], one [WhisperPackage] directory per model id. Shared by
 * the JVM and Android backend decorators so both resolve, describe, load and delete packages the same way;
 * the decorators only add delegation to their host's own [dev.aidos.modelruntime.InferenceBackend].
 */
class SttPackages(private val modelsDir: File) {
    private val live = HashMap<String, SttModelAdapterImpl>()

    /** The package directory for [modelId], or null. The id becomes a path component, so anything that could leave [modelsDir] is refused. */
    fun dirFor(modelId: String): File? {
        if (modelId.isEmpty() || modelId.contains('/') || modelId.contains('\\') || modelId.contains("..")) return null
        return File(modelsDir, modelId).takeIf { WhisperPackage.isPackage(it) }
    }

    fun contains(modelId: String): Boolean = dirFor(modelId) != null

    /**
     * The package directory at [artifactPath], when it is a package inside [modelsDir]. A catalog
     * install records the Hugging Face repo id ("org/name"), which cannot be a directory name, so
     * the host loads by the recorded path instead; anything outside [modelsDir] is refused.
     */
    fun dirAt(artifactPath: String?): File? {
        if (artifactPath.isNullOrBlank()) return null
        val dir = File(artifactPath).canonicalFile
        if (!dir.toPath().startsWith(modelsDir.canonicalFile.toPath()) || dir == modelsDir.canonicalFile) return null
        return dir.takeIf { WhisperPackage.isPackage(it) }
    }

    /** True when [modelId] is installed as a package, or [artifactPath] points at one. */
    fun handles(modelId: String, artifactPath: String?): Boolean = contains(modelId) || dirAt(artifactPath) != null

    fun descriptors(): List<ModelDescriptor> =
        modelsDir.listFiles { f -> f.isDirectory }?.filter { WhisperPackage.isPackage(it) }.orEmpty().map { dir ->
            ModelDescriptor(
                id = dir.name,
                name = dir.name,
                kind = ModelKind.STT,
                providerId = "local",
                isLocal = true,
                contextWindow = WhisperPackage.readGenerationConfig(dir).maxTargetPositions,
                sizeBytes = WhisperPackage.sizeBytes(dir),
                digest = null,
                format = "onnx",
            )
        }

    /** Digest of the graphs, not the tokenizer: it names the weights that were installed. */
    fun digest(modelId: String): String? =
        dirFor(modelId)?.let { sha256(listOf(WhisperPackage.encoderFile(it)!!, WhisperPackage.decoderFile(it)!!)) }

    @Synchronized
    fun load(modelId: String, artifactPath: String? = null): Result<SttModelAdapterImpl> {
        val dir = dirFor(modelId) ?: dirAt(artifactPath)
            ?: return Result.failure(IllegalStateException("MODEL_NOT_INSTALLED: $modelId"))
        live[modelId]?.let { return Result.success(it) }
        return try {
            val adapter = SttModelAdapterImpl(WhisperPackage.open(modelId, dir), WhisperPackage.readGenerationConfig(dir).maxTargetPositions)
            live[modelId] = adapter
            Result.success(adapter)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("MODEL_LOAD_FAILED: $modelId: ${e.message ?: e::class.simpleName}", e))
        }
    }

    /** Closes a loaded package; false when [modelId] is not a loaded speech-to-text model (so the caller can delegate). */
    @Synchronized
    fun unload(modelId: String): Boolean {
        val adapter = live.remove(modelId) ?: return false
        adapter.close()
        return true
    }

    @Synchronized
    fun delete(modelId: String): Boolean {
        val dir = dirFor(modelId) ?: return false
        live.remove(modelId)?.close()
        dir.deleteRecursively()
        return true
    }

    private fun sha256(files: List<File>): String {
        val md = MessageDigest.getInstance("SHA-256")
        for (f in files) f.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
