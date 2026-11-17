package dev.aidos.tts

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.modelruntime.InferenceBackend
import dev.aidos.modelruntime.ModelRunSettings
import java.io.File

/**
 * Adds Piper text-to-speech voices to an existing JVM [InferenceBackend] (RFC-0117 amendment). A voice is a package
 * directory `<modelsDir>/<modelId>/` (see [VoicePackage]); every other id is delegated, so llama.cpp, Whisper and
 * Laya behaviour is unchanged. Stack it like [dev.aidos.stt.OnnxSttBackend].
 */
class OnnxTtsBackend(
    private val delegate: InferenceBackend,
    modelsDir: File,
) : InferenceBackend {
    private val packages = TtsPackages(modelsDir)

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

    // Settings are for llama.cpp; the voice packages ignore them, anything else is the delegate's.
    override suspend fun load(modelId: String, artifactPath: String?, settings: ModelRunSettings): Result<ModelAdapter> =
        if (packages.handles(modelId, artifactPath)) packages.load(modelId, artifactPath) else delegate.load(modelId, artifactPath, settings)

    override suspend fun unload(modelId: String) {
        if (!packages.unload(modelId)) delegate.unload(modelId)
    }
}
