package dev.aidos.vision

import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import java.io.File
import java.security.MessageDigest

/**
 * The vision models installed under [modelsDir], one [VisionPackage] directory per model id. Shared by
 * the JVM and Android backend decorators, like [dev.aidos.decisions.DecisionPackages]: the decorators
 * only add delegation to their host's own backend.
 */
class VisionPackages(private val modelsDir: File, private val images: ImageDecoder) {
    private val live = HashMap<String, VisionModelAdapter>()

    /** The package directory for [modelId], or null. The id becomes a path component, so anything that could leave [modelsDir] is refused. */
    fun dirFor(modelId: String): File? {
        if (modelId.isEmpty() || modelId.contains('/') || modelId.contains('\\') || modelId.contains("..")) return null
        return File(modelsDir, modelId).takeIf { VisionPackage.isPackage(it) }
    }

    fun contains(modelId: String): Boolean = dirFor(modelId) != null

    /** The package directory at [artifactPath] when it is a package inside [modelsDir] (catalog installs record a path, not a directory-name id). */
    fun dirAt(artifactPath: String?): File? {
        if (artifactPath.isNullOrBlank()) return null
        val dir = File(artifactPath).canonicalFile
        if (!dir.toPath().startsWith(modelsDir.canonicalFile.toPath()) || dir == modelsDir.canonicalFile) return null
        return dir.takeIf { VisionPackage.isPackage(it) }
    }

    fun handles(modelId: String, artifactPath: String?): Boolean = contains(modelId) || dirAt(artifactPath) != null

    fun descriptors(): List<ModelDescriptor> =
        modelsDir.listFiles { f -> f.isDirectory }?.filter { VisionPackage.isPackage(it) }.orEmpty().map { dir ->
            ModelDescriptor(
                id = dir.name,
                name = dir.name,
                kind = ModelKind.VISION,
                providerId = "local",
                isLocal = true,
                contextWindow = VisionPackage.readConfig(dir).contextWindow,
                sizeBytes = VisionPackage.graphs(dir)!!.sizeBytes,
                digest = null,
                format = "onnx",
            )
        }

    /** SHA-256 over the three graphs, in a fixed order; the config files are small and not weights. */
    fun digest(modelId: String): String? = dirFor(modelId)?.let { dir ->
        val g = VisionPackage.graphs(dir)!!
        val md = MessageDigest.getInstance("SHA-256")
        for (f in listOf(g.encoder, g.embed, g.decoder)) {
            f.inputStream().use { s ->
                val buf = ByteArray(1 shl 16)
                while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    fun load(modelId: String, artifactPath: String? = null): Result<VisionModelAdapter> {
        val dir = dirFor(modelId) ?: dirAt(artifactPath)
            ?: return Result.failure(IllegalStateException("MODEL_NOT_INSTALLED: $modelId"))
        live[modelId]?.let { return Result.success(it) }
        return try {
            val adapter = VisionModelAdapter(VisionPackage.open(modelId, dir, images))
            live[modelId] = adapter
            Result.success(adapter)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("MODEL_LOAD_FAILED: $modelId: ${e.message ?: e::class.simpleName}", e))
        }
    }

    /** Closes a loaded package; false when [modelId] is not a loaded vision model (so the caller can delegate). */
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
}
