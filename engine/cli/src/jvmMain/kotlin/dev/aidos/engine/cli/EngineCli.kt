package dev.aidos.engine.cli

import dev.aidos.downloads.DownloadEvent
import dev.aidos.downloads.DownloadManager
import dev.aidos.downloads.LocalDownloadManager
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import java.io.File

/**
 * Testable command layer for the Aidos Engine CLI.
 *
 * This class deliberately contains no terminal concerns. Main.kt is the process boundary;
 * EngineCli can be exercised directly from JVM tests.
 */
class EngineCli(
    private val runtime: ModelRuntime,
    private val modelsDirectory: File = File(
        System.getProperty("aidos.models.dir")
            ?: File(System.getProperty("user.home"), ".aidos/models").absolutePath
    ),
    private val downloadManager: DownloadManager = LocalDownloadManager(modelsDirectory.absolutePath),
) {
    suspend fun catalog(): List<ModelDescriptor> = runtime.catalog()

    suspend fun installed(): List<ModelDescriptor> = runtime.installed()

    fun loaded(): List<String> = runtime.loaded()

    suspend fun load(modelId: String): Result<Unit> =
        runtime.load(modelId).map { Unit }

    suspend fun unload(modelId: String) {
        runtime.unload(modelId)
    }

    /** Run one user prompt against a model and return the model response. */
    suspend fun infer(modelId: String, prompt: String, maxOutputTokens: Int = 512): Result<ModelResponse> {
        val adapter = runtime.load(modelId).getOrElse { return Result.failure(it) }
        return try {
            adapter.invoke(
                ModelRequest(
                    messages = listOf(
                        Turn.User(
                            content = listOf(ContentBlock.Text(prompt)),
                            trustLevel = TrustLevel.UNTRUSTED,
                        )
                    ),
                    tools = emptyList(),
                    toolChoice = ToolChoice.None,
                    maxOutputTokens = maxOutputTokens,
                )
            )
        } finally {
            runtime.unload(modelId)
        }
    }

    /**
     * Download one GGUF file from Hugging Face into the engine's model directory.
     * The actual byte transfer is delegated to the engine DownloadManager so CLI and
     * Android share the same resumable/digest-verification semantics.
     */
    suspend fun downloadFromHuggingFace(repo: String, filename: String): File {
        require(repo.isNotBlank()) { "Hugging Face repository must not be blank" }
        require(filename.isNotBlank()) { "Hugging Face filename must not be blank" }
        require(!filename.contains("/") && !filename.contains("\\")) {
            "filename must be a file name, not a path"
        }
        require(filename.endsWith(".gguf", ignoreCase = true)) {
            "Hugging Face model file must be a .gguf file"
        }

        modelsDirectory.mkdirs()
        val destination = File(modelsDirectory, filename)
        val url = "https://huggingface.co/${repo.trim('/')}/resolve/main/$filename?download=true"
        var completed: File? = null
        var failure: Throwable? = null

        downloadManager.download(url, destination.absolutePath, expectedDigest = null).collect { event ->
            when (event) {
                is DownloadEvent.Started -> {
                    val total = event.totalBytes?.let { " / $it bytes" } ?: ""
                    println("download started at ${event.resumeFromBytes} bytes$total")
                }
                is DownloadEvent.Progress -> {
                    // Keep CLI output simple; callers that need a progress bar can consume
                    // DownloadManager directly.
                    if (event.totalBytes != null && event.totalBytes > 0) {
                        val percent = (event.bytesDownloaded * 100 / event.totalBytes).coerceIn(0, 100)
                        print("\rDownloading: $percent%")
                    }
                }
                is DownloadEvent.Completed -> {
                    println()
                    completed = File(event.finalPath)
                }
                is DownloadEvent.Failed -> failure = IllegalStateException(event.reason)
                is DownloadEvent.DigestMismatch -> failure = IllegalStateException(
                    "SHA-256 mismatch: expected ${event.expectedDigest}, got ${event.actualDigest}"
                )
            }
        }

        failure?.let { throw it }
        return completed ?: error("Download did not complete")
    }

    fun version(): String = VERSION

    companion object {
        const val VERSION = "0.1.0"
    }
}
