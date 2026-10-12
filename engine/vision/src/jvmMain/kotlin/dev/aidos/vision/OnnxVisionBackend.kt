package dev.aidos.vision

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.modelruntime.InferenceBackend
import java.io.File

/**
 * Adds vision models to an existing JVM [InferenceBackend] (RFC-0022): a model is a [VisionPackage]
 * directory `<modelsDir>/<modelId>/`; every other id is delegated unchanged. Stack it with
 * [dev.aidos.decisions.OnnxDecisionBackend]; the two recognise disjoint directory shapes.
 * The Android host has the same decorator in :androidapp over the same [VisionPackages].
 */
class OnnxVisionBackend(
    private val delegate: InferenceBackend,
    modelsDir: File,
    images: ImageDecoder = JvmImageDecoder,
) : InferenceBackend {
    private val packages = VisionPackages(modelsDir, images)

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
