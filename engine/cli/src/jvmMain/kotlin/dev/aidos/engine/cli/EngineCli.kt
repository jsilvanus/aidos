package dev.aidos.engine.cli

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import java.io.File
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.StandardCopyOption

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
    private val httpClient: HttpClient = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).build(),
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
     *
     * [repo] is e.g. `Qwen/Qwen2.5-3B-Instruct-GGUF` and [filename] is the exact
     * repository filename. The destination is `~/.aidos/models/<filename>` (or the
     * configured `aidos.models.dir`).
     */
    fun downloadFromHuggingFace(repo: String, filename: String): File {
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
        val temporary = File(modelsDirectory, "$filename.part")
        val url = "https://huggingface.co/${repo.trim('/')}/resolve/main/$filename?download=true"

        val request = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "aidos-engine-cli")
            .GET()
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream())
        if (response.statusCode() !in 200..299) {
            response.body().close()
            throw IllegalStateException("Hugging Face download failed: HTTP ${response.statusCode()} ($url)")
        }

        response.body().use { input ->
            temporary.outputStream().buffered().use { output -> input.copyTo(output) }
        }
        Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        return destination
    }

    fun version(): String = VERSION

    companion object {
        const val VERSION = "0.1.0"
    }
}
