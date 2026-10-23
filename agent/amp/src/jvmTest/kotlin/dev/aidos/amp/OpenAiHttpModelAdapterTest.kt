package dev.aidos.amp

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiHttpModelAdapterTest {

    @Test
    fun testSimpleChatCompletion() = runBlocking {
        val mockResponse = """
            {
              "id": "chatcmpl-123",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": "Hello from local model!"
                  },
                  "finish_reason": "stop"
                }
              ],
              "usage": {
                "prompt_tokens": 10,
                "completion_tokens": 5
              }
            }
        """.trimIndent()

        val fakeTransport = HttpTransport { _, _, _ -> mockResponse }
        val adapter = OpenAiHttpModelAdapter(
            baseUrl = "http://localhost:11434",
            modelId = "llama3",
            transport = fakeTransport,
        )

        val request = ModelRequest(
            messages = listOf(
                Turn.System("You are a helpful assistant"),
                Turn.User(listOf(ContentBlock.Text("Hi")), TrustLevel.UNTRUSTED)
            ),
            tools = emptyList(),
            toolChoice = ToolChoice.None,
            maxOutputTokens = 100,
        )

        val result = adapter.invoke(request)
        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(StopReason.END_TURN, response.stopReason)
        assertEquals(1, response.outputs.size)
        val text = response.outputs.first() as TextOutput
        assertEquals("Hello from local model!", text.text)
    }

    @Test
    fun testToolCallResponse() = runBlocking {
        val mockResponse = """
            {
              "id": "chatcmpl-456",
              "choices": [
                {
                  "index": 0,
                  "message": {
                    "role": "assistant",
                    "content": null,
                    "tool_calls": [
                      {
                        "id": "call_abc123",
                        "type": "function",
                        "function": {
                          "name": "check_calendar",
                          "arguments": "{\"date\":\"2026-10-23\",\"time\":\"17:00\"}"
                        }
                      }
                    ]
                  },
                  "finish_reason": "tool_calls"
                }
              ]
            }
        """.trimIndent()

        val fakeTransport = HttpTransport { _, _, _ -> mockResponse }
        val adapter = OpenAiHttpModelAdapter(
            baseUrl = "http://localhost:11434",
            modelId = "llama3",
            transport = fakeTransport,
        )

        val dummyTool = AmpToolDefinition(
            name = "check_calendar",
            description = "Check calendar availability",
            inputSchema = buildJsonObject { put("type", "object") },
            handler = { AmpToolExecutionResult("available") }
        )

        val request = ModelRequest(
            messages = listOf(Turn.User(listOf(ContentBlock.Text("Am I free at 5?")), TrustLevel.UNTRUSTED)),
            tools = listOf(dummyTool.toToolDescriptor()),
            toolChoice = ToolChoice.Auto,
            maxOutputTokens = 100,
        )

        val result = adapter.invoke(request)
        assertTrue(result.isSuccess)
        val response = result.getOrThrow()
        assertEquals(StopReason.TOOL_USE, response.stopReason)
        val toolOutput = response.outputs.filterIsInstance<ToolCallOutput>().first()
        assertEquals("check_calendar", toolOutput.call.toolName)
        assertEquals("call_abc123", toolOutput.call.callId)
    }
}
