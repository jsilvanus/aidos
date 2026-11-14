package dev.aidos.vault

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AnthropicAdapterTest {

    private val okResponseJson = """
        {
          "id": "msg_test_1",
          "type": "message",
          "role": "assistant",
          "content": [{"type": "text", "text": "Done."}],
          "stop_reason": "end_turn",
          "usage": {"input_tokens": 12, "output_tokens": 4}
        }
    """.trimIndent()

    @Test
    fun `assistant turn with both text and tool calls preserves both text and tool_use blocks`() = runTest {
        var capturedBody: String? = null
        val adapter = AnthropicAdapter(
            apiKeyChars = "sk-ant-test-key".toCharArray(),
            transport = AnthropicTransport { _, _, bodyJson ->
                capturedBody = bodyJson
                okResponseJson
            },
        )

        val request = ModelRequest(
            messages = listOf(
                Turn.User(listOf(ContentBlock.Text("Check foo.kt")), TrustLevel.TRUSTED),
                Turn.Assistant(
                    text = "I will inspect foo.kt now.",
                    toolCalls = listOf(
                        ToolCall(
                            callId = "call_1",
                            toolName = "fs_read",
                            arguments = buildJsonObject { put("path", "foo.kt") },
                            capabilityId = null,
                        ),
                        ToolCall(
                            callId = "call_2",
                            toolName = "fs_stat",
                            arguments = buildJsonObject { put("path", "foo.kt") },
                            capabilityId = null,
                        ),
                    ),
                ),
                Turn.ToolResult(
                    ToolCallResult(
                        callId = "call_1",
                        outcome = ToolOutcome.Ok,
                        content = listOf(ContentBlock.Text("package demo")),
                        trustLevel = TrustLevel.PROJECT,
                    )
                ),
            ),
            tools = emptyList(),
            toolChoice = ToolChoice.Auto,
            maxOutputTokens = 256,
        )

        val result = adapter.invoke(request)
        adapter.close()
        assertTrue(result.isSuccess)

        val body = Json.parseToJsonElement(assertNotNull(capturedBody)).jsonObject
        val messages = body["messages"]!!.jsonArray
        assertEquals(3, messages.size)

        val assistantMsg = messages[1].jsonObject
        assertEquals("assistant", assistantMsg["role"]!!.jsonPrimitive.content)

        val content = assistantMsg["content"]!!.jsonArray
        assertEquals(3, content.size)

        val textBlock = content[0].jsonObject
        assertEquals("text", textBlock["type"]!!.jsonPrimitive.content)
        assertEquals("I will inspect foo.kt now.", textBlock["text"]!!.jsonPrimitive.content)

        val toolBlock1 = content[1].jsonObject
        assertEquals("tool_use", toolBlock1["type"]!!.jsonPrimitive.content)
        assertEquals("call_1", toolBlock1["id"]!!.jsonPrimitive.content)
        assertEquals("fs_read", toolBlock1["name"]!!.jsonPrimitive.content)
        assertEquals("foo.kt", toolBlock1["input"]!!.jsonObject["path"]!!.jsonPrimitive.content)

        val toolBlock2 = content[2].jsonObject
        assertEquals("tool_use", toolBlock2["type"]!!.jsonPrimitive.content)
        assertEquals("call_2", toolBlock2["id"]!!.jsonPrimitive.content)
        assertEquals("fs_stat", toolBlock2["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun `assistant turn handles text-only, toolCalls-only, and empty-text with toolCalls`() = runTest {
        var capturedBody: String? = null
        val adapter = AnthropicAdapter(
            apiKeyChars = "sk-ant-test-key".toCharArray(),
            transport = AnthropicTransport { _, _, bodyJson ->
                capturedBody = bodyJson
                okResponseJson
            },
        )

        val request = ModelRequest(
            messages = listOf(
                Turn.Assistant(text = "Plain reply", toolCalls = emptyList()),
                Turn.Assistant(
                    text = null,
                    toolCalls = listOf(
                        ToolCall("call_9", "git_status", buildJsonObject {}, null),
                    ),
                ),
                Turn.Assistant(
                    text = "",
                    toolCalls = listOf(
                        ToolCall("call_10", "git_diff", buildJsonObject {}, null),
                    ),
                ),
            ),
            tools = emptyList(),
            toolChoice = ToolChoice.Auto,
            maxOutputTokens = 128,
        )

        val result = adapter.invoke(request)
        adapter.close()
        assertTrue(result.isSuccess)

        val messages = Json.parseToJsonElement(assertNotNull(capturedBody)).jsonObject["messages"]!!.jsonArray
        assertEquals(3, messages.size)

        val textOnly = messages[0].jsonObject["content"]!!.jsonArray
        assertEquals(1, textOnly.size)
        assertEquals("text", textOnly[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("Plain reply", textOnly[0].jsonObject["text"]!!.jsonPrimitive.content)

        val toolsOnlyNullText = messages[1].jsonObject["content"]!!.jsonArray
        assertEquals(1, toolsOnlyNullText.size)
        assertEquals("tool_use", toolsOnlyNullText[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_9", toolsOnlyNullText[0].jsonObject["id"]!!.jsonPrimitive.content)

        val toolsOnlyEmptyText = messages[2].jsonObject["content"]!!.jsonArray
        assertEquals(1, toolsOnlyEmptyText.size)
        assertEquals("tool_use", toolsOnlyEmptyText[0].jsonObject["type"]!!.jsonPrimitive.content)
        assertEquals("call_10", toolsOnlyEmptyText[0].jsonObject["id"]!!.jsonPrimitive.content)
    }
}
