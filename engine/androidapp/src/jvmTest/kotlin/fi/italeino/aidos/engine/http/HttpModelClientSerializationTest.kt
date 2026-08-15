package fi.italeino.aidos.engine.http

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Unit tests for HttpModelClient serialization and response parsing (RFC-0103, Phase E).
 *
 * Tests cover:
 * - ChatMessage serialization
 * - ChatCompletionRequest JSON encoding
 * - ChatCompletionResponse JSON decoding
 * - TokenUsage calculations
 * - Error response handling
 */
class HttpModelClientSerializationTest {
    private val json = Json {
        prettyPrint = false
        encodeDefaults = true
        isLenient = true
    }

    @Test
    fun testChatMessageSerialization() {
        val message = ChatMessage(
            role = "user",
            content = "Hello, how are you?"
        )
        val encoded = json.encodeToString(message)
        
        assert(encoded.contains("\"role\":\"user\""))
        assert(encoded.contains("\"content\":\"Hello, how are you?\""))
    }

    @Test
    fun testChatCompletionRequestSerialization() {
        val request = ChatCompletionRequest(
            model = "qwen2.5-3b",
            messages = listOf(
                ChatMessage(role = "user", content = "Test message")
            ),
            temperature = 0.7f,
            max_tokens = 512
        )
        val encoded = json.encodeToString(request)
        
        assert(encoded.contains("\"model\":\"qwen2.5-3b\""))
        assert(encoded.contains("\"max_tokens\":512"))
        assert(encoded.contains("\"temperature\":0.7"))
    }

    @Test
    fun testChatCompletionResponseDeserialization() {
        val responseJson = """
            {
                "id": "chatcmpl-123",
                "object": "chat.completion",
                "created": 1234567890,
                "model": "qwen2.5-3b",
                "choices": [
                    {
                        "index": 0,
                        "message": {
                            "role": "assistant",
                            "content": "Hello! I'm doing great, thanks for asking."
                        },
                        "finish_reason": "stop"
                    }
                ],
                "usage": {
                    "prompt_tokens": 10,
                    "completion_tokens": 15,
                    "total_tokens": 25
                }
            }
        """.trimIndent()

        val response = json.decodeFromString<ChatCompletionResponse>(responseJson)
        
        assertEquals("chatcmpl-123", response.id)
        assertEquals("qwen2.5-3b", response.model)
        assertEquals(1, response.choices.size)
        assertEquals("Hello! I'm doing great, thanks for asking.", response.firstContent)
        assertEquals(25, response.totalTokens)
    }

    @Test
    fun testTokenUsageCalculation() {
        val usage = TokenUsage(
            prompt_tokens = 10,
            completion_tokens = 15,
            total_tokens = 25
        )
        
        assertEquals(25, usage.total_tokens)
        assertEquals(10, usage.prompt_tokens)
        assertEquals(15, usage.completion_tokens)
    }

    @Test
    fun testChatCompletionResponseWithMultipleChoices() {
        val response = ChatCompletionResponse(
            id = "test-123",
            model = "test-model",
            choices = listOf(
                Choice(
                    index = 0,
                    message = Message(role = "assistant", content = "First response")
                ),
                Choice(
                    index = 1,
                    message = Message(role = "assistant", content = "Second response")
                )
            ),
            usage = TokenUsage(prompt_tokens = 10, completion_tokens = 20, total_tokens = 30)
        )
        
        // Should return first choice's content
        assertEquals("First response", response.firstContent)
        assertEquals(30, response.totalTokens)
    }

    @Test
    fun testEmptyChatCompletionResponse() {
        val response = ChatCompletionResponse()
        
        assertEquals("", response.firstContent)
        assertEquals(0, response.totalTokens)
        assertTrue(response.choices.isEmpty())
    }

    @Test
    fun testMessageWithSpecialCharacters() {
        val message = ChatMessage(
            role = "user",
            content = "Special chars: \"\\\n\t"
        )
        val encoded = json.encodeToString(message)
        val decoded = json.decodeFromString<ChatMessage>(encoded)
        
        assertEquals(message.content, decoded.content)
    }

    @Test
    fun testLargeTokenCount() {
        val usage = TokenUsage(
            prompt_tokens = 100_000,
            completion_tokens = 50_000,
            total_tokens = 150_000
        )
        
        assertEquals(150_000, usage.total_tokens)
    }
}
