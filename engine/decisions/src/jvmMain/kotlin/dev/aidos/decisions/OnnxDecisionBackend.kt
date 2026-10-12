package dev.aidos.decisions

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.modelruntime.InferenceBackend
import java.io.File

/**
 * Adds decision models to an existing JVM [InferenceBackend] (RFC-0022 D44/D45). A model is a Laya
 * package directory `<modelsDir>/<modelId>/` (see [LayaPackage]); every other id is delegated, so
 * llama.cpp behaviour is unchanged. The ONNX backend stays generic: Laya lives in [LayaPackage].
 * The Android host has the same decorator in :androidapp over the same [DecisionPackages].
 */
class OnnxDecisionBackend(
    private val delegate: InferenceBackend,
    modelsDir: File,
) : InferenceBackend {
    private val packages = DecisionPackages(modelsDir)

    override suspend fun catalog(): List<ModelDescriptor> = delegate.catalog()

    override suspend fun installed(): List<ModelDescriptor> = delegate.installed() + packages.descriptors()

    override suspend fun computeDigest(modelId: String): String =
        packages.digest(modelId) ?: delegate.computeDigest(modelId)

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
