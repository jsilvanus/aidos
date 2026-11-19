package dev.aidos.amp

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.Turn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AmpAgentRunnerTest {

    @Test
    fun testMultiStepAgenticToolExecution() = runBlocking {
        var callCount = 0

        // Mock adapter simulating tool invocation on step 1, then final answer on step 2
        val mockAdapter = object : ModelAdapter {
            override val providerId: String = "mock"
            override val modelId: String = "mock-model"
            override val modelVersion: String = "1.0"
            override val contextWindow: Int = 4096
            override val isLocal: Boolean = true
            override fun supportsNativeToolCalls(): Boolean = true

            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
                callCount++
                return if (callCount == 1) {
                    // Step 1: Model decides to call check_calendar
                    Result.success(
                        ModelResponse(
                            outputs = listOf(
                                ToolCallOutput(
                                    ToolCall(
                                        callId = "call_cal_1",
                                        toolName = "check_calendar",
                                        arguments = buildJsonObject { put("time", "17:00") },
                                        capabilityId = null,
                                    )
                                )
                            ),
                            stopReason = StopReason.TOOL_USE,
                            usage = null,
                            model = null,
                        )
                    )
                } else {
                    // Step 2: Model receives tool result and produces final answer
                    // Assert that tool result is in messages
                    val lastTurn = request.messages.last()
                    assertTrue(lastTurn is Turn.ToolResult)

                    Result.success(
                        ModelResponse(
                            outputs = listOf(
                                TextOutput("You are completely free at 5:00 PM.")
                            ),
                            stopReason = StopReason.END_TURN,
                            usage = null,
                            model = null,
                        )
                    )
                }
            }
        }

        var toolExecuted = false
        val calendarTool = AmpToolDefinition(
            name = "check_calendar",
            description = "Check user calendar schedule",
            inputSchema = buildJsonObject { put("type", "object") },
            handler = { args ->
                toolExecuted = true
                AmpToolExecutionResult("No events scheduled for 17:00.")
            }
        )

        val runner = AmpAgentRunner(adapter = mockAdapter, maxSteps = 5)

        val result = runner.executeTurn(
            systemPrompt = "You are Aidos assistant.",
            conversationHistory = emptyList(),
            incomingMessage = "Can I meet at 5 PM?",
            tools = listOf(calendarTool)
        )

        assertTrue(result.isSuccess)
        val turnResult = result.getOrThrow()
        assertTrue(toolExecuted)
        assertEquals(2, turnResult.totalSteps)
        assertEquals("You are completely free at 5:00 PM.", turnResult.replyText)
        assertEquals(1, turnResult.executedToolCalls.size)
        assertEquals("check_calendar", turnResult.executedToolCalls.first().toolName)
    }

    @Test
    fun toolResultsPassThroughTheScreenBeforeTheModelAndTheCaller() = runBlocking {
        // RFC-0035 "Secrets found in content": the model must never see what the screen removed.
        var seenByModel: String? = null
        var step = 0
        val adapter = object : ModelAdapter {
            override val providerId = "mock"
            override val modelId = "mock-model"
            override val modelVersion = "1.0"
            override val contextWindow = 4096
            override val isLocal = true
            override fun supportsNativeToolCalls() = true
            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
                step++
                if (step == 1) {
                    return Result.success(ModelResponse(
                        outputs = listOf(ToolCallOutput(ToolCall("c1", "read_file", buildJsonObject { }, null))),
                        stopReason = StopReason.TOOL_USE, usage = null, model = null,
                    ))
                }
                val last = request.messages.last() as Turn.ToolResult
                seenByModel = (last.result.content.single() as ContentBlock.Text).text
                return Result.success(ModelResponse(listOf(TextOutput("done")), StopReason.END_TURN, null, null))
            }
        }
        val tool = AmpToolDefinition("read_file", "reads", buildJsonObject { put("type", "object") }) {
            AmpToolExecutionResult("token=SECRET-VALUE")
        }
        val screen = AmpToolResultScreen { name, text -> "$name:" + text.replace("SECRET-VALUE", "[redacted]") }

        val result = AmpAgentRunner(adapter, 5, screen).executeTurn("sys", emptyList(), "go", listOf(tool)).getOrThrow()

        assertEquals("read_file:token=[redacted]", seenByModel)
        assertEquals("read_file:token=[redacted]", result.executedToolCalls.single().resultText)
    }
}
