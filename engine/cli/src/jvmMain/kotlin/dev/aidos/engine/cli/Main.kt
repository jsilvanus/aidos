package dev.aidos.engine.cli

import dev.aidos.modelruntime.GlobalModelRuntime
import kotlinx.coroutines.runBlocking

/**
 * JVM entry point for the engine-only CLI.
 *
 * This is intentionally separate from agent/cli: it talks directly to the engine runtime and
 * never connects to the Aidos agent daemon.
 */
fun main(args: Array<String>) = runBlocking {
    val cli = EngineCli(GlobalModelRuntime.create())

    try {
        execute(cli, args)
    } catch (e: Exception) {
        System.err.println("error: ${e.message ?: e::class.simpleName}")
        System.exit(1)
    }
}

private suspend fun execute(cli: EngineCli, args: Array<String>) {
    when (args.firstOrNull()) {
        null, "help", "--help", "-h" -> printHelp()
        "version", "--version", "-v" -> println("aidos-engine ${cli.version()}")
        "models" -> printModels(cli.catalog())
        "installed" -> printModels(cli.installed())
        "loaded" -> cli.loaded().forEach(::println)
        "load" -> {
            val modelId = requireArgument(args, 1, "model id")
            cli.load(modelId).fold(
                onSuccess = { println("loaded $modelId") },
                onFailure = { throw it },
            )
        }
        "unload" -> {
            val modelId = requireArgument(args, 1, "model id")
            cli.unload(modelId)
            println("unloaded $modelId")
        }
        "infer" -> {
            val modelId = requireArgument(args, 1, "model id")
            val prompt = requireArgument(args, 2, "prompt")
            cli.infer(modelId, prompt).fold(
                onSuccess = { response -> println(response.text.orEmpty()) },
                onFailure = { throw it },
            )
        }
        "download" -> {
            val provider = requireArgument(args, 1, "provider")
            require(provider == "hf" || provider == "huggingface") {
                "unsupported download provider: $provider (use hf)"
            }
            val repo = requireArgument(args, 2, "Hugging Face repository")
            val filename = requireArgument(args, 3, "GGUF filename")
            val file = cli.downloadFromHuggingFace(repo, filename)
            println("downloaded ${file.absolutePath}")
        }
        "info" -> {
            println("Aidos Engine")
            println("Version: ${cli.version()}")
            println("JVM: ${System.getProperty("java.version")}")
            println("Backend: llama.cpp (JVM)")
        }
        else -> {
            System.err.println("unknown command: ${args[0]}")
            printHelp()
            System.exit(2)
        }
    }
}

private fun requireArgument(args: Array<String>, index: Int, name: String): String =
    args.getOrNull(index)?.takeIf { it.isNotBlank() }
        ?: error("missing $name")

private fun printModels(models: List<dev.aidos.kernel.ModelDescriptor>) {
    if (models.isEmpty()) {
        println("No models.")
        return
    }

    models.forEach { model ->
        println("${model.id}\t${model.name}\t${model.kind}\t${model.contextWindow}")
    }
}

private fun printHelp() {
    println(
        """
        Aidos Engine CLI

        Usage:
          aidos-engine <command> [arguments]

        Commands:
          info                              Show engine/runtime information
          version                           Show CLI version
          models                            List models in the catalog
          installed                         List installed models
          loaded                            List currently loaded models
          load <model>                      Load a model
          unload <model>                    Unload a model
          infer <model> <prompt>            Run one prompt and print the response
          download hf <repo> <filename>     Download a GGUF file from Hugging Face
          help                              Show this help

        Examples:
          aidos-engine download hf Qwen/Qwen2.5-3B-Instruct-GGUF qwen2.5-3b-instruct-q4_k_m.gguf
          aidos-engine infer qwen2.5-3b-instruct-q4_k_m "What is the capital of Finland?"

        This CLI talks directly to the engine. It is independent of agent/cli.
        """.trimIndent()
    )
}
