package fi.italeino.aidos.engine.inference

import dev.aidos.decisions.DecisionPackages
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.modelruntime.InferenceBackend
import java.io.File

/**
 * Adds ONNX decision models (typed decisions / "System One", RFC-0022 D45) to the Android host's
 * backend. A model is a package directory `<filesDir>/models/<modelId>/` (model.onnx, tokenizer.json,
 * rl_agent_config.json; see docs/system-one.md); every other id goes to [delegate] unchanged.
 *
 * UNVERIFIED ON A DEVICE: written against the same [DecisionPackages] the JVM host tests, but the Android
 * targets do not compile in the cloud environment (sdk/CONTRACT.md, "Not covered").
 */
class AndroidOnnxDecisionBackend(
    private val delegate: InferenceBackend,
    modelsDir: File,
) : InferenceBackend {
    private val packages = DecisionPackages(modelsDir)

    override suspend fun catalog(): List<ModelDescriptor> = delegate.catalog()

    override suspend fun installed(): List<ModelDescriptor> = delegate.installed() + packages.descriptors()

    override suspend fun computeDigest(modelId: String): String =
        packages.digest(modelId) ?: delegate.computeDigest(modelId)

    override suspend fun computeDigest(modelId: String, artifactPath: String?): String =
        packages.digest(modelId) ?: delegate.computeDigest(modelId, artifactPath)

    override suspend fun delete(modelId: String) {
        if (!packages.delete(modelId)) delegate.delete(modelId)
    }

    override suspend fun load(modelId: String): Result<ModelAdapter> =
        if (packages.contains(modelId)) packages.load(modelId) else delegate.load(modelId)

    override suspend fun load(modelId: String, artifactPath: String?): Result<ModelAdapter> =
        if (packages.handles(modelId, artifactPath)) packages.load(modelId, artifactPath) else delegate.load(modelId, artifactPath)

    override suspend fun unload(modelId: String) {
        if (!packages.unload(modelId)) delegate.unload(modelId)
    }
}
