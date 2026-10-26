package dev.aidos.providers

import dev.aidos.amp.DefaultJavaHttpTransport
import dev.aidos.amp.HttpTransport
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelOutput
import dev.aidos.kernel.ModelRef
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallOutput
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.Turn
import dev.aidos.kernel.Usage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * [ModelAdapter] for any OpenAI-compatible Chat Completions API (Ollama, vLLM, LocalAI, LM Studio),
 * served by a configured provider (RFC-0021). [isLocal] is decided by the backend that creates the
 * adapter, never defaulted here: the old `OpenAiHttpModelAdapter` hard-coded `true` for every URL,
 * which made "local" a label rather than a fact (RFC-0104).
 */
class OpenAiCompatibleModelAdapter(
    val baseUrl: String,
    /** The model's name at the provider (what goes in the request's `model` field). */
    override val modelId: String,
    override val providerId: String,
    override val isLocal: Boolean,
    val apiKey: String = "",
    override val contextWindow: Int = 4096,
    private val transport: HttpTransport = DefaultJavaHttpTransport(),
) : ModelAdapter {

    override val modelVersion: String = "1.0"

    override fun supportsNativeToolCalls(): Boolean = true

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = runCatching {
        val endpointUrl = "${baseUrl.trim().removeSuffix("/")}/v1/chat/completions"

        // 1. Serialize messages
        val messagesJson = buildJsonArray {
            for (turn in request.messages) {
                when (turn) {
                    is Turn.System -> add(buildJsonObject {
                        put("role", "system")
                        put("content", turn.content)
                    })
                    is Turn.User -> add(buildJsonObject {
                        put("role", "user")
                        val text = turn.content.filterIsInstance<ContentBlock.Text>()
                            .joinToString("\n") { it.text }
                        put("content", text)
                    })
                    is Turn.Assistant -> add(buildJsonObject {
                        put("role", "assistant")
                        put("content", turn.text ?: "")
                        if (turn.toolCalls.isNotEmpty()) {
                            put("tool_calls", buildJsonArray {
                                for (tc in turn.toolCalls) {
                                    add(buildJsonObject {
                                        put("id", tc.callId)
                                        put("type", "function")
                                        put("function", buildJsonObject {
                                            put("name", tc.toolName)
                                            put("arguments", tc.arguments.toString())
                                        })
                                    })
                                }
                            })
                        }
                    })
                    is Turn.ToolResult -> add(buildJsonObject {
                        put("role", "tool")
                        put("tool_call_id", turn.result.callId)
                        val text = turn.result.content.filterIsInstance<ContentBlock.Text>()
                            .joinToString("\n") { it.text }
                        put("content", text)
                    })
                }
            }
        }

        // 2. Serialize tools if present
        val toolsJson = if (request.tools.isNotEmpty()) {
            buildJsonArray {
                for (tool in request.tools) {
                    add(buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", tool.name)
                            put("description", tool.description)
                            put("parameters", tool.inputSchema)
                        })
                    })
                }
            }
        } else null

        val requestPayload = buildJsonObject {
            put("model", modelId)
            put("messages", messagesJson)
            put("stream", false)
            put("max_tokens", request.maxOutputTokens)
            if (toolsJson != null) {
                put("tools", toolsJson)
                when (request.toolChoice) {
                    ToolChoice.Auto -> put("tool_choice", "auto")
                    ToolChoice.None -> put("tool_choice", "none")
                    ToolChoice.Required -> put("tool_choice", "required")
                    is ToolChoice.Specific -> put("tool_choice", buildJsonObject {
                        put("type", "function")
                        put("function", buildJsonObject {
                            put("name", (request.toolChoice as ToolChoice.Specific).toolName)
                        })
                    })
                }
            }
        }

        val headers = mutableMapOf<String, String>()
        if (apiKey.isNotBlank()) {
            headers["Authorization"] = "Bearer $apiKey"
        }

        val rawResponse = transport.postJson(endpointUrl, headers, requestPayload.toString())
        parseOpenAiResponse(rawResponse)
    }

    private fun parseOpenAiResponse(rawJson: String): ModelResponse {
        val root = json.parseToJsonElement(rawJson).jsonObject
        val choices = root["choices"]?.jsonArray
        val firstChoice = choices?.firstOrNull()?.jsonObject

        val message = firstChoice?.get("message")?.jsonObject
        val rawContent = message?.get("content")?.let {
            if (it is JsonPrimitive && it.isString) it.content else null
        }
        val finishReason = firstChoice?.get("finish_reason")?.let {
            if (it is JsonPrimitive) it.content else null
        }

        val outputs = mutableListOf<ModelOutput>()
        if (!rawContent.isNullOrBlank()) {
            outputs.add(TextOutput(rawContent))
        }

        val toolCallsJson = message?.get("tool_calls")?.let { if (it is JsonArray) it else null }
        if (toolCallsJson != null) {
            for (tcElem in toolCallsJson) {
                val tcObj = tcElem.jsonObject
                val id = tcObj["id"]?.jsonPrimitive?.content ?: UUID.randomUUID().toString()
                val fn = tcObj["function"]?.jsonObject
                val fnName = fn?.get("name")?.jsonPrimitive?.content ?: "unknown"
                val fnArgsRaw = fn?.get("arguments")?.jsonPrimitive?.content ?: "{}"

                val parsedArgs: JsonObject = try {
                    json.parseToJsonElement(fnArgsRaw).jsonObject
                } catch (_: Exception) {
                    JsonObject(emptyMap())
                }

                outputs.add(
                    ToolCallOutput(
                        call = ToolCall(
                            callId = id,
                            toolName = fnName,
                            arguments = parsedArgs,
                            capabilityId = null,
                            rawText = fnArgsRaw,
                        )
                    )
                )
            }
        }

        val stopReason = when (finishReason) {
            "tool_calls" -> StopReason.TOOL_USE
            "length" -> StopReason.MAX_TOKENS
            "stop" -> StopReason.END_TURN
            else -> if (outputs.any { it is ToolCallOutput }) StopReason.TOOL_USE else StopReason.END_TURN
        }

        val usageObj = root["usage"]?.jsonObject
        val promptTokens = usageObj?.get("prompt_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0
        val completionTokens = usageObj?.get("completion_tokens")?.jsonPrimitive?.content?.toIntOrNull() ?: 0

        return ModelResponse(
            outputs = outputs,
            stopReason = stopReason,
            usage = Usage(
                inputTokens = promptTokens,
                outputTokens = completionTokens,
                totalTokens = promptTokens + completionTokens,
            ),
            model = ModelRef(id = modelId, version = modelVersion),
        )
    }
}
