package fi.italeino.aidos.engine.http

import dev.aidos.kernel.*
import dev.aidos.kernel.ToolCall as KernelToolCall
import fi.italeino.aidos.engine.inference.EngineBusyException
import fi.italeino.aidos.engine.inference.EngineShuttingDownException
import fi.italeino.aidos.engine.inference.InferenceRequestManager
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/**
 * Ktor HTTP server for Aidos Engine local inference (RFC-0103).
 */
class EngineHttpServer(
    private val tokenManager: TokenManager,
    private val modelRuntime: ModelRuntime,
    private val inferenceManager: InferenceRequestManager = InferenceRequestManager(modelRuntime),
    private val port: Int = 0
) {
    private var server: EmbeddedServer<*, *>? = null

    suspend fun getBoundPort(): Int? = server?.engine?.resolvedConnectors()?.firstOrNull()?.port

    fun start() {
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

    /**
     * Installs content negotiation, bearer auth, and routing onto [application] — factored out
     * of [start] so tests can mount the real handlers via Ktor's `testApplication` instead of
     * duplicating routing/auth setup per test (as the pre-S4 test suite did, which meant it never
     * actually exercised this class's own code).
     */
    internal fun installInto(application: Application) {
        with(application) {
            setupContentNegotiation()
            setupAuthentication()
            setupRouting()
        }
    }

    private fun Application.setupContentNegotiation() {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = false
                encodeDefaults = true
                isLenient = true
            })
        }
    }

    private fun Application.setupAuthentication() {
        install(Authentication) {
            bearer("bearerAuth") {
                authenticate { tokenCredential ->
                    val token = tokenManager.validateToken(tokenCredential.token)
                    if (token != null) UserIdPrincipal(token) else null
                }
            }
        }
    }

    private fun Application.setupRouting() {
        routing {
            get("/health") { call.respond(mapOf("status" to "ok")) }
            authenticate("bearerAuth") {
                get("/v1/models") { handleModels(call) }
                post("/v1/chat/completions") { handleChatCompletions(call) }
                post("/v1/embeddings") { handleEmbeddings(call) }
                post("/v1/audio/transcriptions") { handleTranscriptions(call) }
            }
        }
    }

    private suspend fun handleChatCompletions(call: ApplicationCall) {
        try {
            val request = call.receive<ChatCompletionRequest>()
            val turns = request.messages.map { msg ->
                when (msg.role) {
                    "system" -> Turn.System(msg.content ?: "")
                    "user" -> Turn.User(listOf(ContentBlock.Text(msg.content ?: "")), TrustLevel.TRUSTED)
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

            val modelRequest = ModelRequest(
                messages = turns,
                tools = tools,
                toolChoice = when (request.tool_choice) {
                    "none" -> ToolChoice.None
                    "required" -> ToolChoice.Required
                    "auto" -> ToolChoice.Auto
                    null -> if (tools.isNotEmpty()) ToolChoice.Auto else ToolChoice.None
                    else -> ToolChoice.Auto
                },
                maxOutputTokens = request.max_tokens ?: 2048,
                stopConditions = emptyList()
            )

            if (request.stream) {
                val streamResult = inferenceManager.execute(request.model) { adapter ->
                    streamChatCompletions(call, adapter, modelRequest, request.model)
                }
                if (streamResult.isFailure) {
                    respondClassifiedError(call, streamResult.exceptionOrNull())
                }
                return
            }

            val inferenceResult = inferenceManager.execute(request.model) { adapter ->
                adapter.invoke(modelRequest).getOrThrow()
            }
            if (inferenceResult.isFailure) {
                respondClassifiedError(call, inferenceResult.exceptionOrNull())
                return
            }

            val response = inferenceResult.getOrThrow()
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
            val chatResponse = ChatCompletionResponse(
                id = "chatcmpl-${UUID.randomUUID()}",
                created = System.currentTimeMillis() / 1000,
                model = request.model,
                choices = listOf(Choice(0, message, response.stopReason?.name?.lowercase())),
                usage = TokenUsage(
                    prompt_tokens = usage?.inputTokens ?: 0,
                    completion_tokens = usage?.outputTokens ?: 0,
                    total_tokens = usage?.totalTokens ?: 0
                )
            )
            call.respond(chatResponse)
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(e.message ?: "Unknown error", "invalid_request_error")))
        }
    }

    /**
     * Streams real per-token deltas from [ModelAdapter.invokeStreaming] as SSE frames (RFC-0021
     * "Streaming"; Dictator plan S4) — unlike the previous implementation, which called the
     * non-streaming [ModelAdapter.invoke], waited for the complete response, and only then chopped
     * it into fake chunks. Time-to-first-token now reflects real generation, not full-response
     * latency.
     */
    private suspend fun streamChatCompletions(
        call: ApplicationCall,
        adapter: ModelAdapter,
        modelRequest: ModelRequest,
        modelId: String
    ) {
        val completionId = "chatcmpl-${UUID.randomUUID()}"

        call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Connection, "keep-alive")

        call.respondBytesWriter(status = HttpStatusCode.OK) {
            adapter.invokeStreaming(modelRequest).collect { event ->
                when (event) {
                    is ModelStreamEvent.Delta -> {
                        val chunk = ChatCompletionChunk(
                            id = completionId,
                            created = System.currentTimeMillis() / 1000,
                            model = modelId,
                            choices = listOf(ChunkChoice(0, ChunkDelta(content = event.text), null))
                        )
                        writeStringUtf8("data: ${Json.encodeToString(chunk)}\n\n")
                        flush()
                    }
                    is ModelStreamEvent.Done -> {
                        val finalChunk = ChatCompletionChunk(
                            id = completionId,
                            created = System.currentTimeMillis() / 1000,
                            model = modelId,
                            choices = listOf(ChunkChoice(0, ChunkDelta(content = ""), event.response.stopReason?.name?.lowercase()))
                        )
                        writeStringUtf8("data: ${Json.encodeToString(finalChunk)}\n\n")
                    }
                    is ModelStreamEvent.Failed -> {
                        writeStringUtf8(
                            "data: ${Json.encodeToString(ErrorResponse(ErrorDetail(event.error.message ?: "Inference failed", "inference_error")))}\n\n"
                        )
                    }
                }
            }
            writeStringUtf8("data: [DONE]\n\n")
            flush()
        }
    }

    private fun parseJsonObject(jsonString: String): JsonObject {
        return try {
            Json.parseToJsonElement(jsonString) as JsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }
    }

    private suspend fun handleModels(call: ApplicationCall) {
        try {
            val catalogById = modelRuntime.catalog().associateBy { it.id }
            val installedById = modelRuntime.installed().associateBy { it.id }
            val loadedIds = modelRuntime.loaded().toSet()
            val allModelIds = (catalogById.keys + installedById.keys).sorted()

            val models = allModelIds.map { modelId ->
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
                    format = if (model.isLocal) "gguf" else null,
                    size_bytes = installedModel?.sizeBytes ?: catalogModel?.sizeBytes,
                    quantization = deriveQuantization(model.id),
                    installed = installedModel != null,
                    loaded = loadedIds.contains(model.id),
                    metadata = buildMap {
                        model.digest?.let { put("digest", it) }
                        put("is_local", model.isLocal.toString())
                        put("provider_id", model.providerId)
                    },
                )
            }

            call.respond(ModelsResponse(data = models))
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(ErrorDetail(e.message ?: "Failed to list models", "internal_error"))
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
    }

    private fun deriveQuantization(modelId: String): String? {
        val lowered = modelId.lowercase()
        return when {
            lowered.contains("q2_k") -> "q2_k"
            lowered.contains("q3_k") -> "q3_k"
            lowered.contains("q4_k_m") -> "q4_k_m"
            lowered.contains("q4_k_s") -> "q4_k_s"
            lowered.contains("q4_0") -> "q4_0"
            lowered.contains("q5_k_m") -> "q5_k_m"
            lowered.contains("q5_k_s") -> "q5_k_s"
            lowered.contains("q6_k") -> "q6_k"
            lowered.contains("q8_0") -> "q8_0"
            else -> null
        }
    }

    private suspend fun handleEmbeddings(call: ApplicationCall) {
        try {
            val request = call.receive<EmbeddingsRequest>()
            if (request.input.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("input must contain at least one string", "invalid_request_error", param = "input"))
                )
                return
            }
            if (request.encoding_format != null && request.encoding_format != "float") {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(ErrorDetail("only float embeddings are supported", "invalid_request_error", param = "encoding_format"))
                )
                return
            }

            val embeddingsResult = inferenceManager.execute(request.model) { adapter ->
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
                respondClassifiedError(call, embeddingsResult.exceptionOrNull())
                return
            }

            val embeddings = embeddingsResult.getOrThrow()
            val dimension = embeddings.firstOrNull()?.embedding?.size ?: 0
            if (dimension == 0 || embeddings.any { it.embedding.size != dimension }) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(ErrorDetail("Embedding model returned inconsistent vector dimensions", "embedding_error"))
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
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(e.message ?: "Unknown error", "invalid_request_error")))
        }
    }

    private suspend fun handleTranscriptions(call: ApplicationCall) {
        try {
            val request = call.receive<TranscriptionRequest>()
            val audioBytes = Base64.getDecoder().decode(request.file)
            val result = inferenceManager.execute(request.model) { adapter ->
                val modelRequest = ModelRequest(
                    messages = listOf(
                        Turn.System(request.prompt ?: "Transcribe audio"),
                        Turn.User(listOf(ContentBlock.Audio("audio/wav", audioBytes)), TrustLevel.TRUSTED)
                    ),
                    tools = emptyList(),
                    toolChoice = ToolChoice.None,
                    maxOutputTokens = 1000,
                    stopConditions = emptyList()
                )
                adapter.invoke(modelRequest).getOrThrow()
            }
            if (result.isFailure) {
                respondClassifiedError(call, result.exceptionOrNull())
                return
            }
            val text = result.getOrThrow().outputs.filterIsInstance<TextOutput>().joinToString("") { it.text }
            call.respond(TranscriptionResponse(text = text))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(e.message ?: "Unknown error", "invalid_request_error")))
        }
    }

    private suspend fun respondClassifiedError(call: ApplicationCall, error: Throwable?) {
        val (status, detail) = classifyError(error)
        call.respond(status, ErrorResponse(detail))
    }

    private fun classifyError(error: Throwable?): Pair<HttpStatusCode, ErrorDetail> {
        val message = error?.message ?: "Inference failed"
        return when {
            error is EngineBusyException -> HttpStatusCode.ServiceUnavailable to
                ErrorDetail("Engine is busy; retry later", "engine_busy", code = "queue_saturated")
            error is EngineShuttingDownException -> HttpStatusCode.ServiceUnavailable to
                ErrorDetail("Engine is shutting down", "engine_stopping", code = "shutdown")
            message.contains("MODEL_NOT_INSTALLED") || message.contains("not installed", ignoreCase = true) ->
                HttpStatusCode.NotFound to ErrorDetail("Model is not installed", "model_error", code = "model_not_installed")
            message.contains("INVALID_GGUF") || message.contains("unsupported", ignoreCase = true) ->
                HttpStatusCode.BadRequest to ErrorDetail("Model file is invalid or unsupported", "model_error", code = "invalid_model")
            message.contains("INCOMPATIBLE_GGUF") || message.contains("incompatible", ignoreCase = true) ->
                HttpStatusCode.UnprocessableEntity to ErrorDetail("Model is incompatible with current runtime", "model_error", code = "incompatible_model")
            message.contains("MODEL_LOAD_FAILED") ->
                HttpStatusCode.InternalServerError to ErrorDetail("Model failed to load", "model_error", code = "model_load_failed")
            else -> HttpStatusCode.InternalServerError to ErrorDetail("Inference failed", "inference_error")
        }
    }
}
