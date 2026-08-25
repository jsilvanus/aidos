package dev.aidos.modelruntime

import de.kherud.llama.InferenceParameters
import de.kherud.llama.LlamaModel
import de.kherud.llama.ModelParameters
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelStreamEvent
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.Usage
import dev.aidos.kernel.Turn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
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
     *
     * Implemented in terms of [invokeStreaming] rather than its own generation loop, so there is
     * exactly one place that walks `model.generate()`'s token iterator (RFC-0021 "Streaming";
     * Dictator plan S4). Before this, [invoke] buffered every token into one string before
     * returning, which is where the *actual* streaming problem lived: Engine's SSE endpoint could
     * only ever start emitting after generation had already finished, whatever the transport did.
     */
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        var finalResponse: ModelResponse? = null
        try {
            invokeStreaming(request).collect { event ->
                if (event is ModelStreamEvent.Done) finalResponse = event.response
                if (event is ModelStreamEvent.Failed) throw event.error
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }
        val response = finalResponse
            ?: return Result.failure(
                IllegalStateException("Generation stream for $modelId completed without a result")
            )
        return Result.success(response)
    }

    /** Real token-by-token streaming — see [invoke]'s KDoc for why it now delegates here. */
    override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        if (closed) {
            emit(ModelStreamEvent.Failed(
                IllegalStateException("Model $modelId was unloaded; reload before invoking again")
            ))
            return@flow
        }

        try {
            val prompt = formatPrompt(request.messages)

            if (request.tools.isNotEmpty()) {
                compileToolGrammar(request.tools)
            }

            val output = StringBuilder()
            val tokens = model.generate(prompt, InferenceParameters()).iterator()
            var tokenCount = 0

            while (tokens.hasNext() && tokenCount < request.maxOutputTokens) {
                val token = tokens.next()
                output.append(token.text)
                tokenCount++
                emit(ModelStreamEvent.Delta(token.text))

                if (shouldStop(output.toString(), request.stopConditions)) break
            }

            val finalText = output.toString()
            val toolCalls = if (request.tools.isNotEmpty()) {
                extractToolCalls(finalText, request.tools)
            } else {
                emptyList()
            }

            val stopReason = when {
                estimateTokens(finalText) >= request.maxOutputTokens -> StopReason.MAX_TOKENS
                shouldStop(finalText, request.stopConditions) -> StopReason.STOP_SEQUENCE
                toolCalls.isNotEmpty() -> StopReason.TOOL_USE
                else -> StopReason.END_TURN
            }

            val inputTokens = estimateTokens(prompt)
            val outputTokens = estimateTokens(finalText)
            val usage = Usage(
                inputTokens = inputTokens,
                outputTokens = outputTokens,
                totalTokens = inputTokens + outputTokens,
            )

            val outputs = buildList {
                if (finalText.isNotEmpty()) add(TextOutput(finalText))
                toolCalls.forEach { add(ToolCallOutput(it)) }
            }

            emit(ModelStreamEvent.Done(
                ModelResponse(
                    outputs = outputs,
                    stopReason = stopReason,
                    usage = usage,
                    model = ModelRef(modelId, modelVersion),
                )
            ))
        } catch (e: Exception) {
            emit(ModelStreamEvent.Failed(e))
        }
    }

    /**
     * A model that declares no chat template has no notion of roles, so framing
     * its input as a `User:`/`Assistant:` dialogue feeds it text that is not part
     * of the request and biases — or with a base completion model, wholly
     * determines — what it predicts next. Such models get their content verbatim.
     *
     * The role-labelled form below is not a real chat template either; it is a
     * placeholder until per-model templates are applied (the template string is
     * now carried on [GgufMetadata.chatTemplate] for that work).
     */
    private fun formatPrompt(turns: List<Turn>): String =
        if (metadata.chatTemplate == null) rawPrompt(turns) else chatPrompt(turns)

    /** Content only, in order, with nothing the caller did not supply. */
    private fun rawPrompt(turns: List<Turn>): String {
        val prompt = StringBuilder()
        for (turn in turns) {
            when (turn) {
                is Turn.System -> prompt.append(turn.content)
                is Turn.User -> turn.content.forEach { prompt.append(renderBlock(it)) }
                is Turn.Assistant -> turn.text?.let { prompt.append(it) }
                is Turn.ToolResult -> turn.result.content.forEach { prompt.append(renderBlock(it)) }
            }
        }
        return prompt.toString()
    }

    private fun renderBlock(block: ContentBlock): String = when (block) {
        is ContentBlock.Text -> block.text
        is ContentBlock.Image -> "[Image: ${block.mimeType}]"
        is ContentBlock.Audio -> "[Audio: ${block.mimeType}]"
        is ContentBlock.ResourceRef -> "[Resource: ${block.nodeId}, ${block.sizeBytes} bytes]"
    }

    private fun chatPrompt(turns: List<Turn>): String {
        val prompt = StringBuilder()

        for (turn in turns) {
            when (turn) {
                is Turn.System -> {
                    prompt.append(turn.content).append("\n\n")
                }
                is Turn.User -> {
                    prompt.append("User: ")
                    for (block in turn.content) prompt.append(renderBlock(block))
                    prompt.append("\n")
                }
                is Turn.Assistant -> {
                    prompt.append("Assistant: ")
                    turn.text?.let { prompt.append(it) }
                    prompt.append("\n")
                }
                is Turn.ToolResult -> {
                    prompt.append("Tool result: ")
                    for (block in turn.result.content) prompt.append(renderBlock(block))
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
