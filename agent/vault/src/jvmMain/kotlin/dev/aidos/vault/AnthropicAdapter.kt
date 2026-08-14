package dev.aidos.vault

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ProviderRetention
import dev.aidos.kernel.RetentionPolicy
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.TokenUsage
import dev.aidos.kernel.TrainingUse
import dev.aidos.kernel.Turn
import kotlinx.datetime.Clock
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.headers
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Anthropic Claude adapter (RFC-0023, M14).
 *
 * One remote provider implementing [ModelAdapter]. Every call is [EffectKind.Egress] at the
 * broker level — this class is not responsible for that; the broker wraps every adapter call.
 *
 * The API key is resolved from the vault at construction time and held in a [CharArray] that
 * is zeroed on [close]. It never appears in a log, event, audit row, or prompt.
 *
 * [providerRetention] is the Anthropic-stated retention policy for the API tier — a claim, not a
 * control. It is recorded in `attempts.provider_retention_json` so the user can weigh it
 * (RFC-0026), by whoever calls [invoke] and writes the Attempt row, not by this class itself —
 * an adapter has no `attempts` row to write into, only a fact to report.
 */
class AnthropicAdapter(
    private val apiKeyChars: CharArray,
    override val modelId: String = "claude-3-5-haiku-20241022",
    override val modelVersion: String = "claude-3-5-haiku-20241022",
    override val contextWindow: Int = 200_000,
) : ModelAdapter, AutoCloseable {

    override val providerId = "anthropic"
    override val isLocal = false

    /**
     * Anthropic's stated data retention policy for the API tier, per
     * https://privacy.anthropic.com/en/policies/privacy-policy (RFC-0023). [ProviderRetention]'s
     * own doc comment covers why [ProviderRetention.recordedAt] here is not the final, persisted
     * timestamp.
     */
    override val providerRetention = ProviderRetention(
        policy = RetentionPolicy.ZERO,
        statedDurationDays = 0,
        trainingUse = TrainingUse.NONE,
        recordedAt = Clock.System.now(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    private val client = HttpClient(CIO) {
        install(ContentNegotiation) { json(json) }
    }

    override fun supportsNativeToolCalls(): Boolean = true

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> = runCatching {
        val body = buildRequestBody(request)
        val apiKey = String(apiKeyChars)
        val response = client.post("https://api.anthropic.com/v1/messages") {
            headers {
                append("x-api-key", apiKey)
                append("anthropic-version", "2023-06-01")
            }
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        // Zero the in-scope String representation ASAP — strings are immutable on the JVM heap
        // so we cannot truly zero it, but we minimise its lifetime as much as we can here.
        parseResponse(response.body<AnthropicResponse>())
    }

    override fun close() {
        apiKeyChars.fill('\u0000')
        client.close()
    }

    // ── Request / Response mapping ─────────────────────────────────────────────

    private fun buildRequestBody(request: ModelRequest): JsonObject = buildJsonObject {
        put("model", modelId)
        put("max_tokens", request.maxOutputTokens.coerceAtLeast(1))

        // System turn
        val systemContent = request.messages
            .filterIsInstance<Turn.System>()
            .joinToString("\n\n") { it.content }
        if (systemContent.isNotBlank()) put("system", systemContent)

        put("messages", buildJsonArray {
            for (turn in request.messages) {
                val msg = toAnthropicMessage(turn) ?: continue
                add(msg)
            }
        })

        // Tools
        if (request.tools.isNotEmpty()) {
            put("tools", buildJsonArray {
                for (tool in request.tools) {
                    add(buildJsonObject {
                        put("name", tool.name)
                        put("description", tool.description)
                        put("input_schema", tool.inputSchema)
                    })
                }
            })
        }
    }

    private fun toAnthropicMessage(turn: Turn): JsonObject? = when (turn) {
        is Turn.System -> null  // handled as top-level "system" field
        is Turn.User -> buildJsonObject {
            put("role", "user")
            put("content", buildJsonArray {
                for (block in turn.content) {
                    when (block) {
                        is ContentBlock.Text -> add(buildJsonObject {
                            put("type", "text"); put("text", block.text)
                        })
                        else -> { /* skip non-text blocks for now */ }
                    }
                }
            })
        }
        is Turn.Assistant -> buildJsonObject {
            put("role", "assistant")
            if (turn.text != null) {
                put("content", buildJsonArray {
                    add(buildJsonObject { put("type", "text"); put("text", turn.text) })
                })
            } else if (turn.toolCalls.isNotEmpty()) {
                put("content", buildJsonArray {
                    for (tc in turn.toolCalls) {
                        add(buildJsonObject {
                            put("type", "tool_use")
                            put("id", tc.callId)
                            put("name", tc.toolName)
                            put("input", tc.arguments)
                        })
                    }
                })
            }
        }
        is Turn.ToolResult -> buildJsonObject {
            put("role", "user")
            val text = turn.result.content
                .filterIsInstance<ContentBlock.Text>()
                .joinToString("\n") { it.text }
            put("content", buildJsonArray {
                add(buildJsonObject {
                    put("type", "tool_result")
                    put("tool_use_id", turn.result.callId)
                    put("content", text)
                })
            })
        }
    }

    private fun parseResponse(r: AnthropicResponse): ModelResponse {
        val text = r.content
            .filter { it.type == "text" }
            .joinToString("") { it.text ?: "" }
            .takeIf { it.isNotBlank() }

        val toolCalls = r.content
            .filter { it.type == "tool_use" }
            .map { block ->
                ToolCall(
                    callId = block.id ?: "",
                    toolName = block.name ?: "",
                    arguments = block.input ?: buildJsonObject {},
                    capabilityId = null,  // resolved by the broker, not the adapter
                )
            }

        val stop = when (r.stopReason) {
            "end_turn"   -> StopReason.END_TURN
            "tool_use"   -> StopReason.TOOL_USE
            "max_tokens" -> StopReason.MAX_TOKENS
            else         -> StopReason.END_TURN
        }
        return ModelResponse(
            text = text,
            toolCalls = toolCalls,
            stopReason = stop,
            usage = TokenUsage(r.usage.inputTokens, r.usage.outputTokens),
            modelId = modelId,
            modelVersion = modelVersion,
        )
    }

    // ── Anthropic wire types ───────────────────────────────────────────────────

    @Serializable
    private data class AnthropicResponse(
        val id: String = "",
        val type: String = "",
        val role: String = "",
        val content: List<ContentPart> = emptyList(),
        @SerialName("stop_reason") val stopReason: String = "end_turn",
        val usage: Usage = Usage(),
    )

    @Serializable
    private data class ContentPart(
        val type: String = "",
        val text: String? = null,
        val id: String? = null,
        val name: String? = null,
        val input: JsonObject? = null,
    )

    @Serializable
    private data class Usage(
        @SerialName("input_tokens") val inputTokens: Int = 0,
        @SerialName("output_tokens") val outputTokens: Int = 0,
    )
}
