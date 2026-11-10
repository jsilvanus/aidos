package fi.italeino.aidos.engine.agent

import dev.aidos.aidin.NodeId
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
import fi.italeino.aidos.engine.http.ChatCompletionRequest
import fi.italeino.aidos.engine.http.ChatCompletionResponse
import fi.italeino.aidos.engine.http.ChatMessage
import fi.italeino.aidos.engine.http.FunctionDefinition
import fi.italeino.aidos.engine.http.ToolDefinition
import fi.italeino.aidos.engine.http.ToolFunctionCall
import fi.italeino.aidos.engine.inference.Locality
import fi.italeino.aidos.engine.inference.Placement
import fi.italeino.aidos.engine.inference.ResolvedCandidateRoute
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import dev.aidos.aidin.LinkMessage
import fi.italeino.aidos.engine.http.ToolCall as WireToolCall

/**
 * The agent loop's model, resolved through the Engine's virtual router (RFC-0105, RFC-0107 phase 1).
 *
 * [ManagedModelAdapter] can only call a model installed on this Engine. This one takes any id the
 * router understands, such as `aidos:chat`, `peer:<node>/<model>` or `local:<model>`, so the loop
 * can run here while its model runs on a trusted peer (a phone using the desktop's model), with
 * the same locality filtering and fallback as the Engine's own `/v1/chat/completions`.
 *
 * Rules, matching the HTTP path:
 * - [Locality] is applied by the router before any peer is contacted; a route the caller's limit
 *   forbids is never tried, and a wider placement than allowed is never used as a fallback.
 * - Routes are tried in order. A failed peer route is quarantined and the next one is tried; a
 *   failed local model is not retried elsewhere silently (it surfaces).
 * - Split-anchor routes are not supported here yet (text only, no tool calls) and are skipped.
 *
 * The three dependencies are plain functions so the adapter has no hard link to Android or to the
 * Aidin node and can be tested with fakes.
 */
class RoutedModelAdapter(
    /** Usually `VirtualModelRouter::resolveRoutes`. */
    private val resolveRoutes: suspend (modelId: String, locality: Locality) -> List<ResolvedCandidateRoute>,
    /** Runs one call on a model of this Engine (admission and locality of RFC-0104 live behind it). */
    private val callLocal: suspend (modelId: String, locality: Locality, request: ModelRequest) -> Result<ModelResponse>,
    /** Usually `AidinNode::delegate`, wrapped to a [PeerCall]. */
    private val callPeer: suspend (peer: NodeId, endpoint: String, bodyJson: String, locality: Locality, planVersion: Long?) -> LinkMessage.DelegateResponse,
    private val quarantine: (key: String) -> Unit = {},
    override val modelId: String,
    private val locality: Locality,
    override val contextWindow: Int = 8192,
    private val onPlacementObserved: ((Placement) -> Unit)? = null,
) : ModelAdapter {
    override val providerId: String = "aidos-engine-routed"
    override val modelVersion: String = "1.0"
    override val isLocal: Boolean = false

    override fun supportsNativeToolCalls(): Boolean = true

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun invoke(request: ModelRequest): Result<ModelResponse> {
        val routes = try {
            resolveRoutes(modelId, locality)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return Result.failure(e)
        }
        var lastFailure: Throwable? = null
        for (route in routes) {
            val outcome = when (route) {
                is ResolvedCandidateRoute.LocalModel -> callLocal(route.modelId, locality, request)
                is ResolvedCandidateRoute.PeerDelegation -> runCatching { delegate(route, request) }
                // Split anchors answer text only; an agent turn needs tool calls.
                is ResolvedCandidateRoute.LocalSplitAnchor -> continue
            }
            if (outcome.isSuccess) {
                onPlacementObserved?.invoke(route.placement)
                return outcome
            }
            val failure = outcome.exceptionOrNull()!!
            if (failure is CancellationException) throw failure
            lastFailure = failure
            // A local model that failed is the caller's answer. A peer that failed is only a candidate.
            if (route !is ResolvedCandidateRoute.PeerDelegation) break
            quarantine("peer:${route.peerNodeId.value}/${route.modelId}")
        }
        return Result.failure(lastFailure ?: IllegalStateException("no usable route for $modelId"))
    }

    private suspend fun delegate(route: ResolvedCandidateRoute.PeerDelegation, request: ModelRequest): ModelResponse {
        val body = json.encodeToString(toWire(route.modelId, request))
        val resp = callPeer(route.peerNodeId, "/aidin/v1/chat/completions", body, locality, route.planVersion)
        // The status only: a peer's error body can echo the prompt, and this message is logged.
        if (resp.status != 200) throw PeerModelException(route.peerNodeId.value, resp.status)
        return fromWire(json.decodeFromString<ChatCompletionResponse>(resp.bodyJson), route.modelId)
    }

    private fun toWire(model: String, request: ModelRequest): ChatCompletionRequest {
        val messages = request.messages.map { turn ->
            when (turn) {
                is Turn.System -> ChatMessage(role = "system", content = turn.content)
                is Turn.User -> ChatMessage(
                    role = "user",
                    content = turn.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text },
                )
                is Turn.Assistant -> ChatMessage(
                    role = "assistant",
                    content = turn.text ?: "",
                    tool_calls = turn.toolCalls.takeIf { it.isNotEmpty() }?.map {
                        WireToolCall(it.callId, function = ToolFunctionCall(it.toolName, it.arguments.toString()))
                    },
                )
                is Turn.ToolResult -> ChatMessage(
                    role = "tool",
                    tool_call_id = turn.result.callId,
                    content = turn.result.content.filterIsInstance<ContentBlock.Text>().joinToString("\n") { it.text },
                )
            }
        }
        return ChatCompletionRequest(
            model = model,
            messages = messages,
            max_tokens = request.maxOutputTokens,
            stream = false,
            tools = request.tools.takeIf { it.isNotEmpty() }?.map {
                ToolDefinition(function = FunctionDefinition(it.name, it.description, it.inputSchema))
            },
            tool_choice = when (request.toolChoice) {
                ToolChoice.None -> "none"
                ToolChoice.Required -> "required"
                // The OpenAI wire form here has no per-tool choice; auto is the closest safe value.
                else -> "auto"
            },
        )
    }

    private fun fromWire(resp: ChatCompletionResponse, servedModel: String): ModelResponse {
        val message = resp.choices.firstOrNull()?.message
        val outputs = mutableListOf<ModelOutput>()
        message?.content?.takeIf { it.isNotBlank() }?.let { outputs += TextOutput(it) }
        message?.tool_calls.orEmpty().forEach { tc ->
            val args = runCatching { json.parseToJsonElement(tc.function.arguments) as? JsonObject }.getOrNull() ?: JsonObject(emptyMap())
            outputs += ToolCallOutput(ToolCall(tc.id.ifBlank { UUID.randomUUID().toString() }, tc.function.name, args, null, tc.function.arguments))
        }
        val stop = when (resp.choices.firstOrNull()?.finish_reason) {
            "tool_calls" -> StopReason.TOOL_USE
            "length" -> StopReason.MAX_TOKENS
            else -> if (outputs.any { it is ToolCallOutput }) StopReason.TOOL_USE else StopReason.END_TURN
        }
        return ModelResponse(
            outputs = outputs,
            stopReason = stop,
            usage = Usage(resp.usage.prompt_tokens, resp.usage.completion_tokens, resp.usage.total_tokens),
            model = ModelRef(servedModel, "peer"),
        )
    }
}

/** A trusted peer answered a delegated model call with an error. Carries no content. */
class PeerModelException(val peer: String, val status: Int) : Exception("peer $peer answered model call with status $status")
