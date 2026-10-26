package fi.italeino.aidos.engine.http

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
import fi.italeino.aidos.engine.inference.InferenceRequestManager
import fi.italeino.aidos.engine.inference.LOCALITY_HEADER
import fi.italeino.aidos.engine.inference.Locality
import fi.italeino.aidos.engine.inference.LocalityViolationException
import fi.italeino.aidos.engine.inference.PLACEMENT_HEADER
import fi.italeino.aidos.engine.inference.Placement
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
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.*
import kotlin.time.Duration.Companion.milliseconds

/** An SDK version is a short build string; anything longer is not one, and is cut before it is stored. */
private const val MAX_SDK_VERSION_LENGTH = 64

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
    private val onClientVersions: (suspend (subject: String, sdkVersion: String?, apiVersion: Int?) -> Unit)? = null
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
                post("/v1/chat/completions") { handleChatCompletions(call) }
                post("/v1/embeddings") { handleEmbeddings(call) }
                post("/v1/audio/transcriptions") { handleTranscriptions(call) }
                // Typed decisions (RFC-0022 D45). /v1/systemone is the Jev-compatible path; /v1/decisions
                // is OpenJev's alias for the same handler.
                post("/v1/systemone") { handleDecisions(call) }
                post("/v1/decisions") { handleDecisions(call) }
                extraRoutes?.invoke(this)
            }
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
                    ErrorResponse(ErrorDetail("$LOCALITY_HEADER must be local_only, trusted_cluster or any", "invalid_request_error", param = "locality"))
                )
                return null
            }
        } ?: defaultLocality()
        val subject = call.principal<UserIdPrincipal>()?.name
        val forSubject = if (subject == null || subject == TokenManager.ENGINE_SUBJECT) null else subjectLocality?.invoke(subject)
        return listOfNotNull(requested, forSubject, engineLocality()).reduce(Locality::strictest)
    }

    private suspend fun handleChatCompletions(call: ApplicationCall) {
        val locality = resolveLocality(call) ?: return
        try {
            val request = call.receive<ChatCompletionRequest>()
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
                val streamResult = inferenceManager.execute(request.model, locality) { adapter, placement ->
                    call.response.header(PLACEMENT_HEADER, placement.wire)
                    streamChatCompletions(call, adapter, modelRequest, request.model)
                }
                if (streamResult.isFailure) {
                    respondClassifiedError(call, streamResult.exceptionOrNull())
                }
                return
            }

            val inferenceResult = inferenceManager.execute(request.model, locality) { adapter, placement ->
                call.response.header(PLACEMENT_HEADER, placement.wire)
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
        ModelKind.DECISION -> listOf("decisions")
    }

    private suspend fun handleEmbeddings(call: ApplicationCall) {
        val locality = resolveLocality(call) ?: return
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

            val embeddingsResult = inferenceManager.execute(request.model, locality) { adapter, placement ->
                call.response.header(PLACEMENT_HEADER, placement.wire)
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

    private suspend fun handleDecisions(call: ApplicationCall) {
        val locality = resolveLocality(call) ?: return
        val parsed = try {
            DecisionWire.parseRequest(call.receive<JsonObject>())
        } catch (e: DecisionRequestException) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(e.message ?: "invalid request", "invalid_request_error")))
            return
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail("request body must be a JSON object", "invalid_request_error")))
            return
        }
        val outcome = inferenceManager.execute(parsed.model, locality) { adapter, placement ->
            call.response.header(PLACEMENT_HEADER, placement.wire)
            val decisionAdapter = adapter as? DecisionModelAdapter
                ?: throw UnsupportedOperationException("Model ${parsed.model} is not a decision model")
            decisionAdapter.decide(parsed.request).getOrThrow()
        }
        val error = outcome.exceptionOrNull()
        when {
            error is DecisionRequestException ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(error.message ?: "invalid request", "invalid_request_error")))
            error is UnsupportedOperationException ->
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(error.message ?: "unsupported", "invalid_request_error", param = "model")))
            error != null -> respondClassifiedError(call, error)
            else -> call.respondText(
                DecisionWire.renderResult(outcome.getOrThrow()).toString(),
                ContentType.Application.Json,
            )
        }
    }

    private suspend fun handleTranscriptions(call: ApplicationCall) {
        val locality = resolveLocality(call) ?: return
        try {
            val request = call.receive<TranscriptionRequest>()
            val audioBytes = Base64.getDecoder().decode(request.file)
            val result = inferenceManager.execute(request.model, locality) { adapter, placement ->
                call.response.header(PLACEMENT_HEADER, placement.wire)
                // A speech-to-text model (Whisper) transcribes directly. Any other model keeps the older
                // path of handing the audio to the model as a chat turn (an audio-capable LLM, once one exists).
                if (adapter is SttModelAdapter) {
                    val stt = adapter.transcribe(SttRequest(audioBytes, request.language, request.prompt)).getOrThrow()
                    return@execute stt.text
                }
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
                adapter.invoke(modelRequest).getOrThrow().outputs.filterIsInstance<TextOutput>().joinToString("") { it.text }
            }
            val failure = result.exceptionOrNull()
            if (failure is SttRequestException) {
                call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(failure.message ?: "invalid audio", "invalid_request_error", param = "file")))
                return
            }
            if (result.isFailure) {
                respondClassifiedError(call, failure)
                return
            }
            call.respond(TranscriptionResponse(text = result.getOrThrow()))
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
            error is LocalityViolationException -> HttpStatusCode.Forbidden to
                ErrorDetail(message, "locality_violation", code = "placement_${error.placement.wire}", param = "locality")
            error is EngineBusyException -> HttpStatusCode.ServiceUnavailable to
                ErrorDetail("Engine is busy; retry later", "engine_busy", code = "queue_saturated")
            error is EngineShuttingDownException -> HttpStatusCode.ServiceUnavailable to
                ErrorDetail("Engine is shutting down", "engine_stopping", code = "shutdown")
            error is EngineModelBusyException -> HttpStatusCode.Conflict to
                ErrorDetail("Model is busy; wait for inference to finish", "model_busy", code = "model_busy")
            message.contains("MODEL_NOT_INSTALLED") || message.contains("not installed", ignoreCase = true) ->
                HttpStatusCode.NotFound to ErrorDetail("Model is not installed", "model_error", code = "model_not_installed")
            message.contains("MODEL_INTEGRITY_MISSING") ->
                HttpStatusCode.UnprocessableEntity to ErrorDetail("Model integrity metadata is missing", "model_error", code = "model_integrity_missing")
            message.contains("MODEL_INTEGRITY_MISMATCH") ->
                HttpStatusCode.UnprocessableEntity to ErrorDetail("Model integrity verification failed", "model_error", code = "model_integrity_mismatch")
            message.contains("INVALID_GGUF") || message.contains("unsupported", ignoreCase = true) ->
                HttpStatusCode.BadRequest to ErrorDetail("Model file is invalid or unsupported", "model_error", code = "invalid_model")
            message.contains("INCOMPATIBLE_GGUF") || message.contains("incompatible", ignoreCase = true) ->
                HttpStatusCode.UnprocessableEntity to ErrorDetail("Model is incompatible with current runtime", "model_error", code = "incompatible_model")
            else -> HttpStatusCode.InternalServerError to ErrorDetail(message, "inference_error")
        }
    }
}

/** An `images` entry of a chat message: base64, optionally as a `data:image/...;base64,` URL. */
internal fun decodeImage(entry: String): ContentBlock.Image {
    val dataUrl = Regex("^data:([^;,]+);base64,", RegexOption.IGNORE_CASE).find(entry)
    val bytes = try {
        Base64.getMimeDecoder().decode(entry.removePrefix(dataUrl?.value ?: ""))
    } catch (e: IllegalArgumentException) {
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
