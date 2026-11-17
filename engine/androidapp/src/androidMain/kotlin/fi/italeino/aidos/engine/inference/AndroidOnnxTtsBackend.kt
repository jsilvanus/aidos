package fi.italeino.aidos.engine.inference

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.modelruntime.InferenceBackend
import dev.aidos.modelruntime.ModelRunSettings
import dev.aidos.tts.TtsPackages
import java.io.File

/**
 * Adds Piper text-to-speech voices (RFC-0117 amendment) to the Android host's backend. A voice is a package
 * directory `<filesDir>/models/<modelId>/` (see [dev.aidos.tts.VoicePackage]); every other id goes to [delegate]
 * unchanged. Stacked like [AndroidOnnxSttBackend].
 *
 * UNVERIFIED ON A DEVICE: written against the same [TtsPackages] the JVM host tests, but the Android targets do
 * not compile in the cloud environment (sdk/CONTRACT.md, "Not covered").
 */
class AndroidOnnxTtsBackend(
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
