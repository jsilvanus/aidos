package dev.aidos.agentloop

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.DenialReason
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.InferenceRouter
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RoutingContext
import dev.aidos.kernel.RoutingDecision
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.TokenUsage
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import dev.aidos.prompt.PromptAssembler
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AgentLoopTest {

    private fun fakeModel(
        contextWindow: Int = 4096,
        responses: List<ModelResponse>,
    ): ModelAdapter {
        val queue = ArrayDeque(responses)
        return object : ModelAdapter {
            override val providerId = "test"
            override val modelId = "test-model"
            override val modelVersion = "1.0"
            override val contextWindow = contextWindow
            override val isLocal = true
            override fun supportsNativeToolCalls() = false
            override suspend fun invoke(request: ModelRequest): Result<ModelResponse> =
                if (queue.isEmpty()) Result.failure(NoSuchElementException("No more responses"))
                else Result.success(queue.removeFirst())
        }
    }

    private fun fakeRouter(adapter: ModelAdapter): InferenceRouter = object : InferenceRouter {
        override suspend fun select(kind: ModelKind, context: RoutingContext) =
            RoutingDecision.Local(adapter)
    }

    private fun noOpBroker(): EffectBroker = object : EffectBroker {
        override fun register(tool: Tool) {}
        override fun descriptorsFor(s: String, p: PlatformProfile, n: Boolean) = emptyList<ToolDescriptor>()
        override suspend fun invoke(s: String, call: ToolCall, runTaint: TrustLevel) =
            ToolCallResult(
                callId = call.callId,
                outcome = ToolOutcome.Ok,
                content = listOf(ContentBlock.Text("ok")),
                trustLevel = TrustLevel.UNTRUSTED,
            )
        override suspend fun preview(s: String, call: ToolCall) =
            Result.success(Preview.Description("preview"))
        override suspend fun cancel(callId: String) {}
    }

    private fun deniedBroker(): EffectBroker = object : EffectBroker {
        override fun register(tool: Tool) {}
        override fun descriptorsFor(s: String, p: PlatformProfile, n: Boolean) = emptyList<ToolDescriptor>()
        override suspend fun invoke(s: String, call: ToolCall, runTaint: TrustLevel) =
            ToolCallResult(
                callId = call.callId,
                outcome = ToolOutcome.Denied(DenialReason.ATTENUATED_BY_TAINT),
                content = listOf(ContentBlock.Text("Denied: tainted")),
                trustLevel = TrustLevel.UNTRUSTED,
            )
        override suspend fun preview(s: String, call: ToolCall) =
            Result.success(Preview.Description("preview"))
        override suspend fun cancel(callId: String) {}
    }

    private fun endTurnResponse(text: String = "Done") = ModelResponse(
        outputs = listOf(TextOutput(text)),
        stopReason = StopReason.END_TURN,
        usage = TokenUsage(10, 5, 15),
        model = ModelRef("test-model", "1.0"),
    )

    private fun toolCallResponse(toolName: String, callId: String = "call-1") = ModelResponse(
        outputs = listOf(
            ToolCallOutput(
                ToolCall(
                    callId = callId,
                    toolName = toolName,
                    arguments = buildJsonObject {},
                    capabilityId = null,
                )
            )
        ),
        stopReason = StopReason.TOOL_USE,
        usage = TokenUsage(10, 5, 15),
        model = ModelRef("test-model", "1.0"),
    )

    @Test
    fun `run completes on END_TURN`() = runTest {
        val adapter = fakeModel(responses = listOf(endTurnResponse("Task complete")))
        val loop = AgentLoop(fakeRouter(adapter), PromptAssembler(), noOpBroker())
        val result = loop.run(RunRequest(userMessage = "Hello"))
        assertIs<RunOutcome.Completed>(result)
        assertEquals(TrustLevel.TRUSTED, result.runTaint)
        assertEquals(1, result.steps)
    }

    @Test
    fun `taint is monotonic - becomes UNTRUSTED after tool result`() = runTest {
        val adapter = fakeModel(responses = listOf(
            toolCallResponse("read-file"),
            endTurnResponse(),
        ))
        val events = mutableListOf<StepEvent>()
        val loop = AgentLoop(fakeRouter(adapter), PromptAssembler(), noOpBroker()) { event ->
            events.add(event)
        }
        val result = loop.run(RunRequest(userMessage = "Read a file"))
        assertIs<RunOutcome.Completed>(result)
        assertEquals(TrustLevel.UNTRUSTED, result.runTaint)
        assertEquals("read-file", result.taintSourceDescription)
    }

    @Test
    fun `tainted run with denied egress surfaces tool name`() = runTest {
        val adapter = fakeModel(responses = listOf(
            toolCallResponse("write-remote"),
            endTurnResponse(),
        ))
        val loop = AgentLoop(fakeRouter(adapter), PromptAssembler(), deniedBroker())
        val result = loop.run(RunRequest(userMessage = "Upload data"))
        assertIs<RunOutcome.Completed>(result)
        val toolResultTurns = result.transcript.filterIsInstance<Turn.ToolResult>()
        assertTrue(toolResultTurns.isNotEmpty())
        val content = toolResultTurns.first().result.content
            .filterIsInstance<ContentBlock.Text>()
            .joinToString { it.text }
        assertTrue(content.contains("Denied"), "Expected 'Denied' in tool result content: $content")
    }

    @Test
    fun `run fails after maxSteps`() = runTest {
        val responses = (1..30).map { toolCallResponse("loop-tool", callId = "call-$it") }
        val adapter = fakeModel(responses = responses)
        val loop = AgentLoop(fakeRouter(adapter), PromptAssembler(), noOpBroker())
        val result = loop.run(RunRequest(userMessage = "Loop"))
        assertIs<RunOutcome.Failed>(result)
        assertTrue(result.reason.contains("limit") || result.reason.contains("Loop"))
    }

    @Test
    fun `loop detected when same tool call repeated three times`() = runTest {
        val repeated = toolCallResponse("echo", callId = "call-1")
        val responses = listOf(repeated, repeated, repeated, repeated)
        val adapter = fakeModel(responses = responses)
        val loop = AgentLoop(fakeRouter(adapter), PromptAssembler(), noOpBroker())
        val result = loop.run(RunRequest(userMessage = "Echo"))
        assertIs<RunOutcome.Failed>(result)
        assertTrue(result.reason.contains("Loop") || result.reason.contains("limit"))
    }

    @Test
    fun `checkpoint is called at run start and each step`() = runTest {
        val adapter = fakeModel(responses = listOf(
            toolCallResponse("tool"),
            endTurnResponse(),
        ))
        val events = mutableListOf<StepEvent>()
        val loop = AgentLoop(fakeRouter(adapter), PromptAssembler(), noOpBroker()) { events.add(it) }
        loop.run(RunRequest(userMessage = "Two steps"))
        assertTrue(events.any { it is StepEvent.RunStarted })
        assertTrue(events.any { it is StepEvent.StepStarted })
        assertTrue(events.any { it is StepEvent.RunCompleted })
    }
}
