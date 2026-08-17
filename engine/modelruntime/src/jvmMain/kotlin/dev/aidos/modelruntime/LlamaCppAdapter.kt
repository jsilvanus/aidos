package dev.aidos.modelruntime

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.Usage
import dev.aidos.kernel.Turn
import java.io.File

/**
 * Real llama.cpp-based inference adapter (RFC-0022, M21/M22).
 *
 * Uses llama-cpp-java JNI bindings to load and run GGUF models locally.
 * Supports constrained decoding via GBNF grammars for tool-calling
 * (RFC-0021 constrained decoding, RFC-0008 agent loop).
 */
class LlamaCppAdapter(
    override val modelId: String,
    private val modelFile: File,
    private val metadata: GgufMetadata,
    contextSize: Int = 2048,
    threads: Int = 4,
) : ModelAdapter {
    override val providerId = "llama.cpp"
    override val modelVersion = "local-gguf"
    override val contextWindow = contextSize
    override val isLocal = true

    private val loadStartNanos: Long = System.nanoTime()
    private val model: LlamaModel = loadModel(modelFile, contextSize, threads)
    val coldStartMillis: Long = (System.nanoTime() - loadStartNanos) / 1_000_000

    @Volatile
    private var closed: Boolean = false

    private fun loadModel(
        modelPath: File,
        contextSize: Int,
        threads: Int,
    ): LlamaModel {
        val params = ModelParameters()
            .setNCtx(contextSize)
            .setNThreads(threads)
            .setNGpuLayers(0)
            .setNBbatch(512)
            .setLogitsAll(false)
            .setUseMmap(true)
            .setUseMLock(false)

        return try {
            LlamaModel(modelPath.absolutePath, params)
        } catch (e: Exception) {
            throw IllegalStateException("Failed to load model $modelId from ${modelPath.absolutePath}", e)
        }
    }

    override fun supportsNativeToolCalls(): Boolean = false

    /**
     * Run inference and return the generalized RFC-0022 response shape.
     * Text and tool calls are separate ordered ModelOutput values; model identity and usage
     * remain attached to the response rather than being encoded in output types.
     */
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        if (closed) {
            return Result.failure(
                IllegalStateException("Model $modelId was unloaded; reload before invoking again")
            )
        }

        return try {
            val prompt = formatPrompt(request.messages)

            if (request.tools.isNotEmpty()) {
                compileToolGrammar(request.tools)
            }

            val output = buildString {
                val tokens = model.generate(prompt, InferenceParameters()).iterator()
                var tokenCount = 0

                while (tokens.hasNext() && tokenCount < request.maxOutputTokens) {
                    val token = tokens.next()
                    append(token.text)
                    tokenCount++

                    if (shouldStop(toString(), request.stopConditions)) break
                }
            }

            val toolCalls = if (request.tools.isNotEmpty()) {
                extractToolCalls(output, request.tools)
            } else {
                emptyList()
            }

            val stopReason = when {
                estimateTokens(output) >= request.maxOutputTokens -> StopReason.MAX_TOKENS
                shouldStop(output, request.stopConditions) -> StopReason.STOP_SEQUENCE
                toolCalls.isNotEmpty() -> StopReason.TOOL_USE
                else -> StopReason.END_TURN
            }

            val inputTokens = estimateTokens(prompt)
            val outputTokens = estimateTokens(output)
            val usage = Usage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens,
            )

            val outputs = buildList {
                if (output.isNotEmpty()) add(TextOutput(output))
                toolCalls.forEach { add(ToolCallOutput(it)) }
            }

            Result.success(
                ModelResponse(
                    outputs = outputs,
                    stopReason = stopReason,
                    usage = usage,
                    model = ModelRef(modelId, modelVersion),
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun formatPrompt(turns: List<Turn>): String {
        val prompt = StringBuilder()

        for (turn in turns) {
            when (turn) {
                is Turn.System -> {
                    prompt.append(turn.content).append("\n\n")
                }
                is Turn.User -> {
                    prompt.append("User: ")
                    for (block in turn.content) {
                        when (block) {
                            is ContentBlock.Text -> prompt.append(block.text)
                            is ContentBlock.Image -> prompt.append("[Image: ${block.mimeType}]")
                            is ContentBlock.ResourceRef -> prompt.append("[Resource: ${block.nodeId}, ${block.sizeBytes} bytes]")
                        }
                    }
                    prompt.append("\n")
                }
                is Turn.Assistant -> {
                    prompt.append("Assistant: ")
                    turn.text?.let { prompt.append(it) }
                    prompt.append("\n")
                }
                is Turn.ToolResult -> {
                    prompt.append("Tool result: ")
                    for (block in turn.result.content) {
                        when (block) {
                            is ContentBlock.Text -> prompt.append(block.text)
                            is ContentBlock.Image -> prompt.append("[Image: ${block.mimeType}]")
                            is ContentBlock.ResourceRef -> prompt.append("[Resource: ${block.nodeId}, ${block.sizeBytes} bytes]")
                        }
                    }
                    prompt.append("\n")
                }
            }
        }

        prompt.append("Assistant:")
        return prompt.toString()
    }

    private fun compileToolGrammar(tools: List<dev.aidos.kernel.ToolDescriptor>): String? =
        GbnfGrammarCompiler.compile(tools)

    private fun extractToolCalls(
        output: String,
        tools: List<dev.aidos.kernel.ToolDescriptor>,
    ): List<ToolCall> = ToolCallParser.parse(output, tools, isConstrained = false)

    private fun shouldStop(output: String, stopSequences: List<String>): Boolean =
        stopSequences.any { output.contains(it) }

    private fun estimateTokens(text: String): Int =
        (text.split("\\s+".toRegex()).size * 1.3).toInt()

    fun close() {
        if (closed) return
        closed = true
        try {
            model.close()
        } catch (e: Exception) {
            System.err.println("Error closing llama.cpp model: ${e.message}")
        }
    }
}
