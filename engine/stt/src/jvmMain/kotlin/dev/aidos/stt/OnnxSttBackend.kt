package dev.aidos.stt

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.modelruntime.InferenceBackend
import java.io.File

/**
 * Adds Whisper speech-to-text models to an existing JVM [InferenceBackend] (RFC-0103). A model is a
 * package directory `<modelsDir>/<modelId>/` (see [WhisperPackage]); every other id is delegated, so
 * llama.cpp and Laya behaviour is unchanged (stack it around [dev.aidos.decisions.OnnxDecisionBackend] or inside it).
 * The Android host has the same decorator in :androidapp over the same [SttPackages].
 */
class OnnxSttBackend(
    private val delegate: InferenceBackend,
    modelsDir: File,
) : InferenceBackend {
    private val packages = SttPackages(modelsDir)

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
