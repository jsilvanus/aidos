package dev.aidos.decisions

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import dev.aidos.modelruntime.InferenceBackend
import java.io.File
import java.security.MessageDigest

/**
 * Adds decision models to an existing JVM [InferenceBackend] (RFC-0022 D44/D45). A model is a Laya
 * package directory `<modelsDir>/<modelId>/` (see [LayaPackage]); every other id is delegated, so
 * llama.cpp behaviour is unchanged. The ONNX backend stays generic: Laya lives in [LayaPackage].
 */
class OnnxDecisionBackend(
    private val delegate: InferenceBackend,
    private val modelsDir: File,
) : InferenceBackend {
    private val live = HashMap<String, DecisionModelAdapterImpl>()

    private fun packageDir(modelId: String): File? {
        // The id becomes a path component; refuse anything that could leave modelsDir.
        if (modelId.isEmpty() || modelId.contains('/') || modelId.contains('\\') || modelId.contains("..")) return null
        return File(modelsDir, modelId).takeIf { LayaPackage.isPackage(it) }
    }

    override suspend fun catalog(): List<ModelDescriptor> = delegate.catalog()

    override suspend fun installed(): List<ModelDescriptor> {
        val packages = modelsDir.listFiles { f -> f.isDirectory }?.filter { LayaPackage.isPackage(it) }.orEmpty().map { dir ->
            val cfg = LayaPackage.readConfig(File(dir, LayaPackage.CONFIG))
            ModelDescriptor(
                id = dir.name,
                name = dir.name,
                kind = ModelKind.DECISION,
                providerId = "local",
                isLocal = true,
                contextWindow = cfg.maxLen,
                sizeBytes = LayaPackage.onnxFile(dir)!!.length(),
                digest = null,
                format = "onnx",
            )
        }
        return delegate.installed() + packages
    }

    override suspend fun computeDigest(modelId: String): String =
        packageDir(modelId)?.let { sha256(LayaPackage.onnxFile(it)!!) } ?: delegate.computeDigest(modelId)

    override suspend fun delete(modelId: String) {
        val dir = packageDir(modelId) ?: return delegate.delete(modelId)
        live.remove(modelId)?.close()
        dir.deleteRecursively()
    }

    override suspend fun load(modelId: String): Result<ModelAdapter> {
        val dir = packageDir(modelId) ?: return delegate.load(modelId)
        live[modelId]?.let { return Result.success(it) }
        return try {
            val model = LayaPackage.open(modelId, dir)
            val adapter = DecisionModelAdapterImpl(model, LayaPackage.readConfig(File(dir, LayaPackage.CONFIG)).maxLen)
            live[modelId] = adapter
            Result.success(adapter)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("MODEL_LOAD_FAILED: $modelId: ${e.message ?: e::class.simpleName}", e))
        }
    }

    override suspend fun load(modelId: String, artifactPath: String?): Result<ModelAdapter> =
        if (packageDir(modelId) != null) load(modelId) else delegate.load(modelId, artifactPath)

    override suspend fun unload(modelId: String) {
        live.remove(modelId)?.close() ?: delegate.unload(modelId)
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val buf = ByteArray(1 shl 16); while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
