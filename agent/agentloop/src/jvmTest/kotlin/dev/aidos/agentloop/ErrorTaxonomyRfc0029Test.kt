package dev.aidos.agentloop

import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ErrorCodes
import dev.aidos.kernel.InferenceRouter
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RoutingContext
import dev.aidos.kernel.RoutingDecision
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import dev.aidos.prompt.PromptAssembler
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RFC-0029 gap-closure test:
 * `ErrorClass.isModelAudience` gates transcript rendering vs user-only failure in [AgentLoop].
 */
class ErrorTaxonomyRfc0029Test {

    @Test
    fun `AgentLoop renders model-audience errors and hides user-only errors from transcript`() = runBlocking {
        // 1. Model-audience error (INVALID_INPUT) is rendered as <tool_result status="error"> and model recovers.
        var step = 0
        var seenToolResultText: String? = null
        val modelAudienceAdapter = object : ModelAdapter {
            override val providerId = "test"
            override val modelId = "m1"
            override val modelVersion = "1.0"
            override val contextWindow = 4096
            override val isLocal = true
            override fun supportsNativeToolCalls() = false
            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
                step++
                return if (step == 1) {
                    Result.success(
                        ModelResponse(
                            outputs = listOf(ToolCallOutput(ToolCall("c1", "fs:read", JsonObject(emptyMap()), null))),
                            stopReason = StopReason.TOOL_USE,
                            usage = null,
                            model = null,
                        )
                    )
                } else {
                    seenToolResultText = request.messages.filterIsInstance<Turn.ToolResult>().single()
                        .result.content.filterIsInstance<ContentBlock.Text>().single().text
                    Result.success(
                        ModelResponse(
                            outputs = listOf(TextOutput("recovered")),
                            stopReason = StopReason.END_TURN,
                            usage = null,
                            model = null,
                        )
                    )
                }
            }
        }
        val invalidInputBroker = object : EffectBroker {
            override fun register(tool: Tool) {}
            override fun descriptorsFor(subjectId: String, profile: PlatformProfile, networkAvailable: Boolean) = emptyList<ToolDescriptor>()
            override suspend fun preview(subjectId: String, call: ToolCall): Result<Preview> = error("unused")
            override suspend fun invoke(subjectId: String, call: ToolCall, runTaint: TrustLevel) = ToolCallResult(
                callId = call.callId,
                outcome = ToolOutcome.Failed(AidosError(ErrorCodes.TOOL_INVALID_ARGUMENTS, ErrorClass.INVALID_INPUT, "missing path")),
                content = listOf(ContentBlock.Text("missing path")),
                trustLevel = TrustLevel.TRUSTED,
            )
            override suspend fun cancel(callId: String) {}
        }
        val loop1 = AgentLoop(
            router = object : InferenceRouter {
                override suspend fun select(kind: ModelKind, context: RoutingContext) = RoutingDecision.Local(modelAudienceAdapter)
            },
            assembler = PromptAssembler(),
            broker = invalidInputBroker,
        )
        val outcome1 = loop1.run(RunRequest(userMessage = "read file"))
        assertIs<RunOutcome.Completed>(outcome1)
        assertEquals(
            "<tool_result call_id=\"c1\" status=\"error\" code=\"tool.invalid_arguments\">\n  missing path\n</tool_result>",
            seenToolResultText,
        )

        // 2. User-only error (INTERNAL) terminates immediately without rendering into model transcript.
        var invokedAfterInternal = false
        val userOnlyAdapter = object : ModelAdapter {
            override val providerId = "test"
            override val modelId = "m1"
            override val modelVersion = "1.0"
            override val contextWindow = 4096
            override val isLocal = true
            override fun supportsNativeToolCalls() = false
            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
                if (request.messages.any { it is Turn.ToolResult }) invokedAfterInternal = true
                return Result.success(
                    ModelResponse(
                        outputs = listOf(ToolCallOutput(ToolCall("c2", "db:op", JsonObject(emptyMap()), null))),
                        stopReason = StopReason.TOOL_USE,
                        usage = null,
                        model = null,
                    )
                )
            }
        }
        val internalBroker = object : EffectBroker {
            override fun register(tool: Tool) {}
            override fun descriptorsFor(subjectId: String, profile: PlatformProfile, networkAvailable: Boolean) = emptyList<ToolDescriptor>()
            override suspend fun preview(subjectId: String, call: ToolCall): Result<Preview> = error("unused")
            override suspend fun invoke(subjectId: String, call: ToolCall, runTaint: TrustLevel) = ToolCallResult(
                callId = call.callId,
                outcome = ToolOutcome.Failed(AidosError(ErrorCodes.STORAGE_CORRUPT, ErrorClass.INTERNAL, "corrupt page")),
                content = listOf(ContentBlock.Text("corrupt page")),
                trustLevel = TrustLevel.TRUSTED,
            )
            override suspend fun cancel(callId: String) {}
        }
        val loop2 = AgentLoop(
            router = object : InferenceRouter {
                override suspend fun select(kind: ModelKind, context: RoutingContext) = RoutingDecision.Local(userOnlyAdapter)
            },
            assembler = PromptAssembler(),
            broker = internalBroker,
        )
        val outcome2 = loop2.run(RunRequest(userMessage = "do op"))
        assertIs<RunOutcome.Failed>(outcome2)
        assertTrue(outcome2.reason.contains(ErrorCodes.STORAGE_CORRUPT))
        assertEquals(false, invokedAfterInternal)
    }
}
