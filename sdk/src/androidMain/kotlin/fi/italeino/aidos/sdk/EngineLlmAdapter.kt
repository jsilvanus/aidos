package fi.italeino.aidos.sdk

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.TokenUsage
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.Turn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * LLM ModelAdapter for Aidos Engine (RFC-0103, RFC-0021).
 *
 * Adapts Aidos Engine's OpenAI-compatible `/v1/chat/completions` endpoint
 * to the RFC-0021 ModelAdapter interface. Enables Aidos Agent to treat
 * local inference (via Aidos Engine) the same as remote providers.
 *
 * RFC-0021: "The provider-neutral model interface: adapters for models without
 * native tool-calling implement the same interface using constrained decoding
 * or a documented text protocol."
 */
class EngineLlmAdapter(
    override val modelId: String,
    override val modelVersion: String,
    override val contextWindow: Int,
    private val httpClient: EngineHttpClient,
) : ModelAdapter {
    override val providerId = "aidos-engine"
    override val isLocal = true

    override fun supportsNativeToolCalls(): Boolean = false

    /**
     * Run LLM inference via Aidos Engine's chat completion endpoint.
     *
     * Converts RFC-0021 ModelRequest to OpenAI-compatible format, calls Engine,
     * and converts the response back to ModelResponse.
     *
     * Tool calling via constrained decoding (RFC-0021 M22 pattern) is not yet
     * integrated into Engine's inference path, so tool definitions are passed
     * but not used for grammar compilation. Parsing tools is deferred to future work.
     */
    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        return try {
            // Convert RFC-0021 request to OpenAI format
            val messages = convertMessages(request.messages)
            val tools = if (request.tools.isNotEmpty()) {
                convertToolSchemas(request.tools)
            } else {
                null
            }
            val toolChoice = convertToolChoice(request.toolChoice)

            val chatRequest = ChatCompletionRequest(
                model = modelId,
                messages = messages,
                tools = tools,
                toolChoice = toolChoice,
                maxTokens = request.maxOutputTokens,
            )

            // Call Engine's HTTP endpoint
            val chatResponse = httpClient.chatCompletions(chatRequest).getOrElse { error ->
                return Result.failure(error)
            }

            // Extract the first choice (Engine currently returns single choice per RFC-0103 MVP)
            val choice = chatResponse.choices.firstOrNull()
                ?: return Result.failure(IllegalStateException("No choices in response"))

            // Convert response back to RFC format
            val responseText = choice.message.content
            val toolCalls = extractToolCalls(choice.message.toolCalls ?: emptyList())

            val stopReason = when (choice.finishReason) {
                "tool_calls" -> StopReason.TOOL_USE
                "length" -> StopReason.MAX_TOKENS
                "stop" -> StopReason.END_TURN
                else -> StopReason.END_TURN
            }

            val modelResponse = ModelResponse(
                text = responseText,
                toolCalls = toolCalls,
                stopReason = stopReason,
                usage = TokenUsage(
                    inputTokens = chatResponse.usage.promptTokens,
                    outputTokens = chatResponse.usage.completionTokens,
                ),
                modelId = modelId,
                modelVersion = modelVersion,
            )

            Result.success(modelResponse)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Convert RFC-0021 Turn list to OpenAI-compatible message format.
     */
    private fun convertMessages(turns: List<Turn>): List<ChatMessage> {
        return turns.mapNotNull { turn ->
            when (turn) {
                is Turn.System -> ChatMessage(
                    role = "system",
                    content = turn.content,
                )
                is Turn.User -> ChatMessage(
                    role = "user",
                    content = contentBlocksToText(turn.content),
                )
                is Turn.Assistant -> ChatMessage(
                    role = "assistant",
                    content = turn.text,
                    toolCalls = turn.toolCalls.map { toolCall ->
                        ToolCallJson(
                            id = toolCall.callId,
                            type = "function",
                            function = ToolFunctionJson(
                                name = toolCall.toolName,
                                arguments = toolCall.arguments.toString(),
                            ),
                        )
                    }.ifEmpty { null },
                )
                is Turn.ToolResult -> ChatMessage(
                    role = "user",  // Tool results are treated as user turns in chat format
                    content = "Tool result: " + contentBlocksToText(turn.result.content),
                    toolCallId = turn.result.callId,
                )
            }
        }
    }

    /**
     * Convert content blocks to text (for text-only models).
     */
    private fun contentBlocksToText(blocks: List<ContentBlock>): String {
        return blocks.joinToString(" ") { block ->
            when (block) {
                is ContentBlock.Text -> block.text
                is ContentBlock.Image -> "[Image: ${block.mimeType}]"
                is ContentBlock.ResourceRef -> "[Resource: ${block.nodeId}, ${block.sizeBytes} bytes]"
                else -> "[Unknown content]"
            }
        }
    }

    /**
     * Convert RFC-0021 tool descriptors to OpenAI-compatible tool schemas.
     */
    private fun convertToolSchemas(
        tools: List<dev.aidos.kernel.ToolDescriptor>,
    ): List<ToolSchemaJson> {
        return tools.map { tool ->
            ToolSchemaJson(
                type = "function",
                function = ToolDefinitionJson(
                    name = tool.name,
                    description = tool.description,
                    parameters = tool.inputSchema.toMap(),  // JSON Schema as Map
                ),
            )
        }
    }

    /**
     * Convert RFC-0021 ToolChoice to OpenAI-compatible string format.
     */
    private fun convertToolChoice(choice: ToolChoice): String? {
        return when (choice) {
            is ToolChoice.Auto -> "auto"
            is ToolChoice.None -> "none"
            is ToolChoice.Required -> "required"
            is ToolChoice.Specific -> """{"type": "function", "function": {"name": "${choice.toolName}"}}"""
        }
    }

    /**
     * Extract tool calls from OpenAI response format to RFC-0021 format.
     *
     * TODO(RFC-0103): Implement proper parsing when constrained decoding is integrated.
     * For now, tool calls are parsed from structured response format.
     */
    private fun extractToolCalls(toolCalls: List<ToolCallJson>): List<ToolCall> {
        return toolCalls.map { call ->
            val args = try {
                // Parse the JSON arguments string into a JsonObject
                kotlinx.serialization.json.Json.parseToJsonElement(call.function.arguments) as? JsonObject
                    ?: JsonObject(emptyMap())
            } catch (e: Exception) {
                JsonObject(emptyMap())
            }

            ToolCall(
                callId = call.id,
                toolName = call.function.name,
                arguments = args,
                capabilityId = null,  // Capability resolution happens in agent loop (RFC-0008)
                rawText = call.function.arguments,
            )
        }
    }
}
