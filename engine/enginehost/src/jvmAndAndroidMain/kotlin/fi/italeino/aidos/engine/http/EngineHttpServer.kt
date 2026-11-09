package fi.italeino.aidos.engine.http

import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.LinkMessage
import dev.aidos.aidin.NodeId
import dev.aidos.aidin.RouteInfo
import dev.aidos.aidin.RouteQueryRequest
import dev.aidos.aidin.SplitInferenceOutcome
import dev.aidos.aidin.TrustDomain
import dev.aidos.decisions.DecisionModelAdapter
import dev.aidos.decisions.DecisionRequestException
import dev.aidos.decisions.DecisionWire
import dev.aidos.stt.SttModelAdapter
import dev.aidos.stt.SttRequest
import dev.aidos.stt.SttRequestException
import dev.aidos.kernel.*
import dev.aidos.kernel.ToolCall as KernelToolCall
import fi.italeino.aidos.engine.handshake.HandshakeWire
import fi.italeino.aidos.engine.inference.EngineBusyException
import fi.italeino.aidos.engine.inference.EngineModelBusyException
import fi.italeino.aidos.engine.inference.EngineShuttingDownException
import fi.italeino.aidos.engine.inference.HOP_HEADER
import fi.italeino.aidos.engine.inference.InferenceRequestManager
import fi.italeino.aidos.engine.inference.LOCALITY_HEADER
import fi.italeino.aidos.engine.inference.Locality
import fi.italeino.aidos.engine.inference.LocalityViolationException
import fi.italeino.aidos.engine.inference.ModelUnavailableException
import fi.italeino.aidos.engine.inference.PLACEMENT_HEADER
import fi.italeino.aidos.engine.inference.PLAN_VERSION_HEADER
import fi.italeino.aidos.engine.inference.PeerBusyException
import fi.italeino.aidos.engine.inference.Placement
import fi.italeino.aidos.engine.inference.PlanChangedException
import fi.italeino.aidos.engine.inference.ROUTE_HEADER
import fi.italeino.aidos.engine.inference.ResolvedCandidateRoute
import fi.italeino.aidos.engine.inference.VirtualModelConfig
import fi.italeino.aidos.engine.inference.VirtualModelRouter
import fi.italeino.aidos.engine.inference.placementOf
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.request.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import io.ktor.utils.io.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/** An SDK version is a short build string; anything longer is not one, and is cut before it is stored. */
private const val MAX_SDK_VERSION_LENGTH = 64

/**
 * Request size limits, generation bounds, timeout policy, and safe defaults for the Engine HTTP API
 * boundary (PIPELINE.md §2.7).
 */
data class EngineApiPolicy(
    /** Maximum allowed HTTP request body size in bytes (default 10 MiB). */
    val maxRequestBytes: Long = 10L * 1024 * 1024,
    /** Default `max_tokens` when a chat completion request omits the field. */
    val defaultMaxOutputTokens: Int = 2048,
    /** Hard upper limit on `max_tokens` for a single generation request. */
    val maxOutputTokensLimit: Int = 8192,
    /** Maximum number of messages allowed in a single chat completion request. */
    val maxMessagesCount: Int = 256,
    /** Maximum total character count across all messages in a chat completion request. */
    val maxPromptChars: Int = 256_000,
    /** Maximum number of input strings in a single embeddings request. */
    val maxEmbeddingInputs: Int = 128,
    /** Maximum total character count across all input strings in an embeddings request. */
    val maxEmbeddingInputChars: Int = 64_000,
    /** Maximum decoded audio size in bytes for a transcription request (default 25 MiB). */
    val maxAudioBytes: Int = 25 * 1024 * 1024,
    /** Per-request timeout in milliseconds covering admission, model load, and execution (default 120s). */
    val requestTimeoutMs: Long = 120_000L,
)

class EngineRequestTimeoutException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

private val wireJson = Json {
    prettyPrint = false
    encodeDefaults = true
    isLenient = true
    ignoreUnknownKeys = true
}

class EngineHttpServer(
    private val tokenManager: TokenManager,
    private val modelRuntime: ModelRuntime,
    private val inferenceManager: InferenceRequestManager = InferenceRequestManager(modelRuntime),
    private val port: Int = 0,
    /**
     * Per-request check that the app a token was issued to is still approved (RFC-0103, "Trust
     * model"): revoking or denying an app in Connected Apps takes effect on its next request, not
     * when its token expires. A failed check is a 401, which sends the SDK back through the
     * handshake and so to the app's real state (PENDING / DENIED). Null skips the check; Engine's
     * own in-process token ([TokenManager.ENGINE_SUBJECT]) is never subject to it.
     */
    private val isSubjectApproved: (suspend (subject: String) -> Boolean)? = null,
    /**
     * Engine-wide limit on where inference may run (RFC-0104), read per request so a settings
     * change applies at once. A request's own [LOCALITY_HEADER], this and [subjectLocality]
     * combine to the strictest; nothing a client sends can loosen the other two.
     */
    private val engineLocality: () -> Locality = { Locality.ANY },
    /**
     * What a request that states no [LOCALITY_HEADER] is held to (D47, decided by the owner
     * 2026-10-26): local only. Going wider is something a caller asks for in words, per request.
     */
    private val defaultLocality: () -> Locality = { Locality.LOCAL_ONLY },
    /** Per-app limit (the user's choice in Connected Apps), or null for none. Engine's own subject is exempt. */
    private val subjectLocality: (suspend (subject: String) -> Locality?)? = null,
    /**
     * Extra routes a host mounts inside the authenticated block (same tokens and approval check),
     * e.g. the desktop host's the /v1/agent routes. Additive to API v1; null on hosts without any.
     */
    private val extraRoutes: (Route.() -> Unit)? = null,
    /**
     * Told which SDK build and API version an app's request carries (`X-Aidos-Sdk-Version`,
     * `X-Aidos-Api-Version`), so Connected Apps can show them. Record-only: a request is never
     * refused over its version. Not called for Engine's own subject, or when neither header is sent.
     */
    private val onClientVersions: (suspend (subject: String, sdkVersion: String?, apiVersion: Int?) -> Unit)? = null,
    /** Hardening limits, generation caps, and timeout policy (PIPELINE.md §2.7). */
    val policy: EngineApiPolicy = EngineApiPolicy(),
    /** Optional AIDIN node for peer delegation and inference group routing (RFC-0105). */
    private val aidinNode: () -> AidinNode? = { null },
    /** Optional virtual model configurations (`aidos:chat`, `aidos:embed`, `aidos:stt`). */
    private val virtualModels: () -> List<VirtualModelConfig> = { emptyList() },
    /** True when this Engine is draining or refusing new peer-delegated work (503 `peer_busy`). */
    private val isPeerDraining: () -> Boolean = { false },
    /** Router for virtual model IDs (`aidos:chat`) and locality-filtered peer/group fallback. */
    val virtualModelRouter: VirtualModelRouter = VirtualModelRouter(modelRuntime, aidinNode, virtualModels),
) {
    private var server: EmbeddedServer<*, *>? = null

    init {
        aidinNode()?.let(::attachAidinNode)
    }

    /** Wires [node]'s `delegationHandler` so incoming `/aidin/v1/` requests over `PeerLink` are served by this Engine. */
    fun attachAidinNode(node: AidinNode) {
        if (node.delegationHandler == null) {
            node.delegationHandler = { fromPeer, request, emitStream ->
                handleLinkDelegation(fromPeer, request, emitStream)
            }
        }
    }

    private fun ensureAidinAttached(): AidinNode? {
        val node = aidinNode() ?: return null
        attachAidinNode(node)
        return node
    }

    suspend fun getBoundPort(): Int? = server?.engine?.resolvedConnectors()?.firstOrNull()?.port

    fun start() {
        ensureAidinAttached()
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            installInto(this)
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
    }

    suspend fun shutdownInference(timeoutMs: Long = 5_000L): Boolean =
        inferenceManager.shutdownAndDrain(timeout = timeoutMs.milliseconds)

    suspend fun waitUntilModelIdle(modelId: String, timeoutMs: Long = 5_000L): Boolean =
        inferenceManager.waitUntilModelIdle(modelId = modelId, timeout = timeoutMs.milliseconds)

    /** Force-load a model through the Engine's lifecycle/admission manager without running inference. */
    suspend fun openModel(modelId: String): Result<Unit> =
        inferenceManager.openModel(modelId)

    /** Interrupt active inference, wait for it to stop, then unload the model while keeping it installed. */
    suspend fun closeModel(modelId: String): Result<Unit> =
        inferenceManager.closeModel(modelId)

    /** Close (interrupt + unload) the model before removing its installed artifact. */
    suspend fun deleteModel(modelId: String): Result<Unit> =
        inferenceManager.deleteModel(modelId)

    internal fun installInto(application: Application) {
        with(application) {
            setupContentNegotiation()
            setupAuthentication()
            setupRouting()
        }
    }

    private fun Application.setupContentNegotiation() {
        install(ContentNegotiation) {
            json(wireJson)
        }
    }

    private fun Application.setupAuthentication() {
        install(Authentication) {
            bearer("bearerAuth") {
                authenticate { tokenCredential ->
                    val info = tokenManager.authenticate(tokenCredential.token)
                    when {
                        info == null -> null
                        info.subject != TokenManager.ENGINE_SUBJECT &&
                            isSubjectApproved?.invoke(info.subject) == false -> null
                        else -> {
                            if (info.subject != TokenManager.ENGINE_SUBJECT) reportClientVersions(info.subject)
                            UserIdPrincipal(info.subject)
                        }
                    }
                }
            }
        }
    }

    /** Hands the SDK's version headers to [onClientVersions]; a bad value is dropped, not an error. */
    private suspend fun ApplicationCall.reportClientVersions(subject: String) {
        val callback = onClientVersions ?: return
        val sdk = request.headers[HandshakeWire.HEADER_SDK_VERSION]?.trim()?.take(MAX_SDK_VERSION_LENGTH)?.takeIf { it.isNotEmpty() }
        val api = request.headers[HandshakeWire.HEADER_API_VERSION]?.trim()?.toIntOrNull()
        if (sdk != null || api != null) callback(subject, sdk, api)
    }

    private fun Application.setupRouting() {
        routing {
            get("/health") { call.respond(mapOf("status" to "ok")) }
            authenticate("bearerAuth") {
                get("/v1/models") { handleModels(call) }
                post("/v1/models/{modelId}/open") { handleModelOpen(call) }
                post("/v1/models/{modelId}/close") { handleModelClose(call) }
                delete("/v1/models/{modelId}") { handleModelDelete(call) }
                post("/v1/chat/completions") { handleChatCompletions(call) }
                post("/v1/embeddings") { handleEmbeddings(call) }
                post("/v1/audio/transcriptions") { handleTranscriptions(call) }
                // Typed decisions (RFC-0022 D45). /v1/systemone is the Jev-compatible path; /v1/decisions
                // is OpenJev's alias for the same handler.
                post("/v1/systemone") { handleDecisions(call) }
                post("/v1/decisions") { handleDecisions(call) }

                // RFC-0105 Phase 2 peer delegation endpoints (/aidin/v1/*)
                post("/aidin/v1/route") { handleAidinHttpRoute(call) }
                post("/aidin/v1/chat/completions") { handleAidinHttpDelegation(call, "/aidin/v1/chat/completions") }
                post("/aidin/v1/embeddings") { handleAidinHttpDelegation(call, "/aidin/v1/embeddings") }
                post("/aidin/v1/audio/transcriptions") { handleAidinHttpDelegation(call, "/aidin/v1/audio/transcriptions") }
                post("/aidin/v1/systemone") { handleAidinHttpDelegation(call, "/aidin/v1/systemone") }

                extraRoutes?.invoke(this)
            }
        }
    }

    /**
     * Reads the HTTP request body up to [EngineApiPolicy.maxRequestBytes] and decodes it as [T].
     * Rejects oversized bodies with 413 Payload Too Large and malformed JSON with a sanitized 400
     * Bad Request that never echoes raw payload or prompt text.
     */
    private suspend inline fun <reified T> receiveBoundedJson(call: ApplicationCall): T? {
        val bodyText = receiveBoundedBodyText(call) ?: return null
        return try {
            wireJson.decodeFromString<T>(bodyText)
        } catch (_: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    ErrorDetail(
                        message = "Malformed JSON request body",
                        type = "invalid_request_error",
                        code = "invalid_json"
                    )
                )
            )
            null
        }
    }

    private suspend fun receiveBoundedBodyText(call: ApplicationCall): String? {
        val declaredLength = call.request.contentLength()
        if (declaredLength != null && declaredLength > policy.maxRequestBytes) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse(
                    ErrorDetail(
                        message = "Request body exceeds maximum size limit of ${policy.maxRequestBytes} bytes",
                        type = "invalid_request_error",
                        code = "request_too_large"
                    )
                )
            )
            return null
        }

        val channel = call.receiveChannel()
        val maxBytes = policy.maxRequestBytes
        val out = ByteArrayOutputStream(declaredLength?.coerceIn(0L, 65_536L)?.toInt() ?: 4096)
        val chunk = ByteArray(8192)
        var totalRead = 0L
        while (!channel.isClosedForRead) {
            val read = channel.readAvailable(chunk, 0, chunk.size)
            if (read <= 0) break
            totalRead += read
            if (totalRead > maxBytes) {
                call.respond(
                    HttpStatusCode.PayloadTooLarge,
                    ErrorResponse(
                        ErrorDetail(
                            message = "Request body exceeds maximum size limit of $maxBytes bytes",
                            type = "invalid_request_error",
                            code = "request_too_large"
                        )
                    )
                )
                return null
            }
            out.write(chunk, 0, read)
        }

        val bodyText = out.toString(Charsets.UTF_8.name())
        if (bodyText.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    ErrorDetail(
                        message = "Request body must not be empty",
                        type = "invalid_request_error",
                        code = "invalid_json"
                    )
                )
            )
            return null
        }
        return bodyText
    }

    /**
     * Executes [block] through [inferenceManager] under [EngineApiPolicy.requestTimeoutMs].
     */
    private suspend fun <T> executeManaged(
        modelId: String,
        locality: Locality,
        block: suspend (ModelAdapter, Placement) -> T,
    ): Result<T> {
        val timeoutMs = policy.requestTimeoutMs
        if (timeoutMs <= 0L) {
            return inferenceManager.execute(modelId, locality, block)
        }
        return try {
            val outcome = withTimeout(timeoutMs.milliseconds) {
                inferenceManager.execute(modelId, locality) { adapter, placement ->
                    try {
                        withTimeout(timeoutMs.milliseconds) {
                            block(adapter, placement)
                        }
                    } catch (e: TimeoutCancellationException) {
                        if (adapter is CancellableModelAdapter) adapter.cancelCurrentInference()
                        throw EngineRequestTimeoutException("Inference request timed out after ${timeoutMs}ms", e)
                    }
                }
            }
            val ex = outcome.exceptionOrNull()
            if (ex is TimeoutCancellationException) {
                Result.failure(EngineRequestTimeoutException("Inference request timed out after ${timeoutMs}ms", ex))
            } else {
                outcome
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(EngineRequestTimeoutException("Inference request timed out after ${timeoutMs}ms", e))
        }
    }

    /**
     * The [Locality] this request runs under (RFC-0104): the strictest of the client's
     * [LOCALITY_HEADER] (or [defaultLocality] when it is absent), the app's limit and Engine's. An unreadable header is a 400 (null result),
     * never a silent "any": a client that asked for a limit must not lose it to a typo.
     */
    private suspend fun resolveLocality(call: ApplicationCall): Locality? {
        val requested = call.request.headers[LOCALITY_HEADER]?.let { raw ->
            Locality.parse(raw) ?: run {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("$LOCALITY_HEADER must be local_only, trusted_cluster or any", "invalid_request_error", param = "locality", code = "invalid_locality"))
                )
                return null
            }
        }
        return effectiveLocality(call.principal<UserIdPrincipal>()?.name, requested)
    }

    /**
     * The [Locality] a request is held to: the strictest of what it [requested] (null: [defaultLocality]),
     * its app's limit and Engine's. Public so routes mounted through [extraRoutes] (the agent
     * runtime) apply the same layering instead of inventing their own.
     */
    suspend fun effectiveLocality(subject: String?, requested: Locality?): Locality {
        val forSubject = if (subject == null || subject == TokenManager.ENGINE_SUBJECT) null else subjectLocality?.invoke(subject)
        return listOfNotNull(requested ?: defaultLocality(), forSubject, engineLocality()).reduce(Locality::strictest)
    }

    private suspend fun handleModelOpen(call: ApplicationCall) {
        val modelId = call.parameters["modelId"]?.trim().orEmpty()
        if (modelId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail("modelId is required", "invalid_request_error", param = "modelId", code = "invalid_request")))
            return
        }
        val result = openModel(modelId)
        if (result.isFailure) {
            respondClassifiedError(call, result.exceptionOrNull())
            return
        }
        call.respond(HttpStatusCode.OK, mapOf("id" to modelId, "status" to "loaded"))
    }

    private suspend fun handleModelClose(call: ApplicationCall) {
        val modelId = call.parameters["modelId"]?.trim().orEmpty()
        if (modelId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail("modelId is required", "invalid_request_error", param = "modelId", code = "invalid_request")))
            return
        }
        val result = closeModel(modelId)
        if (result.isFailure) {
            respondClassifiedError(call, result.exceptionOrNull())
            return
        }
        call.respond(HttpStatusCode.OK, mapOf("id" to modelId, "status" to "unloaded"))
    }

    private suspend fun handleModelDelete(call: ApplicationCall) {
        val modelId = call.parameters["modelId"]?.trim().orEmpty()
        if (modelId.isEmpty()) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail("modelId is required", "invalid_request_error", param = "modelId", code = "invalid_request")))
            return
        }
        val result = deleteModel(modelId)
        if (result.isFailure) {
            respondClassifiedError(call, result.exceptionOrNull())
            return
        }
        call.respond(HttpStatusCode.OK, mapOf("id" to modelId, "deleted" to "true"))
    }

    private suspend fun handleChatCompletions(call: ApplicationCall) {
        ensureAidinAttached()
        val locality = resolveLocality(call) ?: return
        val request = receiveBoundedJson<ChatCompletionRequest>(call) ?: return
        val sensitivePrompts = request.messages.mapNotNull { it.content }.filter { it.length >= 4 }

        if (request.model.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail("model must not be blank", "invalid_request_error", param = "model", code = "invalid_request"))
            )
            return
        }
        if (request.messages.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail("messages must not be empty", "invalid_request_error", param = "messages", code = "invalid_request"))
            )
            return
        }
        if (request.messages.size > policy.maxMessagesCount) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    ErrorDetail(
                        "messages count (${request.messages.size}) exceeds maximum limit of ${policy.maxMessagesCount}",
                        "invalid_request_error",
                        param = "messages",
                        code = "request_limit_exceeded"
                    )
                )
            )
            return
        }
        val totalPromptChars = request.messages.sumOf { (it.content?.length ?: 0) + (it.tool_calls?.sumOf { tc -> tc.function.arguments.length } ?: 0) }
        if (totalPromptChars > policy.maxPromptChars) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    ErrorDetail(
                        "Total prompt length ($totalPromptChars chars) exceeds maximum limit of ${policy.maxPromptChars} chars",
                        "invalid_request_error",
                        param = "messages",
                        code = "request_limit_exceeded"
                    )
                )
            )
            return
        }
        if (request.max_tokens != null) {
            if (request.max_tokens <= 0) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("max_tokens must be positive", "invalid_request_error", param = "max_tokens", code = "invalid_request"))
                )
                return
            }
            if (request.max_tokens > policy.maxOutputTokensLimit) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        ErrorDetail(
                            "max_tokens (${request.max_tokens}) exceeds generation limit of ${policy.maxOutputTokensLimit}",
                            "invalid_request_error",
                            param = "max_tokens",
                            code = "generation_limit_exceeded"
                        )
                    )
                )
                return
            }
        }
        if (request.temperature.isNaN() || request.temperature !in 0.0f..2.0f) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail("temperature must be between 0.0 and 2.0", "invalid_request_error", param = "temperature", code = "invalid_request"))
            )
            return
        }
        if (request.top_p.isNaN() || request.top_p !in 0.0f..1.0f) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail("top_p must be between 0.0 and 1.0", "invalid_request_error", param = "top_p", code = "invalid_request"))
            )
            return
        }

        try {
            val modelRequest = buildModelRequest(request)

            if (virtualModelRouter.isVirtualModelId(request.model)) {
                handleVirtualChatCompletions(call, request, modelRequest, locality, sensitivePrompts)
                return
            }

            if (request.stream) {
                val streamResult = executeManaged(request.model, locality) { adapter, placement ->
                    call.response.header(PLACEMENT_HEADER, placement.wire)
                    call.response.header(ROUTE_HEADER, "local")
                    streamChatCompletions(call, adapter, modelRequest, request.model, sensitivePrompts)
                }
                if (streamResult.isFailure && !call.response.isCommitted) {
                    respondClassifiedError(call, streamResult.exceptionOrNull(), sensitivePrompts)
                }
                return
            }

            val inferenceResult = executeManaged(request.model, locality) { adapter, placement ->
                call.response.header(PLACEMENT_HEADER, placement.wire)
                call.response.header(ROUTE_HEADER, "local")
                adapter.invoke(modelRequest).getOrThrow()
            }
            if (inferenceResult.isFailure) {
                respondClassifiedError(call, inferenceResult.exceptionOrNull(), sensitivePrompts)
                return
            }

            val chatResponse = toChatCompletionResponse(inferenceResult.getOrThrow(), request.model)
            call.respond(chatResponse)
        } catch (e: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    ErrorDetail(
                        sanitizeErrorMessage(e.message, "Invalid chat completion request", sensitivePrompts),
                        "invalid_request_error",
                        code = "invalid_request"
                    )
                )
            )
        } catch (e: Exception) {
            respondClassifiedError(call, e, sensitivePrompts)
        }
    }

    private fun buildModelRequest(request: ChatCompletionRequest): ModelRequest {
        val turns = request.messages.map { msg ->
            when (msg.role) {
                "system" -> Turn.System(msg.content ?: "")
                "user" -> Turn.User(
                    listOf<ContentBlock>(ContentBlock.Text(msg.content ?: "")) + msg.images.orEmpty().map(::decodeImage),
                    TrustLevel.TRUSTED,
                )
                "assistant" -> Turn.Assistant(
                    text = msg.content,
                    toolCalls = msg.tool_calls?.map { tc ->
                        KernelToolCall(
                            callId = tc.id,
                            toolName = tc.function.name,
                            arguments = parseJsonObject(tc.function.arguments),
                            capabilityId = null,
                            rawText = tc.function.arguments
                        )
                    } ?: emptyList()
                )
                "tool" -> Turn.ToolResult(
                    ToolCallResult(
                        callId = msg.tool_call_id ?: UUID.randomUUID().toString(),
                        outcome = ToolOutcome.Ok,
                        content = listOf(ContentBlock.Text(msg.content ?: "")),
                        trustLevel = TrustLevel.TRUSTED
                    )
                )
                else -> Turn.System(msg.content ?: "")
            }
        }

        val tools = request.tools?.map { toolDef ->
            ToolDescriptor(
                name = toolDef.function.name,
                title = toolDef.function.name,
                description = toolDef.function.description ?: "",
                inputSchema = toolDef.function.parameters ?: JsonObject(emptyMap()),
                effect = EffectKind.Read,
                requiredPermission = Permission.MODEL_QUERY,
                recoveryClass = RecoveryClass.PURE,
                availability = ToolAvailability(
                    profiles = setOf(PlatformProfile.MOBILE, PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER),
                    tier = AvailabilityTier.UNIVERSAL
                )
            )
        } ?: emptyList()

        val effectiveMaxTokens = (request.max_tokens ?: policy.defaultMaxOutputTokens)
            .coerceIn(1, policy.maxOutputTokensLimit)

        return ModelRequest(
            messages = turns,
            tools = tools,
            toolChoice = when (request.tool_choice) {
                "none" -> ToolChoice.None
                "required" -> ToolChoice.Required
                "auto" -> ToolChoice.Auto
                null -> if (tools.isNotEmpty()) ToolChoice.Auto else ToolChoice.None
                else -> ToolChoice.Auto
            },
            maxOutputTokens = effectiveMaxTokens,
            stopConditions = emptyList()
        )
    }

    private fun toChatCompletionResponse(response: ModelResponse, responseModelId: String): ChatCompletionResponse {
        val text = response.outputs.filterIsInstance<TextOutput>().joinToString("") { it.text }
        val toolCalls = response.outputs.filterIsInstance<ToolCallOutput>().map { it.call }

        val message = ChatMessage(
            role = "assistant",
            content = text,
            tool_calls = toolCalls.map { tc ->
                ToolCall(
                    id = tc.callId,
                    function = ToolFunctionCall(tc.toolName, tc.arguments.toString())
                )
            }
        )

        val usage = response.usage
        return ChatCompletionResponse(
            id = "chatcmpl-${UUID.randomUUID()}",
            created = System.currentTimeMillis() / 1000,
            model = responseModelId,
            choices = listOf(Choice(0, message, response.stopReason?.name?.lowercase())),
            usage = TokenUsage(
                prompt_tokens = usage?.inputTokens ?: 0,
                completion_tokens = usage?.outputTokens ?: 0,
                total_tokens = usage?.totalTokens ?: 0
            )
        )
    }

    private suspend fun handleVirtualChatCompletions(
        call: ApplicationCall,
        request: ChatCompletionRequest,
        modelRequest: ModelRequest,
        locality: Locality,
        sensitivePrompts: Collection<String>,
    ) {
        val routes = virtualModelRouter.resolveRoutes(request.model, locality)
        if (request.stream) {
            val primary = routes.first()
            when (primary) {
                is ResolvedCandidateRoute.LocalModel -> {
                    val streamResult = executeManaged(primary.modelId, locality) { adapter, _ ->
                        call.response.header(PLACEMENT_HEADER, primary.placement.wire)
                        call.response.header(ROUTE_HEADER, primary.routeHeader)
                        streamChatCompletions(call, adapter, modelRequest, request.model, sensitivePrompts)
                    }
                    if (streamResult.isFailure && !call.response.isCommitted) {
                        respondClassifiedError(call, streamResult.exceptionOrNull(), sensitivePrompts)
                    }
                }
                is ResolvedCandidateRoute.LocalSplitAnchor -> {
                    call.response.header(PLACEMENT_HEADER, primary.placement.wire)
                    call.response.header(ROUTE_HEADER, primary.routeHeader)
                    streamLocalSplitAnchor(call, primary, request, sensitivePrompts)
                }
                is ResolvedCandidateRoute.PeerDelegation -> {
                    streamPeerDelegation(call, primary, request, locality)
                }
            }
            return
        }

        // Non-streaming: try primary route; if it is a peer/split candidate and fails with a
        // retryable peer/plan error, quarantine it and retry once on the next candidate (RFC-0105).
        val firstAttempt = runCatching { executeChatRoute(routes[0], request, modelRequest, locality) }
        if (firstAttempt.isSuccess) {
            val (usedRoute, chatResp) = firstAttempt.getOrThrow()
            call.response.header(PLACEMENT_HEADER, usedRoute.placement.wire)
            call.response.header(ROUTE_HEADER, usedRoute.routeHeader)
            call.respond(chatResp.copy(model = request.model))
            return
        }

        val firstErr = firstAttempt.exceptionOrNull()!!
        val fallbackRoute = routes.getOrNull(1)
        if (routes[0] !is ResolvedCandidateRoute.LocalModel && isRetryableRouteFailure(firstErr) && fallbackRoute != null) {
            quarantineFailedRoute(routes[0])
            val secondAttempt = runCatching { executeChatRoute(fallbackRoute, request, modelRequest, locality) }
            if (secondAttempt.isSuccess) {
                val (usedRoute, chatResp) = secondAttempt.getOrThrow()
                call.response.header(PLACEMENT_HEADER, usedRoute.placement.wire)
                call.response.header(ROUTE_HEADER, usedRoute.routeHeader)
                call.respond(chatResp.copy(model = request.model))
                return
            }
            respondClassifiedError(call, secondAttempt.exceptionOrNull(), sensitivePrompts)
            return
        }

        respondClassifiedError(call, firstErr, sensitivePrompts)
    }

    private suspend fun executeChatRoute(
        route: ResolvedCandidateRoute,
        request: ChatCompletionRequest,
        modelRequest: ModelRequest,
        locality: Locality,
    ): Pair<ResolvedCandidateRoute, ChatCompletionResponse> {
        return when (route) {
            is ResolvedCandidateRoute.LocalModel -> {
                val modelResp = executeManaged(route.modelId, locality) { adapter, _ ->
                    adapter.invoke(modelRequest).getOrThrow()
                }.getOrThrow()
                route to toChatCompletionResponse(modelResp, request.model)
            }
            is ResolvedCandidateRoute.LocalSplitAnchor -> {
                val node = aidinNode() ?: throw ModelUnavailableException(route.modelId)
                val anchor = node.activeAnchorSupervisor ?: throw ModelUnavailableException(route.modelId)
                val promptText = request.messages.joinToString("\n") { it.content.orEmpty() }
                when (val outcome = anchor.invoke(promptText, route.planVersion)) {
                    is SplitInferenceOutcome.Success -> {
                        val msg = ChatMessage(role = "assistant", content = outcome.text)
                        val resp = ChatCompletionResponse(
                            id = "chatcmpl-${UUID.randomUUID()}",
                            created = System.currentTimeMillis() / 1000,
                            model = request.model,
                            choices = listOf(Choice(0, msg, "stop")),
                            usage = TokenUsage(0, 0, 0),
                        )
                        route to resp
                    }
                    is SplitInferenceOutcome.PlanChanged -> throw PlanChangedException("Split plan version changed")
                    is SplitInferenceOutcome.Unavailable -> throw ModelUnavailableException(route.modelId, outcome.reason)
                }
            }
            is ResolvedCandidateRoute.PeerDelegation -> {
                val node = aidinNode() ?: throw ModelUnavailableException(route.modelId)
                val delegatedReq = request.copy(model = route.modelId, stream = false)
                val bodyJson = wireJson.encodeToString(delegatedReq)
                val resp = node.delegate(
                    peerNodeId = route.peerNodeId,
                    endpoint = "/aidin/v1/chat/completions",
                    bodyJson = bodyJson,
                    locality = locality.wire,
                    planVersion = route.planVersion,
                )
                if (resp.status == 200) {
                    val decoded = wireJson.decodeFromString<ChatCompletionResponse>(resp.bodyJson)
                    route to decoded
                } else {
                    throw exceptionFromPeerError(resp.status, resp.bodyJson, route.peerNodeId.value, route.modelId)
                }
            }
        }
    }

    private suspend fun streamLocalSplitAnchor(
        call: ApplicationCall,
        route: ResolvedCandidateRoute.LocalSplitAnchor,
        request: ChatCompletionRequest,
        sensitivePrompts: Collection<String>,
    ) {
        val node = aidinNode()
        val anchor = node?.activeAnchorSupervisor
        if (anchor == null) {
            respondClassifiedError(call, ModelUnavailableException(route.modelId), sensitivePrompts)
            return
        }
        val promptText = request.messages.joinToString("\n") { it.content.orEmpty() }
        val completionId = "chatcmpl-${UUID.randomUUID()}"
        call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Connection, "keep-alive")
        call.respondBytesWriter(status = HttpStatusCode.OK) {
            when (val outcome = anchor.invoke(promptText, route.planVersion)) {
                is SplitInferenceOutcome.Success -> {
                    val chunk = ChatCompletionChunk(
                        id = completionId,
                        created = System.currentTimeMillis() / 1000,
                        model = request.model,
                        choices = listOf(ChunkChoice(0, ChunkDelta(content = outcome.text), null)),
                    )
                    writeStringUtf8("data: ${wireJson.encodeToString(chunk)}\n\n")
                    val doneChunk = ChatCompletionChunk(
                        id = completionId,
                        created = System.currentTimeMillis() / 1000,
                        model = request.model,
                        choices = listOf(ChunkChoice(0, ChunkDelta(content = ""), "stop")),
                    )
                    writeStringUtf8("data: ${wireJson.encodeToString(doneChunk)}\n\n")
                    writeStringUtf8("data: [DONE]\n\n")
                    flush()
                }
                else -> {
                    writeStringUtf8("data: {\"type\":\"peer_lost\",\"retryable\":true}\n\n")
                    writeStringUtf8("data: [DONE]\n\n")
                    flush()
                }
            }
        }
    }

    private suspend fun streamPeerDelegation(
        call: ApplicationCall,
        route: ResolvedCandidateRoute.PeerDelegation,
        request: ChatCompletionRequest,
        locality: Locality,
    ) {
        val node = aidinNode() ?: throw ModelUnavailableException(route.modelId)
        val delegatedReq = request.copy(model = route.modelId, stream = true)
        val bodyJson = wireJson.encodeToString(delegatedReq)

        call.response.header(PLACEMENT_HEADER, route.placement.wire)
        call.response.header(ROUTE_HEADER, route.routeHeader)
        call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Connection, "keep-alive")

        call.respondBytesWriter(status = HttpStatusCode.OK) {
            var sawDone = false
            try {
                node.delegateStreaming(
                    peerNodeId = route.peerNodeId,
                    endpoint = "/aidin/v1/chat/completions",
                    bodyJson = bodyJson,
                    locality = locality.wire,
                    planVersion = route.planVersion,
                ).collect { msg ->
                    when (msg) {
                        is LinkMessage.DelegateStreamEvent -> {
                            if (msg.sseData.contains("\"peer_lost\"")) {
                                quarantineFailedRoute(route)
                            }
                            if (msg.sseData.isNotBlank()) {
                                writeStringUtf8("data: ${msg.sseData}\n\n")
                                flush()
                            }
                            if (msg.done) {
                                writeStringUtf8("data: [DONE]\n\n")
                                flush()
                                sawDone = true
                            }
                        }
                        is LinkMessage.DelegateResponse -> {
                            quarantineFailedRoute(route)
                            writeStringUtf8("data: {\"type\":\"peer_lost\",\"retryable\":true}\n\n")
                            writeStringUtf8("data: [DONE]\n\n")
                            flush()
                            sawDone = true
                        }
                        else -> Unit
                    }
                }
            } catch (_: Exception) {
                quarantineFailedRoute(route)
                if (!sawDone) {
                    writeStringUtf8("data: {\"type\":\"peer_lost\",\"retryable\":true}\n\n")
                    writeStringUtf8("data: [DONE]\n\n")
                    flush()
                    sawDone = true
                }
            }
            if (!sawDone) {
                writeStringUtf8("data: [DONE]\n\n")
                flush()
            }
        }
    }

    private fun isRetryableRouteFailure(error: Throwable): Boolean = when (error) {
        is PeerBusyException,
        is PlanChangedException,
        is ModelUnavailableException,
        is EngineRequestTimeoutException,
        is TimeoutCancellationException,
        is java.io.IOException -> true
        else -> false
    }

    private fun quarantineFailedRoute(route: ResolvedCandidateRoute) {
        if (route is ResolvedCandidateRoute.PeerDelegation) {
            virtualModelRouter.markRouteQuarantined("peer:${route.peerNodeId.value}/${route.modelId}")
            aidinNode()?.markPeerSuspected(route.peerNodeId)
        }
    }

    private fun exceptionFromPeerError(status: Int, bodyJson: String, peerId: String, modelId: String): Exception {
        val parsed = runCatching { wireJson.decodeFromString<ErrorResponse>(bodyJson) }.getOrNull()?.error
        val code = parsed?.code.orEmpty()
        val type = parsed?.type.orEmpty()
        val msg = parsed?.message ?: "Peer $peerId failed with status $status"
        return when {
            status == 409 || code == "plan_changed" || type == "plan_changed" ->
                PlanChangedException(msg)
            status == 503 && (code == "peer_busy" || type == "peer_busy" || code == "queue_saturated") ->
                PeerBusyException(peerId, msg)
            status == 504 || code == "request_timeout" ->
                EngineRequestTimeoutException(msg)
            status == 403 && type == "locality_violation" ->
                LocalityViolationException(modelId, Locality.LOCAL_ONLY, Placement.CLUSTER)
            else ->
                ModelUnavailableException(modelId, msg)
        }
    }

    private suspend fun streamChatCompletions(
        call: ApplicationCall,
        adapter: ModelAdapter,
        modelRequest: ModelRequest,
        modelId: String,
        sensitivePrompts: Collection<String> = emptyList(),
    ) {
        val completionId = "chatcmpl-${UUID.randomUUID()}"

        call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Connection, "keep-alive")

        call.respondBytesWriter(status = HttpStatusCode.OK) {
            try {
                adapter.invokeStreaming(modelRequest).collect { event ->
                    when (event) {
                        is ModelStreamEvent.Delta -> {
                            val chunk = ChatCompletionChunk(
                                id = completionId,
                                created = System.currentTimeMillis() / 1000,
                                model = modelId,
                                choices = listOf(ChunkChoice(0, ChunkDelta(content = event.text), null))
                            )
                            writeStringUtf8("data: ${wireJson.encodeToString(chunk)}\n\n")
                            flush()
                        }
                        is ModelStreamEvent.Done -> {
                            val finalChunk = ChatCompletionChunk(
                                id = completionId,
                                created = System.currentTimeMillis() / 1000,
                                model = modelId,
                                choices = listOf(ChunkChoice(0, ChunkDelta(content = ""), event.response.stopReason?.name?.lowercase()))
                            )
                            writeStringUtf8("data: ${wireJson.encodeToString(finalChunk)}\n\n")
                        }
                        is ModelStreamEvent.Failed -> {
                            val (_, detail) = classifyError(event.error, sensitivePrompts)
                            writeStringUtf8(
                                "data: ${wireJson.encodeToString(ErrorResponse(detail))}\n\n"
                            )
                        }
                    }
                }
            } catch (e: TimeoutCancellationException) {
                val detail = ErrorDetail("Inference request timed out", "timeout_error", code = "request_timeout")
                writeStringUtf8("data: ${wireJson.encodeToString(ErrorResponse(detail))}\n\n")
                writeStringUtf8("data: [DONE]\n\n")
                flush()
                throw e
            }
            writeStringUtf8("data: [DONE]\n\n")
            flush()
        }
    }

    private fun parseJsonObject(jsonString: String): JsonObject {
        return try {
            wireJson.parseToJsonElement(jsonString) as JsonObject
        } catch (_: Exception) {
            JsonObject(emptyMap())
        }
    }

    private suspend fun handleModels(call: ApplicationCall) {
        ensureAidinAttached()
        try {
            val catalogById = modelRuntime.catalog().associateBy { it.id }
            val installedById = modelRuntime.installed().associateBy { it.id }
            val loadedIds = modelRuntime.loaded().toSet()
            val allDescriptors = catalogById + installedById
            val allModelIds = allDescriptors.keys.sorted()

            val physicalModels = allModelIds.map { modelId ->
                val catalogModel = catalogById[modelId]
                val installedModel = installedById[modelId]
                val model = installedModel ?: catalogModel
                    ?: error("Model $modelId unexpectedly missing from both catalog and installed sets")

                ModelCard(
                    id = model.id,
                    owned_by = model.providerId,
                    kind = model.kind.name.lowercase(),
                    capabilities = capabilitiesFor(model.kind),
                    context_window = model.contextWindow,
                    format = model.format,
                    size_bytes = installedModel?.sizeBytes ?: catalogModel?.sizeBytes,
                    quantization = model.quantization,
                    installed = installedModel != null,
                    loaded = loadedIds.contains(model.id),
                    placement = placementOf(model).wire,
                    metadata = buildMap {
                        putAll(model.metadata)
                        model.digest?.let { put("digest", it) }
                        put("is_local", model.isLocal.toString())
                        put("provider_id", model.providerId)
                    },
                )
            }

            val virtualCards = virtualModelRouter.virtualModels()
                .filter { it.id !in allDescriptors }
                .map { vm ->
                    val widest = vm.widestPlacement(allDescriptors)
                    ModelCard(
                        id = vm.id,
                        owned_by = "aidos",
                        kind = vm.kind.name.lowercase(),
                        capabilities = capabilitiesFor(vm.kind),
                        context_window = 8192,
                        format = "virtual",
                        installed = vm.candidates.isNotEmpty(),
                        loaded = vm.candidates.any { c -> c.modelId in loadedIds },
                        placement = widest.wire,
                        metadata = mapOf(
                            "virtual" to "true",
                            "candidates" to vm.candidates.joinToString(",") { it.wire },
                            "is_local" to (widest == Placement.LOCAL).toString(),
                            "provider_id" to "aidos",
                        ),
                    )
                }

            call.respond(ModelsResponse(data = physicalModels + virtualCards))
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    ErrorDetail(
                        sanitizeErrorMessage(e.message, "Failed to list models"),
                        "internal_error",
                        code = "internal_error"
                    )
                )
            )
        }
    }

    private fun capabilitiesFor(kind: ModelKind): List<String> = when (kind) {
        ModelKind.LLM -> listOf("chat.completions")
        ModelKind.EMBEDDING -> listOf("embeddings")
        ModelKind.STT -> listOf("audio.transcriptions")
        ModelKind.TTS -> listOf("audio.speech")
        ModelKind.VISION -> listOf("vision")
        ModelKind.OCR -> listOf("ocr")
        ModelKind.RERANKER -> listOf("rerank")
        ModelKind.TRANSLATION -> listOf("translation")
        ModelKind.DECISION -> listOf("decisions")
    }

    private suspend fun handleEmbeddings(call: ApplicationCall) {
        ensureAidinAttached()
        val locality = resolveLocality(call) ?: return
        val request = receiveBoundedJson<EmbeddingsRequest>(call) ?: return
        val sensitiveInputs = request.input.filter { it.length >= 4 }

        if (request.model.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail("model must not be blank", "invalid_request_error", param = "model", code = "invalid_request"))
            )
            return
        }
        if (request.input.isEmpty()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail("input must contain at least one string", "invalid_request_error", param = "input", code = "invalid_request"))
            )
            return
        }
        if (request.input.size > policy.maxEmbeddingInputs) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    ErrorDetail(
                        "input batch size (${request.input.size}) exceeds maximum limit of ${policy.maxEmbeddingInputs}",
                        "invalid_request_error",
                        param = "input",
                        code = "request_limit_exceeded"
                    )
                )
            )
            return
        }
        val totalChars = request.input.sumOf { it.length }
        if (totalChars > policy.maxEmbeddingInputChars) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    ErrorDetail(
                        "Total embedding input length ($totalChars chars) exceeds maximum limit of ${policy.maxEmbeddingInputChars} chars",
                        "invalid_request_error",
                        param = "input",
                        code = "request_limit_exceeded"
                    )
                )
            )
            return
        }
        if (request.encoding_format != null && request.encoding_format != "float") {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail("only float embeddings are supported", "invalid_request_error", param = "encoding_format", code = "invalid_request"))
            )
            return
        }

        try {
            if (virtualModelRouter.isVirtualModelId(request.model)) {
                val routes = virtualModelRouter.resolveRoutes(request.model, locality)
                val first = runCatching { executeEmbeddingsRoute(routes[0], request, locality) }
                if (first.isSuccess) {
                    val (usedRoute, resp) = first.getOrThrow()
                    call.response.header(PLACEMENT_HEADER, usedRoute.placement.wire)
                    call.response.header(ROUTE_HEADER, usedRoute.routeHeader)
                    call.respond(resp.copy(model = request.model))
                    return
                }
                val err = first.exceptionOrNull()!!
                val fallback = routes.getOrNull(1)
                if (routes[0] !is ResolvedCandidateRoute.LocalModel && isRetryableRouteFailure(err) && fallback != null) {
                    quarantineFailedRoute(routes[0])
                    val second = runCatching { executeEmbeddingsRoute(fallback, request, locality) }
                    if (second.isSuccess) {
                        val (usedRoute, resp) = second.getOrThrow()
                        call.response.header(PLACEMENT_HEADER, usedRoute.placement.wire)
                        call.response.header(ROUTE_HEADER, usedRoute.routeHeader)
                        call.respond(resp.copy(model = request.model))
                        return
                    }
                    respondClassifiedError(call, second.exceptionOrNull(), sensitiveInputs)
                    return
                }
                respondClassifiedError(call, err, sensitiveInputs)
                return
            }

            val embeddingsResult = executeManaged(request.model, locality) { adapter, placement ->
                call.response.header(PLACEMENT_HEADER, placement.wire)
                call.response.header(ROUTE_HEADER, "local")
                val embeddingAdapter = adapter as? EmbeddingModelAdapter
                    ?: throw UnsupportedOperationException("Model ${request.model} does not support embeddings")

                request.input.mapIndexed { index, text ->
                    val vector = embeddingAdapter.embed(text).getOrThrow()
                    if (vector.isEmpty()) {
                        throw IllegalStateException("Embedding model returned an empty vector")
                    }
                    Embedding(embedding = vector.toList(), index = index)
                }
            }
            if (embeddingsResult.isFailure) {
                respondClassifiedError(call, embeddingsResult.exceptionOrNull(), sensitiveInputs)
                return
            }

            val embeddings = embeddingsResult.getOrThrow()
            val dimension = embeddings.firstOrNull()?.embedding?.size ?: 0
            if (dimension == 0 || embeddings.any { it.embedding.size != dimension }) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(ErrorDetail("Embedding model returned inconsistent vector dimensions", "embedding_error", code = "embedding_dimension_mismatch"))
                )
                return
            }

            call.respond(
                EmbeddingsResponse(
                    data = embeddings,
                    model = request.model,
                    usage = TokenUsage(0, 0, 0),
                )
            )
        } catch (e: Exception) {
            respondClassifiedError(call, e, sensitiveInputs)
        }
    }

    private suspend fun executeEmbeddingsRoute(
        route: ResolvedCandidateRoute,
        request: EmbeddingsRequest,
        locality: Locality,
    ): Pair<ResolvedCandidateRoute, EmbeddingsResponse> = when (route) {
        is ResolvedCandidateRoute.LocalModel -> {
            val embeddings = executeManaged(route.modelId, locality) { adapter, _ ->
                val embeddingAdapter = adapter as? EmbeddingModelAdapter
                    ?: throw UnsupportedOperationException("Model ${route.modelId} does not support embeddings")
                request.input.mapIndexed { index, text ->
                    val vector = embeddingAdapter.embed(text).getOrThrow()
                    if (vector.isEmpty()) throw IllegalStateException("Embedding model returned an empty vector")
                    Embedding(embedding = vector.toList(), index = index)
                }
            }.getOrThrow()
            route to EmbeddingsResponse(data = embeddings, model = request.model, usage = TokenUsage(0, 0, 0))
        }
        is ResolvedCandidateRoute.PeerDelegation -> {
            val node = aidinNode() ?: throw ModelUnavailableException(route.modelId)
            val bodyJson = wireJson.encodeToString(request.copy(model = route.modelId))
            val resp = node.delegate(route.peerNodeId, "/aidin/v1/embeddings", bodyJson, locality.wire, route.planVersion)
            if (resp.status == 200) {
                route to wireJson.decodeFromString<EmbeddingsResponse>(resp.bodyJson)
            } else {
                throw exceptionFromPeerError(resp.status, resp.bodyJson, route.peerNodeId.value, route.modelId)
            }
        }
        is ResolvedCandidateRoute.LocalSplitAnchor -> throw UnsupportedOperationException("Split model does not support embeddings")
    }

    private suspend fun handleDecisions(call: ApplicationCall) {
        ensureAidinAttached()
        val locality = resolveLocality(call) ?: return
        val rawObject = receiveBoundedJson<JsonObject>(call) ?: return
        val parsed = try {
            DecisionWire.parseRequest(rawObject)
        } catch (e: DecisionRequestException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail(sanitizeErrorMessage(e.message, "invalid request"), "invalid_request_error", code = "invalid_request"))
            )
            return
        } catch (_: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail("request body must be a JSON object", "invalid_request_error", code = "invalid_request"))
            )
            return
        }
        val sensitiveInputs = listOfNotNull(parsed.request.state.takeIf { it.length >= 4 })
        val outcome = executeManaged(parsed.model, locality) { adapter, placement ->
            call.response.header(PLACEMENT_HEADER, placement.wire)
            call.response.header(ROUTE_HEADER, "local")
            val decisionAdapter = adapter as? DecisionModelAdapter
                ?: throw UnsupportedOperationException("Model ${parsed.model} is not a decision model")
            decisionAdapter.decide(parsed.request).getOrThrow()
        }
        val error = outcome.exceptionOrNull()
        when {
            error is DecisionRequestException ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail(sanitizeErrorMessage(error.message, "invalid request", sensitiveInputs), "invalid_request_error", code = "invalid_request"))
                )
            error is UnsupportedOperationException ->
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail(sanitizeErrorMessage(error.message, "unsupported", sensitiveInputs), "invalid_request_error", param = "model", code = "unsupported_capability"))
                )
            error != null -> respondClassifiedError(call, error, sensitiveInputs)
            else -> call.respondText(
                DecisionWire.renderResult(outcome.getOrThrow()).toString(),
                ContentType.Application.Json,
            )
        }
    }

    private suspend fun handleTranscriptions(call: ApplicationCall) {
        ensureAidinAttached()
        val locality = resolveLocality(call) ?: return
        val request = receiveBoundedJson<TranscriptionRequest>(call) ?: return
        val sensitivePrompts = listOfNotNull(request.prompt?.takeIf { it.length >= 4 })

        if (request.model.isBlank()) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail("model must not be blank", "invalid_request_error", param = "model", code = "invalid_request"))
            )
            return
        }

        val audioBytes = try {
            Base64.getDecoder().decode(request.file)
        } catch (_: IllegalArgumentException) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(ErrorDetail("file must be valid base64-encoded audio bytes", "invalid_request_error", param = "file", code = "invalid_request"))
            )
            return
        }

        if (audioBytes.size > policy.maxAudioBytes) {
            call.respond(
                HttpStatusCode.PayloadTooLarge,
                ErrorResponse(
                    ErrorDetail(
                        "Decoded audio size (${audioBytes.size} bytes) exceeds maximum limit of ${policy.maxAudioBytes} bytes",
                        "invalid_request_error",
                        param = "file",
                        code = "request_too_large"
                    )
                )
            )
            return
        }

        try {
            if (virtualModelRouter.isVirtualModelId(request.model)) {
                val routes = virtualModelRouter.resolveRoutes(request.model, locality)
                val first = runCatching { executeTranscriptionRoute(routes[0], request, audioBytes, locality) }
                if (first.isSuccess) {
                    val (usedRoute, resp) = first.getOrThrow()
                    call.response.header(PLACEMENT_HEADER, usedRoute.placement.wire)
                    call.response.header(ROUTE_HEADER, usedRoute.routeHeader)
                    call.respond(resp)
                    return
                }
                val err = first.exceptionOrNull()!!
                val fallback = routes.getOrNull(1)
                if (routes[0] !is ResolvedCandidateRoute.LocalModel && isRetryableRouteFailure(err) && fallback != null) {
                    quarantineFailedRoute(routes[0])
                    val second = runCatching { executeTranscriptionRoute(fallback, request, audioBytes, locality) }
                    if (second.isSuccess) {
                        val (usedRoute, resp) = second.getOrThrow()
                        call.response.header(PLACEMENT_HEADER, usedRoute.placement.wire)
                        call.response.header(ROUTE_HEADER, usedRoute.routeHeader)
                        call.respond(resp)
                        return
                    }
                    respondClassifiedError(call, second.exceptionOrNull(), sensitivePrompts)
                    return
                }
                respondClassifiedError(call, err, sensitivePrompts)
                return
            }

            val result = executeManaged(request.model, locality) { adapter, placement ->
                call.response.header(PLACEMENT_HEADER, placement.wire)
                call.response.header(ROUTE_HEADER, "local")
                // A speech-to-text model (Whisper) transcribes directly. Any other model keeps the older
                // path of handing the audio to the model as a chat turn (an audio-capable LLM, once one exists).
                if (adapter is SttModelAdapter) {
                    val stt = adapter.transcribe(SttRequest(audioBytes, request.language, request.prompt)).getOrThrow()
                    return@executeManaged stt.text
                }
                val modelRequest = ModelRequest(
                    messages = listOf(
                        Turn.System(request.prompt ?: "Transcribe audio"),
                        Turn.User(listOf(ContentBlock.Audio("audio/wav", audioBytes)), TrustLevel.TRUSTED)
                    ),
                    tools = emptyList(),
                    toolChoice = ToolChoice.None,
                    maxOutputTokens = 1000.coerceAtMost(policy.maxOutputTokensLimit),
                    stopConditions = emptyList()
                )
                adapter.invoke(modelRequest).getOrThrow().outputs.filterIsInstance<TextOutput>().joinToString("") { it.text }
            }
            val failure = result.exceptionOrNull()
            if (failure is SttRequestException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail(sanitizeErrorMessage(failure.message, "invalid audio", sensitivePrompts), "invalid_request_error", param = "file", code = "invalid_audio"))
                )
                return
            }
            if (result.isFailure) {
                respondClassifiedError(call, failure, sensitivePrompts)
                return
            }
            call.respond(TranscriptionResponse(text = result.getOrThrow()))
        } catch (e: Exception) {
            respondClassifiedError(call, e, sensitivePrompts)
        }
    }

    private suspend fun executeTranscriptionRoute(
        route: ResolvedCandidateRoute,
        request: TranscriptionRequest,
        audioBytes: ByteArray,
        locality: Locality,
    ): Pair<ResolvedCandidateRoute, TranscriptionResponse> = when (route) {
        is ResolvedCandidateRoute.LocalModel -> {
            val text = executeManaged(route.modelId, locality) { adapter, _ ->
                if (adapter is SttModelAdapter) {
                    adapter.transcribe(SttRequest(audioBytes, request.language, request.prompt)).getOrThrow().text
                } else {
                    val modelRequest = ModelRequest(
                        messages = listOf(
                            Turn.System(request.prompt ?: "Transcribe audio"),
                            Turn.User(listOf(ContentBlock.Audio("audio/wav", audioBytes)), TrustLevel.TRUSTED)
                        ),
                        tools = emptyList(),
                        toolChoice = ToolChoice.None,
                        maxOutputTokens = 1000.coerceAtMost(policy.maxOutputTokensLimit),
                        stopConditions = emptyList()
                    )
                    adapter.invoke(modelRequest).getOrThrow().outputs.filterIsInstance<TextOutput>().joinToString("") { it.text }
                }
            }.getOrThrow()
            route to TranscriptionResponse(text = text)
        }
        is ResolvedCandidateRoute.PeerDelegation -> {
            val node = aidinNode() ?: throw ModelUnavailableException(route.modelId)
            val bodyJson = wireJson.encodeToString(request.copy(model = route.modelId))
            val resp = node.delegate(route.peerNodeId, "/aidin/v1/audio/transcriptions", bodyJson, locality.wire, route.planVersion)
            if (resp.status == 200) {
                route to wireJson.decodeFromString<TranscriptionResponse>(resp.bodyJson)
            } else {
                throw exceptionFromPeerError(resp.status, resp.bodyJson, route.peerNodeId.value, route.modelId)
            }
        }
        is ResolvedCandidateRoute.LocalSplitAnchor -> throw UnsupportedOperationException("Split model does not support transcription")
    }

    // ---- RFC-0105 Phase 2 & 3 Peer Delegation (/aidin/v1/*) --------------------------------

    private suspend fun handleAidinHttpRoute(call: ApplicationCall) {
        val req = receiveBoundedJson<RouteQueryRequest>(call) ?: return
        val info = computeLocalRouteInfo(req.model)
        if (info == null) {
            call.respond(
                HttpStatusCode.NotFound,
                ErrorResponse(ErrorDetail("Model ${req.model} is not installed on this peer", "model_error", code = "model_not_installed"))
            )
            return
        }
        call.respond(HttpStatusCode.OK, info)
    }

    private suspend fun handleAidinHttpDelegation(call: ApplicationCall, endpoint: String) {
        val hop = call.request.headers[HOP_HEADER]?.toIntOrNull() ?: 1
        if (hop > 1) {
            call.respond(
                HttpStatusCode.Forbidden,
                ErrorResponse(ErrorDetail("Delegation hop limit (1) exceeded", "locality_violation", code = "hop_limit_exceeded"))
            )
            return
        }
        val planVersion = call.request.headers[PLAN_VERSION_HEADER]?.toLongOrNull()
        val callerLocalityRaw = call.request.headers[LOCALITY_HEADER] ?: Locality.ANY.wire
        val bodyText = receiveBoundedBodyText(call) ?: return
        val fakeReq = LinkMessage.DelegateRequest(
            requestId = UUID.randomUUID().toString(),
            endpoint = endpoint,
            bodyJson = bodyText,
            locality = callerLocalityRaw,
            hop = hop,
            planVersion = planVersion,
            stream = false,
        )
        val fromNode = aidinNode()?.nodeId ?: NodeId("0".repeat(64))
        val resp = handleLinkDelegation(fromNode, fakeReq) {}
        call.respondText(resp.bodyJson, ContentType.Application.Json, HttpStatusCode.fromValue(resp.status))
    }

    private suspend fun computeLocalRouteInfo(modelId: String): RouteInfo? {
        val node = aidinNode()
        val anchor = node?.activeAnchorSupervisor
        val plan = anchor?.activePlan
        if (node != null && anchor != null && anchor.isAvailable && plan != null && plan.modelId == modelId) {
            val groupId = (plan.domain as? TrustDomain.Group)?.groupId?.value
            return RouteInfo(
                model = modelId,
                peerPlacement = Placement.CLUSTER.wire,
                anchorNodeId = node.nodeId.value,
                helperNodeIds = plan.helpers.map { it.nodeId.value },
                planId = plan.planId,
                planVersion = plan.version,
                groupId = groupId,
            )
        }
        val descriptor = modelRuntime.installed().firstOrNull { it.id == modelId }
            ?: modelRuntime.catalog().firstOrNull { it.id == modelId }
            ?: return null
        val anchorId = node?.nodeId?.value ?: "local"
        return RouteInfo(
            model = modelId,
            peerPlacement = placementOf(descriptor).wire,
            anchorNodeId = anchorId,
            helperNodeIds = emptyList(),
        )
    }

    private suspend fun handleLinkDelegation(
        @Suppress("UNUSED_PARAMETER") fromPeer: NodeId,
        request: LinkMessage.DelegateRequest,
        emitStream: suspend (LinkMessage.DelegateStreamEvent) -> Unit,
    ): LinkMessage.DelegateResponse {
        if (request.hop > 1) {
            return LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 403,
                bodyJson = wireJson.encodeToString(
                    ErrorResponse(ErrorDetail("Delegation hop limit (1) exceeded", "locality_violation", code = "hop_limit_exceeded"))
                ),
            )
        }

        if (request.endpoint == "/aidin/v1/route") {
            val routeReq = runCatching { wireJson.decodeFromString<RouteQueryRequest>(request.bodyJson) }.getOrNull()
                ?: return LinkMessage.DelegateResponse(
                    requestId = request.requestId,
                    status = 400,
                    bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail("Invalid route query", "invalid_request_error", code = "invalid_json"))),
                )
            val info = computeLocalRouteInfo(routeReq.model)
                ?: return LinkMessage.DelegateResponse(
                    requestId = request.requestId,
                    status = 404,
                    bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail("Model not found", "model_error", code = "model_not_installed"))),
                )
            return LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 200,
                bodyJson = wireJson.encodeToString(info),
            )
        }

        if (isPeerDraining()) {
            return LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 503,
                bodyJson = wireJson.encodeToString(
                    ErrorResponse(ErrorDetail("Peer is draining or busy", "peer_busy", code = "peer_busy"))
                ),
            )
        }

        // A peer endpoint runs under the stricter of the caller's locality and its own Engine locality,
        // and never delegates further (hop == 1).
        val callerLocality = Locality.parse(request.locality) ?: Locality.LOCAL_ONLY
        val effectivePeerLocality = callerLocality.strictest(engineLocality())

        return when (request.endpoint) {
            "/aidin/v1/chat/completions" -> handleDelegatedChat(request, effectivePeerLocality, emitStream)
            "/aidin/v1/embeddings" -> handleDelegatedEmbeddings(request, effectivePeerLocality)
            "/aidin/v1/audio/transcriptions" -> handleDelegatedTranscriptions(request, effectivePeerLocality)
            "/aidin/v1/systemone" -> handleDelegatedDecisions(request, effectivePeerLocality)
            else -> LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 404,
                bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail("Unknown peer endpoint ${request.endpoint}", "invalid_request_error", code = "not_found"))),
            )
        }
    }

    private suspend fun handleDelegatedChat(
        request: LinkMessage.DelegateRequest,
        peerLocality: Locality,
        emitStream: suspend (LinkMessage.DelegateStreamEvent) -> Unit,
    ): LinkMessage.DelegateResponse {
        val chatReq = runCatching { wireJson.decodeFromString<ChatCompletionRequest>(request.bodyJson) }.getOrNull()
            ?: return LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 400,
                bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail("Malformed chat request", "invalid_request_error", code = "invalid_json"))),
            )

        // Check if this peer is the active split-inference anchor for chatReq.model
        val anchor = aidinNode()?.activeAnchorSupervisor
        val plan = anchor?.activePlan
        if (anchor != null && plan != null && plan.modelId == chatReq.model) {
            val promptText = chatReq.messages.joinToString("\n") { it.content.orEmpty() }
            return when (val outcome = anchor.invoke(promptText, request.planVersion)) {
                is SplitInferenceOutcome.Success -> {
                    val msg = ChatMessage(role = "assistant", content = outcome.text)
                    val resp = ChatCompletionResponse(
                        id = "chatcmpl-${UUID.randomUUID()}",
                        created = System.currentTimeMillis() / 1000,
                        model = chatReq.model,
                        choices = listOf(Choice(0, msg, "stop")),
                        usage = TokenUsage(0, 0, 0),
                    )
                    if (request.stream || chatReq.stream) {
                        val chunk = ChatCompletionChunk(
                            id = resp.id,
                            created = resp.created,
                            model = chatReq.model,
                            choices = listOf(ChunkChoice(0, ChunkDelta(content = outcome.text), "stop")),
                        )
                        emitStream(LinkMessage.DelegateStreamEvent(request.requestId, wireJson.encodeToString(chunk), done = true))
                    }
                    LinkMessage.DelegateResponse(request.requestId, 200, wireJson.encodeToString(resp))
                }
                is SplitInferenceOutcome.PlanChanged -> LinkMessage.DelegateResponse(
                    requestId = request.requestId,
                    status = 409,
                    bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail("Split plan changed during request", "plan_changed", code = "plan_changed"))),
                )
                is SplitInferenceOutcome.Unavailable -> LinkMessage.DelegateResponse(
                    requestId = request.requestId,
                    status = 503,
                    bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail(outcome.reason, "model_unavailable", code = "model_unavailable"))),
                )
            }
        }

        // If the caller sent a planVersion expecting a split plan that is no longer active, return 409 plan_changed
        if (request.planVersion != null) {
            return LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 409,
                bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail("Split plan is no longer active", "plan_changed", code = "plan_changed"))),
            )
        }

        val modelRequest = runCatching { buildModelRequest(chatReq) }.getOrElse { e ->
            return LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 400,
                bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail(sanitizeErrorMessage(e.message, "Invalid chat request"), "invalid_request_error", code = "invalid_request"))),
            )
        }

        // Peer execution never delegates to another peer; execute against local modelRuntime only.
        val localExecLocality = peerLocality.strictest(Locality.LOCAL_ONLY)
        if (request.stream || chatReq.stream) {
            val completionId = "chatcmpl-${UUID.randomUUID()}"
            val streamOutcome = executeManaged(chatReq.model, localExecLocality) { adapter, _ ->
                adapter.invokeStreaming(modelRequest).collect { ev ->
                    when (ev) {
                        is ModelStreamEvent.Delta -> {
                            val chunk = ChatCompletionChunk(
                                id = completionId,
                                created = System.currentTimeMillis() / 1000,
                                model = chatReq.model,
                                choices = listOf(ChunkChoice(0, ChunkDelta(content = ev.text), null)),
                            )
                            emitStream(LinkMessage.DelegateStreamEvent(request.requestId, wireJson.encodeToString(chunk), done = false))
                        }
                        is ModelStreamEvent.Done -> {
                            val finalChunk = ChatCompletionChunk(
                                id = completionId,
                                created = System.currentTimeMillis() / 1000,
                                model = chatReq.model,
                                choices = listOf(ChunkChoice(0, ChunkDelta(content = ""), ev.response.stopReason?.name?.lowercase())),
                            )
                            emitStream(LinkMessage.DelegateStreamEvent(request.requestId, wireJson.encodeToString(finalChunk), done = true))
                        }
                        is ModelStreamEvent.Failed -> {
                            emitStream(LinkMessage.DelegateStreamEvent(request.requestId, """{"type":"peer_lost","retryable":true}""", done = true))
                        }
                    }
                }
            }
            if (streamOutcome.isFailure) {
                return delegateErrorResponse(request.requestId, streamOutcome.exceptionOrNull())
            }
            return LinkMessage.DelegateResponse(request.requestId, 200, "{}")
        }

        val result = executeManaged(chatReq.model, localExecLocality) { adapter, _ ->
            adapter.invoke(modelRequest).getOrThrow()
        }
        if (result.isFailure) {
            return delegateErrorResponse(request.requestId, result.exceptionOrNull())
        }
        val chatResp = toChatCompletionResponse(result.getOrThrow(), chatReq.model)
        return LinkMessage.DelegateResponse(request.requestId, 200, wireJson.encodeToString(chatResp))
    }

    private suspend fun handleDelegatedEmbeddings(
        request: LinkMessage.DelegateRequest,
        peerLocality: Locality,
    ): LinkMessage.DelegateResponse {
        val embReq = runCatching { wireJson.decodeFromString<EmbeddingsRequest>(request.bodyJson) }.getOrNull()
            ?: return LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 400,
                bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail("Malformed embeddings request", "invalid_request_error", code = "invalid_json"))),
            )
        val localExecLocality = peerLocality.strictest(Locality.LOCAL_ONLY)
        val result = executeManaged(embReq.model, localExecLocality) { adapter, _ ->
            val embeddingAdapter = adapter as? EmbeddingModelAdapter
                ?: throw UnsupportedOperationException("Model ${embReq.model} does not support embeddings")
            embReq.input.mapIndexed { index, text ->
                val vector = embeddingAdapter.embed(text).getOrThrow()
                if (vector.isEmpty()) throw IllegalStateException("Embedding model returned an empty vector")
                Embedding(embedding = vector.toList(), index = index)
            }
        }
        if (result.isFailure) {
            return delegateErrorResponse(request.requestId, result.exceptionOrNull())
        }
        val resp = EmbeddingsResponse(data = result.getOrThrow(), model = embReq.model, usage = TokenUsage(0, 0, 0))
        return LinkMessage.DelegateResponse(request.requestId, 200, wireJson.encodeToString(resp))
    }

    private suspend fun handleDelegatedTranscriptions(
        request: LinkMessage.DelegateRequest,
        peerLocality: Locality,
    ): LinkMessage.DelegateResponse {
        val sttReq = runCatching { wireJson.decodeFromString<TranscriptionRequest>(request.bodyJson) }.getOrNull()
            ?: return LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 400,
                bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail("Malformed transcription request", "invalid_request_error", code = "invalid_json"))),
            )
        val audioBytes = runCatching { Base64.getDecoder().decode(sttReq.file) }.getOrNull()
            ?: return LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 400,
                bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail("Invalid base64 audio", "invalid_request_error", code = "invalid_request"))),
            )
        val localExecLocality = peerLocality.strictest(Locality.LOCAL_ONLY)
        val result = executeManaged(sttReq.model, localExecLocality) { adapter, _ ->
            if (adapter is SttModelAdapter) {
                adapter.transcribe(SttRequest(audioBytes, sttReq.language, sttReq.prompt)).getOrThrow().text
            } else {
                val modelRequest = ModelRequest(
                    messages = listOf(
                        Turn.System(sttReq.prompt ?: "Transcribe audio"),
                        Turn.User(listOf(ContentBlock.Audio("audio/wav", audioBytes)), TrustLevel.TRUSTED)
                    ),
                    tools = emptyList(),
                    toolChoice = ToolChoice.None,
                    maxOutputTokens = 1000.coerceAtMost(policy.maxOutputTokensLimit),
                    stopConditions = emptyList()
                )
                adapter.invoke(modelRequest).getOrThrow().outputs.filterIsInstance<TextOutput>().joinToString("") { it.text }
            }
        }
        if (result.isFailure) {
            return delegateErrorResponse(request.requestId, result.exceptionOrNull())
        }
        return LinkMessage.DelegateResponse(
            requestId = request.requestId,
            status = 200,
            bodyJson = wireJson.encodeToString(TranscriptionResponse(text = result.getOrThrow())),
        )
    }

    private suspend fun handleDelegatedDecisions(
        request: LinkMessage.DelegateRequest,
        peerLocality: Locality,
    ): LinkMessage.DelegateResponse {
        val rawObj = runCatching { wireJson.decodeFromString<JsonObject>(request.bodyJson) }.getOrNull()
            ?: return LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 400,
                bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail("Malformed decision request", "invalid_request_error", code = "invalid_json"))),
            )
        val parsed = runCatching { DecisionWire.parseRequest(rawObj) }.getOrElse { e ->
            return LinkMessage.DelegateResponse(
                requestId = request.requestId,
                status = 400,
                bodyJson = wireJson.encodeToString(ErrorResponse(ErrorDetail(sanitizeErrorMessage(e.message, "invalid request"), "invalid_request_error", code = "invalid_request"))),
            )
        }
        val localExecLocality = peerLocality.strictest(Locality.LOCAL_ONLY)
        val outcome = executeManaged(parsed.model, localExecLocality) { adapter, _ ->
            val decisionAdapter = adapter as? DecisionModelAdapter
                ?: throw UnsupportedOperationException("Model ${parsed.model} is not a decision model")
            decisionAdapter.decide(parsed.request).getOrThrow()
        }
        if (outcome.isFailure) {
            return delegateErrorResponse(request.requestId, outcome.exceptionOrNull())
        }
        return LinkMessage.DelegateResponse(
            requestId = request.requestId,
            status = 200,
            bodyJson = DecisionWire.renderResult(outcome.getOrThrow()).toString(),
        )
    }

    private fun delegateErrorResponse(requestId: String, error: Throwable?): LinkMessage.DelegateResponse {
        // RFC-0105: When a peer's own queue is full or model is busy, refuse delegated requests with 503 peer_busy
        if (error is EngineBusyException || error is EngineModelBusyException || error is PeerBusyException) {
            return LinkMessage.DelegateResponse(
                requestId = requestId,
                status = 503,
                bodyJson = wireJson.encodeToString(
                    ErrorResponse(ErrorDetail("Peer is busy; do not queue behind local work", "peer_busy", code = "peer_busy"))
                ),
            )
        }
        val (status, detail) = classifyError(error)
        return LinkMessage.DelegateResponse(
            requestId = requestId,
            status = status.value,
            bodyJson = wireJson.encodeToString(ErrorResponse(detail)),
        )
    }

    private suspend fun respondClassifiedError(
        call: ApplicationCall,
        error: Throwable?,
        sensitivePrompts: Collection<String> = emptyList(),
    ) {
        val (status, detail) = classifyError(error, sensitivePrompts)
        call.respond(status, ErrorResponse(detail))
    }

    internal fun classifyError(
        error: Throwable?,
        sensitivePrompts: Collection<String> = emptyList(),
    ): Pair<HttpStatusCode, ErrorDetail> {
        val rawMessage = error?.message ?: "Inference failed"
        return when {
            error is LocalityViolationException -> HttpStatusCode.Forbidden to
                ErrorDetail(
                    sanitizeErrorMessage(rawMessage, "Locality constraint violated", sensitivePrompts),
                    "locality_violation",
                    code = "placement_${error.placement.wire}",
                    param = "locality"
                )
            error is ModelUnavailableException -> HttpStatusCode.ServiceUnavailable to
                ErrorDetail(
                    sanitizeErrorMessage(rawMessage, "No candidate model is currently available", sensitivePrompts),
                    "model_unavailable",
                    code = "model_unavailable",
                    param = "model"
                )
            error is PeerBusyException -> HttpStatusCode.ServiceUnavailable to
                ErrorDetail(
                    sanitizeErrorMessage(rawMessage, "Peer is busy; retry later", sensitivePrompts),
                    "peer_busy",
                    code = "peer_busy"
                )
            error is PlanChangedException -> HttpStatusCode.Conflict to
                ErrorDetail(
                    sanitizeErrorMessage(rawMessage, "Split plan changed; retry request", sensitivePrompts),
                    "plan_changed",
                    code = "plan_changed"
                )
            error is EngineRequestTimeoutException || error is TimeoutCancellationException ->
                HttpStatusCode.GatewayTimeout to ErrorDetail(
                    "Inference request timed out",
                    "timeout_error",
                    code = "request_timeout"
                )
            error is CancellationException ->
                HttpStatusCode.Conflict to ErrorDetail(
                    "Inference request was cancelled",
                    "cancelled_error",
                    code = "request_cancelled"
                )
            error is EngineBusyException || rawMessage.contains("ENGINE_BUSY") ->
                HttpStatusCode.ServiceUnavailable to ErrorDetail(
                    "Engine is busy; retry later",
                    "engine_busy",
                    code = "queue_saturated"
                )
            error is EngineShuttingDownException ->
                HttpStatusCode.ServiceUnavailable to ErrorDetail(
                    "Engine is shutting down",
                    "engine_stopping",
                    code = "shutdown"
                )
            error is EngineModelBusyException ->
                HttpStatusCode.Conflict to ErrorDetail(
                    "Model is busy; wait for inference to finish",
                    "model_busy",
                    code = "model_busy"
                )
            rawMessage.contains("INSUFFICIENT_MEMORY", ignoreCase = true) ||
                rawMessage.contains("out of memory", ignoreCase = true) ||
                error is OutOfMemoryError ->
                HttpStatusCode.ServiceUnavailable to ErrorDetail(
                    "Insufficient memory to load or run model",
                    "resource_exhausted",
                    code = "insufficient_memory"
                )
            rawMessage.contains("MODEL_NOT_INSTALLED") || rawMessage.contains("not installed", ignoreCase = true) ->
                HttpStatusCode.NotFound to ErrorDetail(
                    "Model is not installed",
                    "model_error",
                    code = "model_not_installed"
                )
            rawMessage.contains("MODEL_INTEGRITY_MISSING") ->
                HttpStatusCode.UnprocessableEntity to ErrorDetail(
                    "Model integrity metadata is missing",
                    "model_error",
                    code = "model_integrity_missing"
                )
            rawMessage.contains("MODEL_INTEGRITY_MISMATCH") || rawMessage.contains("Digest mismatch", ignoreCase = true) ->
                HttpStatusCode.UnprocessableEntity to ErrorDetail(
                    "Model integrity verification failed",
                    "model_error",
                    code = "model_integrity_mismatch"
                )
            error is UnsupportedOperationException ||
                rawMessage.contains("does not support", ignoreCase = true) ||
                rawMessage.contains("is not a decision model", ignoreCase = true) ->
                HttpStatusCode.BadRequest to ErrorDetail(
                    sanitizeErrorMessage(rawMessage, "Model does not support requested capability", sensitivePrompts),
                    "invalid_request_error",
                    code = "unsupported_capability",
                    param = "model"
                )
            rawMessage.contains("INVALID_GGUF") || rawMessage.contains("unsupported", ignoreCase = true) ->
                HttpStatusCode.BadRequest to ErrorDetail(
                    "Model file is invalid or unsupported",
                    "model_error",
                    code = "invalid_model"
                )
            rawMessage.contains("INCOMPATIBLE_GGUF") || rawMessage.contains("incompatible", ignoreCase = true) ->
                HttpStatusCode.UnprocessableEntity to ErrorDetail(
                    "Model is incompatible with current runtime",
                    "model_error",
                    code = "incompatible_model"
                )
            rawMessage.contains("MODEL_LOAD_FAILED") || rawMessage.contains("failed to load model", ignoreCase = true) ->
                HttpStatusCode.InternalServerError to ErrorDetail(
                    "Failed to load model",
                    "model_error",
                    code = "model_load_failed"
                )
            error is UnsatisfiedLinkError ||
                rawMessage.contains("JNI", ignoreCase = true) ||
                rawMessage.contains("llama_", ignoreCase = true) ||
                rawMessage.contains("ggml_", ignoreCase = true) ||
                rawMessage.contains("libjllama", ignoreCase = true) ||
                rawMessage.contains("SIGSEGV", ignoreCase = true) ->
                HttpStatusCode.InternalServerError to ErrorDetail(
                    "Native inference runtime failure",
                    "native_error",
                    code = "native_failure"
                )
            else -> HttpStatusCode.InternalServerError to ErrorDetail(
                sanitizeErrorMessage(rawMessage, "Inference failed", sensitivePrompts),
                "inference_error",
                code = "inference_failed"
            )
        }
    }
}

private val UNIX_PATH_REGEX = Regex("""(?<![A-Za-z0-9])/(?:data|storage|sdcard|home|Users|tmp|var|private|proc|sys|etc|opt|mnt|dev|usr|lib|system|apex)(?:/[^\s"':;,)]*)+""")
private val MULTI_SEGMENT_UNIX_PATH_REGEX = Regex("""(?<![A-Za-z0-9:])/[A-Za-z0-9._-]+/[A-Za-z0-9._/-]+""")
private val WINDOWS_PATH_REGEX = Regex("""[A-Za-z]:[\\/][^\s"':;,)]*""")
private val UNC_PATH_REGEX = Regex("""\\\\[A-Za-z0-9._-]+\\[^\s"':;,)]*""")
private val ARTIFACT_FILE_REGEX = Regex("""\b[^\s"':;,()\\/]+\.(?:gguf|onnx|partial|db|sqlite|so|dll|dylib|bin)\b""", RegexOption.IGNORE_CASE)
private val STACK_TRACE_MARKER_REGEX = Regex("""(\r?\n\s*at\s+[\w.$]+|\bCaused by:|\bSuppressed:|\[0x[0-9a-fA-F]+\]|0x[0-9a-fA-F]{6,})""")
private val EXCEPTION_CLASS_PREFIX_REGEX = Regex("""^(?:[a-z_][a-z0-9_]*\.)+[A-Z][A-Za-z0-9_$]*(?:Exception|Error):\s*""")
private val SECRET_TOKEN_REGEX = Regex("""\b(?:hf_[A-Za-z0-9_]{6,}|sk-[A-Za-z0-9_-]{6,}|Bearer\s+[A-Za-z0-9._\-]+)\b""")
private val PROMPT_LEAK_MARKER_REGEX = Regex("""\b(?:prompt|messages|secret|api_key|apikey|password|authorization)\s*[=:]""", RegexOption.IGNORE_CASE)

/**
 * Scrubs exception messages before they cross the HTTP boundary so filesystem paths, stack traces,
 * native pointers/symbols, credentials, and prompt contents never leak to callers (PIPELINE.md §2.7).
 */
internal fun sanitizeErrorMessage(
    raw: String?,
    fallback: String = "Inference failed",
    sensitivePrompts: Collection<String> = emptyList(),
): String {
    if (raw.isNullOrBlank()) return fallback

    // Take only the first line prior to any stack trace frames.
    var cleaned = raw.replace(STACK_TRACE_MARKER_REGEX, "")
        .lineSequence()
        .firstOrNull()
        ?.trim()
        .orEmpty()

    cleaned = cleaned.replace(EXCEPTION_CLASS_PREFIX_REGEX, "").trim()
    if (cleaned.isEmpty()) return fallback

    // Never echo caller prompts or sensitive inputs back in error messages.
    for (prompt in sensitivePrompts) {
        val trimmed = prompt.trim()
        if (trimmed.length >= 4 && cleaned.contains(trimmed, ignoreCase = true)) {
            return fallback
        }
    }
    if (PROMPT_LEAK_MARKER_REGEX.containsMatchIn(cleaned)) {
        return fallback
    }

    // Redact secrets, filesystem paths, and artifact file names.
    cleaned = cleaned
        .replace(SECRET_TOKEN_REGEX, "[REDACTED]")
        .replace(WINDOWS_PATH_REGEX, "[REDACTED_PATH]")
        .replace(UNC_PATH_REGEX, "[REDACTED_PATH]")
        .replace(UNIX_PATH_REGEX, "[REDACTED_PATH]")
        .replace(MULTI_SEGMENT_UNIX_PATH_REGEX, "[REDACTED_PATH]")
        .replace(ARTIFACT_FILE_REGEX, "[REDACTED_FILE]")

    // If the message was mostly a path/trace or is excessively long, return the clean fallback.
    if (cleaned.length > 200 || cleaned == "[REDACTED_PATH]" || cleaned == "[REDACTED_FILE]") {
        return fallback
    }
    return cleaned
}

/** An `images` entry of a chat message: base64, optionally as a `data:image/...;base64,` URL. */
internal fun decodeImage(entry: String): ContentBlock.Image {
    val dataUrl = Regex("^data:([^;,]+);base64,", RegexOption.IGNORE_CASE).find(entry)
    val bytes = try {
        Base64.getMimeDecoder().decode(entry.removePrefix(dataUrl?.value ?: ""))
    } catch (_: IllegalArgumentException) {
        throw IllegalArgumentException("images[] entries must be base64-encoded image bytes or data: URLs")
    }
    require(bytes.isNotEmpty()) { "images[] entry is empty" }
    val mime = dataUrl?.groupValues?.get(1) ?: when {
        bytes.size > 3 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() -> "image/png"
        bytes.size > 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() -> "image/jpeg"
        else -> "application/octet-stream"
    }
    return ContentBlock.Image(mime, bytes)
}
