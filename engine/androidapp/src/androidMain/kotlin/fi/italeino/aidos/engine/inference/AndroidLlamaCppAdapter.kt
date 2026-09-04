package fi.italeino.aidos.engine.inference

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
import dev.aidos.kernel.Usage
import dev.aidos.kernel.Turn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.collect
import java.io.File

/** Real on-device llama.cpp adapter used by the Android engine. */
class AndroidLlamaCppAdapter(
    override val modelId: String,
    private val modelFile: File,
    override val contextWindow: Int,
    private val threads: Int = 4,
) : ModelAdapter {
    override val providerId: String = "llama.cpp.android"
    override val modelVersion: String = "java-llama.cpp-4.2.0"
    override val isLocal: Boolean = true

    private val model: LlamaModel
    @Volatile private var closed = false

    init {
        val parameters = ModelParameters()
            .setModel(modelFile.absolutePath)
            .setCtxSize(contextWindow)
            .setThreads(threads)
            .setThreadsBatch(threads)
            .setBatchSize(512)
            .setUbatchSize(512)
            .setGpuLayers(0)
        model = LlamaModel(parameters)
    }

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        var response: ModelResponse? = null
        return try {
            invokeStreaming(request).collect { event ->
                when (event) {
                    is ModelStreamEvent.Done -> response = event.response
                    is ModelStreamEvent.Failed -> throw event.error
                    is ModelStreamEvent.Delta -> Unit
                }
            }
            response?.let(Result.Companion::success)
                ?: Result.failure(IllegalStateException("Inference ended without a response"))
        } catch (e: Throwable) {
            Result.failure(e)
        }
    }

    override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        if (closed) {
            emit(ModelStreamEvent.Failed(IllegalStateException("Model $modelId is unloaded")))
            return@flow
        }
        if (request.tools.isNotEmpty()) {
            emit(ModelStreamEvent.Failed(UnsupportedOperationException("Android llama.cpp tool calling is not enabled yet")))
            return@flow
        }

        try {
            val prompt = formatPrompt(request.messages)
            val parameters = InferenceParameters(prompt)
                .setNPredict(request.maxOutputTokens)
                .setTemperature(0.7f)
                .setTopP(0.95f)
                .setTopK(40)
                .setStream(true)

            val output = StringBuilder()
            var tokenCount = 0
            for (token in model.generate(parameters)) {
                if (tokenCount++ >= request.maxOutputTokens) break
                output.append(token.text)
                emit(ModelStreamEvent.Delta(token.text))
                if (request.stopConditions.any(output::contains)) break
            }

            val text = output.toString()
            val inputTokens = model.encode(prompt).size
            val outputTokens = model.encode(text).size
            emit(ModelStreamEvent.Done(
                ModelResponse(
                    outputs = if (text.isEmpty()) emptyList() else listOf(TextOutput(text)),
                    stopReason = when {
                        outputTokens >= request.maxOutputTokens -> StopReason.MAX_TOKENS
                        request.stopConditions.any(text::contains) -> StopReason.STOP_SEQUENCE
                        else -> StopReason.END_TURN
                    },
                    usage = Usage(inputTokens, outputTokens, inputTokens + outputTokens),
                    model = ModelRef(modelId, modelVersion),
                )
            ))
        } catch (e: Throwable) {
            emit(ModelStreamEvent.Failed(e))
        }
    }

    fun close() {
        if (closed) return
        closed = true
        model.close()
    }

    private fun formatPrompt(turns: List<Turn>): String = buildString {
        turns.forEach { turn ->
            when (turn) {
                is Turn.System -> append(turn.content).append("\n\n")
                is Turn.User -> append("User: ").append(turn.content.joinToString("") { render(it) }).append("\n")
                is Turn.Assistant -> append("Assistant: ").append(turn.text ?: "").append("\n")
                is Turn.ToolResult -> append("Tool result: ").append(turn.result.content.joinToString("") { render(it) }).append("\n")
            }
        }
        append("Assistant:")
    }

    private fun render(block: ContentBlock): String = when (block) {
        is ContentBlock.Text -> block.text
        is ContentBlock.Image -> "[Image: ${block.mimeType}]"
        is ContentBlock.Audio -> "[Audio: ${block.mimeType}]"
        is ContentBlock.ResourceRef -> "[Resource: ${block.nodeId}]"
    }
}
