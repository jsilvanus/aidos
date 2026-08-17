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
import dev.aidos.modelruntime.GgufLoader
import dev.aidos.modelruntime.GlobalModelRuntime
import java.io.File

/** Testable command layer for the Aidos Engine CLI. */
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

    suspend fun load(modelId: String): Result<Unit> = runtime.load(modelId).map { Unit }

    suspend fun unload(modelId: String) = runtime.unload(modelId)

    /** Run one user prompt against a model and return the model response. */
    suspend fun infer(modelId: String, prompt: String, maxOutputTokens: Int = 512): Result<ModelResponse> =
        invoke(modelId, listOf(prompt), maxOutputTokens)

    /** Interactive-chat primitive: invoke the model with the accumulated user turns. */
    suspend fun chat(
        modelId: String,
        prompts: List<String>,
        maxOutputTokens: Int = 512,
    ): Result<List<ModelResponse>> {
        require(prompts.isNotEmpty()) { "chat requires at least one prompt" }
        val adapter = runtime.load(modelId).getOrElse { return Result.failure(it) }
        return try {
            val turns = mutableListOf<Turn>()
            val responses = mutableListOf<ModelResponse>()
            for (prompt in prompts) {
                turns += Turn.User(
                    content = listOf(ContentBlock.Text(prompt)),
                    trustLevel = TrustLevel.UNTRUSTED,
                )
                val response = adapter.invoke(
                    ModelRequest(
                        messages = turns.toList(),
                        tools = emptyList(),
                        toolChoice = ToolChoice.None,
                        maxOutputTokens = maxOutputTokens,
                    )
                ).getOrElse { return Result.failure(it) }
                responses += response
                response.text?.let { turns += Turn.Assistant(content = listOf(ContentBlock.Text(it))) }
            }
            Result.success(responses)
        } finally {
            runtime.unload(modelId)
        }
    }

    private suspend fun invoke(modelId: String, prompts: List<String>, maxOutputTokens: Int): Result<ModelResponse> {
        val adapter = runtime.load(modelId).getOrElse { return Result.failure(it) }
        return try {
            adapter.invoke(
                ModelRequest(
                    messages = prompts.map {
                        Turn.User(content = listOf(ContentBlock.Text(it)), trustLevel = TrustLevel.UNTRUSTED)
                    },
                    tools = emptyList(),
                    toolChoice = ToolChoice.None,
                    maxOutputTokens = maxOutputTokens,
                )
            )
        } finally {
            runtime.unload(modelId)
        }
    }

    /** Download one GGUF file from Hugging Face using the shared DownloadManager. */
    suspend fun downloadFromHuggingFace(repo: String, filename: String): File {
        require(repo.isNotBlank()) { "Hugging Face repository must not be blank" }
        require(filename.isNotBlank()) { "Hugging Face filename must not be blank" }
        require(!filename.contains("/") && !filename.contains("\\")) { "filename must be a file name, not a path" }
        require(filename.endsWith(".gguf", ignoreCase = true)) { "Hugging Face model file must be a .gguf file" }

        modelsDirectory.mkdirs()
        val destination = File(modelsDirectory, filename)
        val url = "https://huggingface.co/${repo.trim('/')}/resolve/main/$filename?download=true"
        var completed: File? = null
        var failure: Throwable? = null
        downloadManager.download(url, destination.absolutePath, expectedDigest = null).collect { event ->
            when (event) {
                is DownloadEvent.Started -> println("download started at ${event.resumeFromBytes} bytes${event.totalBytes?.let { " / $it bytes" } ?: ""}")
                is DownloadEvent.Progress -> if (event.totalBytes != null && event.totalBytes > 0) {
                    val percent = (event.bytesDownloaded * 100 / event.totalBytes).coerceIn(0, 100)
                    print("\rDownloading: $percent%")
                }
                is DownloadEvent.Completed -> { println(); completed = File(event.finalPath) }
                is DownloadEvent.Failed -> failure = IllegalStateException(event.reason)
                is DownloadEvent.DigestMismatch -> failure = IllegalStateException("SHA-256 mismatch: expected ${event.expectedDigest}, got ${event.actualDigest}")
            }
        }
        failure?.let { throw it }
        return completed ?: error("Download did not complete")
    }

    /** Inspect a local GGUF without loading it into the inference backend. */
    fun inspectModel(file: File): Result<GgufInspection> {
        val metadata = GgufLoader.loadMetadata(file)
            ?: return Result.failure(IllegalArgumentException("Not a valid GGUF file: ${file.absolutePath}"))
        return Result.success(
            GgufInspection(
                file = file.absolutePath,
                sizeBytes = file.length(),
                version = metadata.version,
                tensorCount = metadata.tensorCount,
                kvCount = metadata.kvCount,
                modelName = metadata.modelName,
                contextWindow = metadata.contextWindow,
                quantization = metadata.quantization,
            )
        )
    }

    /** Backend smoke test that does not require a model to be loaded. */
    suspend fun testBackend(): BackendTestResult {
        val catalog = runtime.catalog()
        val installed = runtime.installed()
        return BackendTestResult(
            backend = "llama.cpp",
            catalogCount = catalog.size,
            installedCount = installed.size,
            passed = catalog.isNotEmpty(),
        )
    }

    fun version(): String = VERSION

    companion object { const val VERSION = "0.1.0" }
}

data class GgufInspection(
    val file: String,
    val sizeBytes: Long,
    val version: Int,
    val tensorCount: Long,
    val kvCount: Long,
    val modelName: String,
    val contextWindow: Int,
    val quantization: String,
)

data class BackendTestResult(
    val backend: String,
    val catalogCount: Int,
    val installedCount: Int,
    val passed: Boolean,
)
