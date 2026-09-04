package fi.italeino.aidos.engine.inference

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelStreamEvent
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.Turn
import dev.aidos.modelruntime.GlobalModelRuntime
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

/**
 * Small in-process harness for validating the Engine's actual model runtime.
 *
 * This deliberately bypasses the loopback HTTP client. The Engine UI therefore has a way to
 * distinguish inference/runtime failures from HTTP serialization, authentication, or transport
 * failures. Streaming is consumed directly from ModelAdapter so the tester can report first-token
 * and generation timings.
 */
class InferenceTester(
    private val runtime: GlobalModelRuntime,
) {
    data class Result(
        val text: String,
        val inputTokens: Int,
        val outputTokens: Int,
        val totalTokens: Int,
        val loadMillis: Long,
        val timeToFirstTokenMillis: Long?,
        val generationMillis: Long,
        val tokensPerSecond: Double?,
    )

    suspend fun run(
        modelId: String,
        messages: List<Turn>,
        maxOutputTokens: Int = 512,
        onDelta: (String) -> Unit = {},
    ): kotlin.Result<Result> {
        val loadStart = TimeSource.Monotonic.markNow()
        val adapter = runtime.load(modelId).getOrElse { return kotlin.Result.failure(it) }
        val loadMillis = loadStart.elapsedNow().inWholeMilliseconds

        val generationStart = TimeSource.Monotonic.markNow()
        var firstTokenMillis: Long? = null
        var response: dev.aidos.kernel.ModelResponse? = null

        return try {
            adapter.invokeStreaming(
                ModelRequest(
                    messages = messages,
                    tools = emptyList(),
                    toolChoice = ToolChoice.None,
                    maxOutputTokens = maxOutputTokens,
                )
            ).collect { event ->
                when (event) {
                    is ModelStreamEvent.Delta -> {
                        if (firstTokenMillis == null) {
                            firstTokenMillis = generationStart.elapsedNow().inWholeMilliseconds
                        }
                        onDelta(event.text)
                    }
                    is ModelStreamEvent.Done -> response = event.response
                    is ModelStreamEvent.Failed -> throw event.error
                }
            }

            val completed = response
                ?: return kotlin.Result.failure(IllegalStateException("Inference completed without a response"))
            val usage = completed.usage
            val text = completed.outputs
                .filterIsInstance<dev.aidos.kernel.TextOutput>()
                .joinToString("") { it.text }
            val generationMillis = generationStart.elapsedNow().inWholeMilliseconds
            val outputTokens = usage?.outputTokens ?: 0

            kotlin.Result.success(
                Result(
                    text = text,
                    inputTokens = usage?.inputTokens ?: 0,
                    outputTokens = outputTokens,
                    totalTokens = usage?.totalTokens ?: outputTokens,
                    loadMillis = loadMillis,
                    timeToFirstTokenMillis = firstTokenMillis,
                    generationMillis = generationMillis,
                    tokensPerSecond = outputTokens.takeIf { it > 0 && generationMillis > 0 }
                        ?.let { it * 1000.0 / generationMillis },
                )
            )
        } catch (e: Exception) {
            kotlin.Result.failure(e)
        }
    }

    companion object {
        fun userTurn(text: String): Turn =
            Turn.User(listOf(ContentBlock.Text(text)), dev.aidos.kernel.TrustLevel.TRUSTED)
    }
}
