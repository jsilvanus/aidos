package dev.aidos.vision

import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelStreamEvent
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Usage
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A loaded vision model as the ModelRuntime sees it: a chat adapter whose user turns may carry
 * [dev.aidos.kernel.ContentBlock.Image]s. Requests run one at a time; the model is a single
 * ONNX Runtime session set and a phone has the memory for one decode, not two.
 */
class VisionModelAdapter(
    private val model: SmolVlmModel,
    override val modelVersion: String = "onnx",
) : ModelAdapter, AutoCloseable {
    override val providerId = "local"
    override val modelId: String get() = model.modelId
    override val contextWindow: Int get() = model.config.contextWindow
    override val isLocal = true

    private val turn = Mutex()

    override fun supportsNativeToolCalls(): Boolean = false

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        var done: ModelResponse? = null
        return try {
            invokeStreaming(request).collect { event ->
                when (event) {
                    is ModelStreamEvent.Done -> done = event.response
                    is ModelStreamEvent.Failed -> throw event.error
                    else -> Unit
                }
            }
            Result.success(done ?: error("Generation for $modelId ended without a result"))
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun invokeStreaming(request: ModelRequest): Flow<ModelStreamEvent> = flow {
        if (request.tools.isNotEmpty()) {
            emit(ModelStreamEvent.Failed(UnsupportedOperationException("Model $modelId does not support tool calls")))
            return@flow
        }
        turn.withLock {
            try {
                val text = StringBuilder()
                for (step in model.generate(request.messages, request.maxOutputTokens, request.stopConditions)) {
                    when (step) {
                        is VisionStep.Text -> {
                            text.append(step.delta)
                            emit(ModelStreamEvent.Delta(step.delta))
                        }
                        is VisionStep.Finished -> emit(
                            ModelStreamEvent.Done(
                                ModelResponse(
                                    outputs = if (text.isEmpty()) emptyList() else listOf(TextOutput(text.toString())),
                                    stopReason = when (step.reason) {
                                        FinishReason.END_OF_TURN -> StopReason.END_TURN
                                        FinishReason.STOP_SEQUENCE -> StopReason.STOP_SEQUENCE
                                        FinishReason.MAX_TOKENS, FinishReason.CONTEXT_FULL -> StopReason.MAX_TOKENS
                                    },
                                    usage = Usage(step.promptTokens, step.newTokens, step.promptTokens + step.newTokens),
                                    model = ModelRef(modelId, modelVersion),
                                )
                            )
                        )
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                emit(ModelStreamEvent.Failed(e))
            }
        }
    }

    override fun close() = model.close()
}
