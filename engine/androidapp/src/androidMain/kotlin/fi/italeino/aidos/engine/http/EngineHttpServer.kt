package fi.italeino.aidos.engine.http

import dev.aidos.kernel.*
import dev.aidos.kernel.ToolCall as KernelToolCall
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

/**
 * Ktor HTTP server for Aidos Engine local inference (RFC-0103).
 */
class EngineHttpServer(
    private val tokenManager: TokenManager,
    private val modelRuntime: ModelRuntime,
    private val port: Int = 0
) {
    private var server: EmbeddedServer<*, *>? = null

    suspend fun getBoundPort(): Int? = server?.engine?.resolvedConnectors()?.firstOrNull()?.port

    fun start() {
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            setupContentNegotiation()
            setupAuthentication()
            setupRouting()
        }.start(wait = false)
    }

    fun stop() {
        server?.stop(1000, 2000)
        server = null
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
                post("/v1/chat/completions") { handleChatCompletions(call) }
                post("/v1/embeddings") { handleEmbeddings(call) }
                post("/v1/audio/transcriptions") { handleTranscriptions(call) }
            }
        }
    }

    private suspend fun handleChatCompletions(call: ApplicationCall) {
        try {
            val request = call.receive<ChatCompletionRequest>()
            val modelResult = modelRuntime.load(request.model)
            if (modelResult.isFailure) {
                val error = modelResult.exceptionOrNull()
                val statusCode = if (error?.message?.contains("not installed") == true) HttpStatusCode.NotFound else HttpStatusCode.InternalServerError
                call.respond(statusCode, ErrorResponse(ErrorDetail(error?.message ?: "Failed to load model", "model_error")))
                return
            }

            val adapter = modelResult.getOrThrow()
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

            val inferenceResult = adapter.invoke(modelRequest)
            if (inferenceResult.isFailure) {
                call.respond(HttpStatusCode.InternalServerError, ErrorResponse(ErrorDetail(inferenceResult.exceptionOrNull()?.message ?: "Inference failed", "inference_error")))
                return
            }

            val response = inferenceResult.getOrThrow()

            if (request.stream) {
                streamChatCompletions(call, response, request.model)
            } else {
                val message = ChatMessage(
                    role = "assistant",
                    content = response.text ?: "",
                    tool_calls = response.toolCalls.map { tc ->
                        ToolCall(
                            id = tc.callId,
                            function = ToolFunctionCall(tc.toolName, tc.arguments.toString())
                        )
                    }
                )

                val chatResponse = ChatCompletionResponse(
                    id = "chatcmpl-${UUID.randomUUID()}",
                    created = System.currentTimeMillis() / 1000,
                    model = request.model,
                    choices = listOf(Choice(0, message, response.stopReason.name.lowercase())),
                    usage = TokenUsage(
                        prompt_tokens = response.usage.inputTokens,
                        completion_tokens = response.usage.outputTokens,
                        total_tokens = response.usage.inputTokens + response.usage.outputTokens
                    )
                )
                call.respond(chatResponse)
            }
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(e.message ?: "Unknown error", "invalid_request_error")))
        }
    }

    private suspend fun streamChatCompletions(call: ApplicationCall, response: ModelResponse, modelId: String) {
        val completionId = "chatcmpl-${UUID.randomUUID()}"
        val responseText = response.text ?: ""

        call.response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header(HttpHeaders.Connection, "keep-alive")

        call.respondBytesWriter(status = HttpStatusCode.OK) {
            val tokens = responseText.split(Regex("(?<=\\s)|(?=\\s)")).filter { it.isNotEmpty() }
            for (token in tokens) {
                val chunk = ChatCompletionChunk(
                    id = completionId,
                    created = System.currentTimeMillis() / 1000,
                    model = modelId,
                    choices = listOf(ChunkChoice(0, ChunkDelta(content = token), null))
                )
                writeStringUtf8("data: ${Json.encodeToString(chunk)}\n\n")
                flush()
            }
            val finalChunk = ChatCompletionChunk(
                id = completionId,
                created = System.currentTimeMillis() / 1000,
                model = modelId,
                choices = listOf(ChunkChoice(0, ChunkDelta(content = ""), response.stopReason.name.lowercase()))
            )
            writeStringUtf8("data: ${Json.encodeToString(finalChunk)}\n\n")
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

    private suspend fun handleEmbeddings(call: ApplicationCall) {
        try {
            val request = call.receive<EmbeddingsRequest>()
            val modelResult = modelRuntime.load(request.model)
            val adapter = modelResult.getOrThrow()

            val embeddings = request.input.mapIndexed { index, text ->
                val modelRequest = ModelRequest(
                    messages = listOf(Turn.User(listOf(ContentBlock.Text(text)), TrustLevel.TRUSTED)),
                    tools = emptyList(),
                    toolChoice = ToolChoice.None,
                    maxOutputTokens = 0,
                    stopConditions = emptyList()
                )
                val result = adapter.invoke(modelRequest).getOrThrow()
                Embedding(embedding = listOf(0.0f), index = index)
            }
            call.respond(EmbeddingsResponse(data = embeddings, model = request.model, usage = TokenUsage(0, 0, 0)))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(e.message ?: "Unknown error", "invalid_request_error")))
        }
    }

    private suspend fun handleTranscriptions(call: ApplicationCall) {
        try {
            val request = call.receive<TranscriptionRequest>()
            val audioBytes = Base64.getDecoder().decode(request.file)
            val modelResult = modelRuntime.load(request.model)
            val adapter = modelResult.getOrThrow()

            val modelRequest = ModelRequest(
                messages = listOf(
                    Turn.System(request.prompt ?: "Transcribe audio"),
                    Turn.User(listOf(ContentBlock.Image("audio/wav", audioBytes)), TrustLevel.TRUSTED)
                ),
                tools = emptyList(),
                toolChoice = ToolChoice.None,
                maxOutputTokens = 1000,
                stopConditions = emptyList()
            )

            val result = adapter.invoke(modelRequest).getOrThrow()
            call.respond(TranscriptionResponse(text = result.text ?: ""))
        } catch (e: Exception) {
            call.respond(HttpStatusCode.BadRequest, ErrorResponse(ErrorDetail(e.message ?: "Unknown error", "invalid_request_error")))
        }
    }
}
