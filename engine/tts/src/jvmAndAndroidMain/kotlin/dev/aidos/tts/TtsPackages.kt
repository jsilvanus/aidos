package dev.aidos.tts

import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelKind
import java.io.File
import java.security.MessageDigest

/**
 * The text-to-speech voices installed under [modelsDir], one [VoicePackage] directory per model id. Shared by the JVM
 * and Android backend decorators, as :stt's SttPackages is, so both resolve, describe, load and delete voices alike.
 */
class TtsPackages(private val modelsDir: File, private val graphFactory: (File) -> SpeechGraph = { OrtPiperGraph(it) }) {
    private val live = HashMap<String, TtsModelAdapterImpl>()

    /** The package directory for [modelId], or null. The id becomes a path component, so anything that could leave [modelsDir] is refused. */
    fun dirFor(modelId: String): File? {
        if (modelId.isEmpty() || modelId.contains('/') || modelId.contains('\\') || modelId.contains("..")) return null
        return File(modelsDir, modelId).takeIf { VoicePackage.open(it) != null }
    }

    fun contains(modelId: String): Boolean = dirFor(modelId) != null

    /** The package at [artifactPath] when it is a voice inside [modelsDir]; anything outside is refused. */
    fun dirAt(artifactPath: String?): File? {
        if (artifactPath.isNullOrBlank()) return null
        val dir = File(artifactPath).canonicalFile
        if (!dir.toPath().startsWith(modelsDir.canonicalFile.toPath()) || dir == modelsDir.canonicalFile) return null
        return dir.takeIf { VoicePackage.open(it) != null }
    }

    fun handles(modelId: String, artifactPath: String?): Boolean = contains(modelId) || dirAt(artifactPath) != null

    fun descriptors(): List<ModelDescriptor> =
        modelsDir.listFiles { f -> f.isDirectory }.orEmpty().filter { VoicePackage.open(it) != null }.map { dir ->
            ModelDescriptor(
                id = dir.name,
                name = dir.name,
                kind = ModelKind.TTS,
                providerId = "local",
                isLocal = true,
                contextWindow = SpeechText.MAX_INPUT_CHARS,
                sizeBytes = VoicePackage.sizeBytes(dir),
                digest = null,
                format = "onnx",
            )
        }

    /** Digest of the voice graph, which names the weights that were installed. */
    fun digest(modelId: String): String? =
        dirFor(modelId)?.let { VoicePackage.open(it)?.model }?.let { sha256(it) }

    @Synchronized
    fun load(modelId: String, artifactPath: String? = null): Result<TtsModelAdapterImpl> {
        val dir = dirFor(modelId) ?: dirAt(artifactPath)
            ?: return Result.failure(IllegalStateException("MODEL_NOT_INSTALLED: $modelId"))
        live[modelId]?.let { return Result.success(it) }
        return try {
            val pkg = VoicePackage.open(dir) ?: error("not a voice package")
            val config = PiperConfig.parse(pkg.config.readText(Charsets.UTF_8))
            val voice = PiperVoice(modelId, config, pkg.phonemizer(), graphFactory(pkg.model))
            val adapter = TtsModelAdapterImpl(voice)
            live[modelId] = adapter
            Result.success(adapter)
        } catch (e: Exception) {
            Result.failure(IllegalStateException("MODEL_LOAD_FAILED: $modelId: ${e.message ?: e::class.simpleName}", e))
        }
    }

    /** Closes a loaded voice; false when [modelId] is not a loaded voice, so the caller can delegate. */
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

    private fun sha256(file: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { s ->
            val buf = ByteArray(1 shl 16)
            while (true) { val n = s.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
