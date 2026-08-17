package dev.aidos.engine.cli

import dev.aidos.kernel.ModelDescriptor
import dev.aidos.modelruntime.GlobalModelRuntime
import kotlinx.coroutines.runBlocking
import java.io.File

/** JVM entry point for the engine-only CLI. */
fun main(args: Array<String>) = runBlocking {
    val cli = EngineCli(GlobalModelRuntime.create())
    try { execute(cli, args) } catch (e: Exception) {
        System.err.println("error: ${e.message ?: e::class.simpleName}")
        System.exit(1)
    }
}

private suspend fun execute(cli: EngineCli, args: Array<String>) {
    when (args.firstOrNull()) {
        null, "help", "--help", "-h" -> printHelp()
        "version", "--version", "-v" -> println("aidos-engine ${cli.version()}")
        "info" -> {
            println("Aidos Engine")
            println("Version: ${cli.version()}")
            println("JVM: ${System.getProperty("java.version")}")
            println("OS: ${System.getProperty("os.name")} ${System.getProperty("os.arch")}")
            println("Backend: llama.cpp (JVM)")
            println("Models: ${System.getProperty("aidos.models.dir") ?: File(System.getProperty("user.home"), ".aidos/models").absolutePath}")
            println("Loaded: ${cli.loaded().size}")
        }
        "models" -> printModels(cli.catalog())
        "installed" -> printModels(cli.installed())
        "loaded" -> cli.loaded().forEach(::println)
        "load" -> {
            val modelId = requireArgument(args, 1, "model id")
            cli.load(modelId).fold({ println("loaded $modelId") }, { throw it })
        }
        "unload" -> {
            val modelId = requireArgument(args, 1, "model id")
            cli.unload(modelId)
            println("unloaded $modelId")
        }
        "infer" -> {
            val modelId = requireArgument(args, 1, "model id")
            val prompt = requireArgument(args, 2, "prompt")
            cli.infer(modelId, prompt).fold({ println(it.text.orEmpty()) }, { throw it })
        }
        "chat" -> chat(cli, requireArgument(args, 1, "model id"))
        "download" -> {
            val provider = requireArgument(args, 1, "provider")
            require(provider == "hf" || provider == "huggingface") { "unsupported download provider: $provider (use hf)" }
            val repo = requireArgument(args, 2, "Hugging Face repository")
            val filename = requireArgument(args, 3, "artifact filename")
            println("downloaded ${cli.downloadFromHuggingFace(repo, filename).absolutePath}")
        }
        "model" -> {
            requireArgument(args, 1, "model command").also { require(it == "inspect") { "unsupported model command: $it (use inspect)" } }
            val file = File(requireArgument(args, 2, "GGUF file"))
            val inspection = cli.inspectModel(file).getOrElse { throw it }
            println("File: ${inspection.file}")
            println("Size: ${inspection.sizeBytes} bytes")
            println("GGUF version: ${inspection.version}")
            println("Model: ${inspection.modelName}")
            println("Architecture: ${inspection.architecture}")
            println("Size label: ${inspection.sizeLabel}")
            println("Parameters: ${inspection.parameterCount}")
            println("Context: ${inspection.contextWindow}")
            println("Quantization: ${inspection.quantization}")
            println("Tensors: ${inspection.tensorCount}")
            println("Metadata entries: ${inspection.kvCount}")
        }
        "test" -> {
            val test = requireArgument(args, 1, "test name")
            require(test == "backend") { "unsupported test: $test (use backend)" }
            val result = cli.testBackend()
            println("Backend: ${result.backend}")
            println("Catalog models: ${result.catalogCount}")
            println("Installed models: ${result.installedCount}")
            if (!result.passed) error("backend test failed: catalog is empty")
            println("PASS")
        }
        else -> { System.err.println("unknown command: ${args[0]}"); printHelp(); System.exit(2) }
    }
}

private suspend fun chat(cli: EngineCli, modelId: String) {
    println("Aidos Engine chat — /quit to exit")
    val prompts = mutableListOf<String>()
    while (true) {
        print("You: "); System.out.flush()
        val prompt = readlnOrNull() ?: break
        if (prompt == "/quit" || prompt == "/exit") break
        if (prompt.isBlank()) continue
        prompts += prompt
        val response = cli.chat(modelId, prompts).getOrElse { throw it }.last()
        println("AI: ${response.text.orEmpty()}")
    }
}

private fun requireArgument(args: Array<String>, index: Int, name: String): String =
    args.getOrNull(index)?.takeIf { it.isNotBlank() } ?: error("missing $name")

private fun printModels(models: List<ModelDescriptor>) {
    if (models.isEmpty()) { println("No models."); return }
    models.forEach { model -> println("${model.id}\t${model.name}\t${model.kind}\t${model.contextWindow}") }
}

private fun printHelp() {
    println(
        """
        Aidos Engine CLI

        Usage:
          aidos-engine <command> [arguments]

        Commands:
          version                           Show CLI version
          info                              Show engine/runtime information
          models                            List models in the catalog
          installed                         List installed models
          loaded                            List currently loaded models
          load <model>                      Load a model
          unload <model>                    Unload a model
          download hf <repo> <filename>     Download a model artifact from Hugging Face
          infer <model> <prompt>            Run one prompt and print the response
          chat <model>                      Interactive multi-turn chat
          model inspect <file>              Inspect a local GGUF file
          test backend                      Run backend smoke test
          help                              Show this help
        """.trimIndent()
    )
}
