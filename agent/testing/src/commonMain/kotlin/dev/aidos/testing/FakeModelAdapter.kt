package dev.aidos.testing

import dev.aidos.kernel.AidosError
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ErrorCodes
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.Usage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Scripted, adversarial [ModelAdapter] test double (RFC-0038).
 *
 * Supports scripted turns keyed by invocation index: text responses, tool calls (including
 * malformed JSON arguments), and simulated rate-limit or transient errors.
 */
class FakeModelAdapter(
    override val providerId: String = "fake",
    override val modelId: String = "fake-model",
    override val modelVersion: String = "1.0",
    override val contextWindow: Int = 8192,
    override val isLocal: Boolean = true,
) : ModelAdapter {

    private val turns = mutableListOf<suspend (ModelRequest) -> Result<ModelResponse>>()
    private val recordedRequests = mutableListOf<ModelRequest>()
    private var callIndex = 0

    override fun supportsNativeToolCalls(): Boolean = true

    fun script(block: ScriptBuilder.() -> Unit) {
        ScriptBuilder().apply(block)
    }

    fun requests(): List<ModelRequest> = recordedRequests.toList()

    fun reset() {
        callIndex = 0
        recordedRequests.clear()
    }

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        recordedRequests.add(request)
        val idx = callIndex++
        val handler = turns.getOrNull(idx)
            ?: return Result.success(
                ModelResponse(
                    outputs = listOf(TextOutput("Done.")),
                    stopReason = StopReason.END_TURN,
                    usage = Usage(inputTokens = 10, outputTokens = 2, totalTokens = 12),
                    model = ModelRef(modelId, modelVersion),
                )
            )
        return handler(request)
    }

    inner class ScriptBuilder {
        fun turn(block: TurnBuilder.() -> Result<ModelResponse>) {
            turns.add { _ -> TurnBuilder(turns.size).block() }
        }
    }

    inner class TurnBuilder(private val index: Int) {
        fun text(content: String): Result<ModelResponse> = Result.success(
            ModelResponse(
                outputs = listOf(TextOutput(content)),
                stopReason = StopReason.END_TURN,
                usage = Usage(inputTokens = 10, outputTokens = content.length.coerceAtLeast(1), totalTokens = 10 + content.length.coerceAtLeast(1)),
                model = ModelRef(modelId, modelVersion),
            )
        )

        fun toolCall(
            toolName: String,
            argumentsJson: String = "{}",
            capabilityId: CapabilityId? = null,
            callId: String = "call-$index",
        ): Result<ModelResponse> {
            val parsedArgs = runCatching { Json.parseToJsonElement(argumentsJson).jsonObject }
                .getOrElse { JsonObject(emptyMap()) }
            return Result.success(
                ModelResponse(
                    outputs = listOf(
                        ToolCallOutput(
                            ToolCall(
                                callId = callId,
                                toolName = toolName,
                                arguments = parsedArgs,
                                capabilityId = capabilityId,
                                rawText = if (parsedArgs.isEmpty() && argumentsJson != "{}") argumentsJson else null,
                            )
                        )
                    ),
                    stopReason = StopReason.TOOL_USE,
                    usage = Usage(inputTokens = 10, outputTokens = 8, totalTokens = 18),
                    model = ModelRef(modelId, modelVersion),
                )
            )
        }

        fun rateLimited(message: String = "rate limit exceeded"): Result<ModelResponse> =
            Result.failure(
                IllegalStateException(
                    AidosError(
                        code = ErrorCodes.MODEL_RATE_LIMITED,
                        errorClass = ErrorClass.TRANSIENT,
                        message = message,
                    ).message
                )
            )

        fun failure(error: Throwable): Result<ModelResponse> = Result.failure(error)
    }
}
