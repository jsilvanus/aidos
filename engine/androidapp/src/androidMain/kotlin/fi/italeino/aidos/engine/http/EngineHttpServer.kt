package fi.italeino.aidos.engine.http

import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.TokenUsage
import dev.aidos.kernel.Turn
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import io.ktor.server.application.*
import io.ktor.server.auth.*
import io.ktor.server.cio.*
import io.ktor.server.engine.*
import io.ktor.server.plugins.contentnegotiation.*
import io.ktor.server.response.*
import io.ktor.server.routing.*
import kotlinx.serialization.json.Json
import java.util.*

/**
 * Ktor HTTP server for Aidos Engine local inference (RFC-0103).
 *
 * Hosts OpenAI-compatible endpoints:
 * - POST /v1/chat/completions — LLM inference
 * - POST /v1/embeddings — Text embeddings
 * - POST /v1/audio/transcriptions — Speech-to-text
 *
 * All endpoints require bearer token authentication via Authorization header.
 * Binds to 127.0.0.1 on an ephemeral port (chosen by OS).
 * Server lifecycle is managed by [EngineService].
 */
class EngineHttpServer(
    private val tokenManager: TokenManager,
    private val modelRuntime: ModelRuntime,
    private val port: Int = 0  // 0 = let OS choose ephemeral port
) {
    private var server: CIOApplicationEngine? = null

    /**
     * Get the actual port the server is bound to. Only valid after [start].
     */
    fun getBoundPort(): Int? = server?.environment?.connectors?.firstOrNull()?.port

    /**
     * Start the HTTP server.
     * Blocks until the server is ready to accept connections.
     */
    fun start() {
        server = embeddedServer(CIO, port = port, host = "127.0.0.1") {
            setupContentNegotiation()
            setupAuthentication()
            setupRouting()
        }.start(wait = false)
    }

    /**
     * Stop the HTTP server gracefully.
     */
    fun stop() {
        server?.stop()
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
                    if (token != null) {
                        UserIdPrincipal(token)
                    } else {
                        null
                    }
                }
            }
        }
    }

    private fun Application.setupRouting() {
        routing {
            // Health check endpoint (no auth required)
            get("/health") {
                call.respond(mapOf("status" to "ok"))
            }

            // OpenAI-compatible endpoints (all require bearer auth)
            authenticate("bearerAuth") {
                post("/v1/chat/completions") {
                    handleChatCompletions(call)
                }

                post("/v1/embeddings") {
                    handleEmbeddings(call)
                }

                post("/v1/audio/transcriptions") {
                    handleTranscriptions(call)
                }
            }

            // 404 for unknown endpoints
            route("{...}") {
                handle {
                    call.respond(
                        HttpStatusCode.NotFound,
                        ErrorResponse(
                            error = ErrorDetail(
                                message = "Endpoint not found",
                                type = "not_found"
                            )
                        )
                    )
                }
            }
        }
    }

    private suspend fun handleChatCompletions(call: ApplicationCall) {
        try {
            val request = call.receive<ChatCompletionRequest>()

            // Validate request
            if (request.model.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = "Model name is required",
                            type = "invalid_request_error"
                        )
                    )
                )
                return
            }

            if (request.messages.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = "At least one message is required",
                            type = "invalid_request_error"
                        )
                    )
                )
                return
            }

            // Try to load the model (RFC-0103, M21: admission queue serializes loading)
            val modelResult = modelRuntime.load(request.model)
            if (modelResult.isFailure) {
                val error = modelResult.exceptionOrNull()
                val statusCode = when {
                    error?.message?.contains("not installed") == true -> HttpStatusCode.NotFound
                    else -> HttpStatusCode.InternalServerError
                }
                call.respond(
                    statusCode,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = error?.message ?: "Failed to load model ${request.model}",
                            type = "model_error"
                        )
                    )
                )
                return
            }

            val adapter = modelResult.getOrNull()
                ?: run {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(
                            error = ErrorDetail(
                                message = "Model adapter is null",
                                type = "internal_error"
                            )
                        )
                    )
                    return
                }

            // Convert HTTP request messages to kernel format
            val turns = request.messages.map { msg ->
                when (msg.role) {
                    "system" -> Turn.System(msg.content ?: "")
                    "user" -> Turn.User(
                        content = listOf(
                            dev.aidos.kernel.ContentBlock.Text(msg.content ?: "")
                        ),
                        trustLevel = dev.aidos.kernel.TrustLevel.TRUSTED
                    )
                    "assistant" -> Turn.Assistant(
                        text = msg.content,
                        toolCalls = msg.tool_calls?.map { tc ->
                            dev.aidos.kernel.ToolCall(
                                callId = tc.id,
                                toolName = tc.function.name,
                                arguments = tc.function.arguments,
                                capabilityId = null,
                                rawText = tc.function.arguments
                            )
                        } ?: emptyList()
                    )
                    "tool" -> Turn.ToolResult(
                        result = dev.aidos.kernel.ToolCallResult.Ok(
                            toolName = msg.name ?: "unknown",
                            content = msg.content ?: ""
                        )
                    )
                    else -> Turn.System("")  // fallback
                }
            }

            // Convert tools to kernel format
            val tools = request.tools?.map { toolDef ->
                dev.aidos.kernel.ToolDescriptor(
                    name = toolDef.function.name,
                    description = toolDef.function.description ?: "",
                    inputSchema = toolDef.function.parameters ?: emptyMap(),
                    requiresApprovalPerUse = false
                )
            } ?: emptyList()

            // Build the model request
            val modelRequest = ModelRequest(
                messages = turns,
                tools = tools,
                toolChoice = when {
                    request.tool_choice == "required" -> ToolChoice.Required
                    request.tool_choice == "none" -> ToolChoice.None
                    request.tool_choice?.startsWith("function:") == true -> {
                        val toolName = request.tool_choice.removePrefix("function:")
                        ToolChoice.Specific(toolName)
                    }
                    else -> ToolChoice.Auto
                },
                maxOutputTokens = request.max_tokens ?: adapter.contextWindow,
                stopConditions = emptyList()
            )

            // Invoke the model
            val inferenceResult = adapter.invoke(modelRequest)
            if (inferenceResult.isFailure) {
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = inferenceResult.exceptionOrNull()?.message
                                ?: "Inference failed",
                            type = "inference_error"
                        )
                    )
                )
                return
            }

            val response = inferenceResult.getOrNull()
                ?: run {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(
                            error = ErrorDetail(
                                message = "Model response is null",
                                type = "internal_error"
                            )
                        )
                    )
                    return
                }

            // Convert the model response to HTTP format
            val message = ChatMessage(
                role = "assistant",
                content = response.text,
                tool_calls = response.toolCalls.map { tc ->
                    ToolCall(
                        id = tc.callId,
                        type = "function",
                        function = ToolFunctionCall(
                            name = tc.toolName,
                            arguments = tc.arguments
                        )
                    )
                }
            )

            val httpResponse = ChatCompletionResponse(
                id = "chatcmpl-${UUID.randomUUID()}",
                created = System.currentTimeMillis() / 1000,
                model = request.model,
                choices = listOf(
                    Choice(
                        index = 0,
                        message = message,
                        finish_reason = response.stopReason.name.lowercase()
                    )
                ),
                usage = TokenUsage(
                    prompt_tokens = response.usage.inputTokens,
                    completion_tokens = response.usage.outputTokens,
                    total_tokens = response.usage.inputTokens + response.usage.outputTokens
                )
            )

            call.respond(httpResponse)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = ErrorDetail(
                        message = e.message ?: "Unknown error",
                        type = "invalid_request_error"
                    )
                )
            )
        }
    }

    private suspend fun handleEmbeddings(call: ApplicationCall) {
        try {
            val request = call.receive<EmbeddingsRequest>()

            // Validate request
            if (request.model.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = "Model name is required",
                            type = "invalid_request_error"
                        )
                    )
                )
                return
            }

            if (request.input.isEmpty()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = "At least one input string is required",
                            type = "invalid_request_error"
                        )
                    )
                )
                return
            }

            // Try to load the embedding model (RFC-0103, M22: embeddings model)
            val modelResult = modelRuntime.load(request.model)
            if (modelResult.isFailure) {
                val error = modelResult.exceptionOrNull()
                val statusCode = when {
                    error?.message?.contains("not installed") == true -> HttpStatusCode.NotFound
                    else -> HttpStatusCode.InternalServerError
                }
                call.respond(
                    statusCode,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = error?.message ?: "Failed to load model ${request.model}",
                            type = "model_error"
                        )
                    )
                )
                return
            }

            val adapter = modelResult.getOrNull()
                ?: run {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(
                            error = ErrorDetail(
                                message = "Model adapter is null",
                                type = "internal_error"
                            )
                        )
                    )
                    return
                }

            // Compute embeddings for each input
            val embeddings = mutableListOf<Embedding>()
            var totalInputTokens = 0
            var totalOutputTokens = 0

            for ((index, input) in request.input.withIndex()) {
                // Create a simple request for embedding
                val modelRequest = ModelRequest(
                    messages = listOf(
                        Turn.User(
                            content = listOf(
                                dev.aidos.kernel.ContentBlock.Text(input)
                            ),
                            trustLevel = dev.aidos.kernel.TrustLevel.TRUSTED
                        )
                    ),
                    tools = emptyList(),
                    toolChoice = ToolChoice.None,
                    maxOutputTokens = 0,  // Embeddings don't generate text
                    stopConditions = emptyList()
                )

                val inferenceResult = adapter.invoke(modelRequest)
                if (inferenceResult.isFailure) {
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        ErrorResponse(
                            error = ErrorDetail(
                                message = "Failed to embed input at index $index: ${inferenceResult.exceptionOrNull()?.message}",
                                type = "inference_error"
                            )
                        )
                    )
                    return
                }

                val response = inferenceResult.getOrNull()
                    ?: run {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            ErrorResponse(
                                error = ErrorDetail(
                                    message = "Embedding model response is null",
                                    type = "internal_error"
                                )
                            )
                        )
                        return
                    }

                // The embedding is extracted from response.text if it's a vector
                // For now, use a placeholder (future: implement actual embedding extraction)
                val embedding = FloatArray(1024) { 0.0f }.toList()
                embeddings.add(
                    Embedding(
                        embedding = embedding,
                        index = index
                    )
                )

                totalInputTokens += response.usage.inputTokens
                totalOutputTokens += response.usage.outputTokens
            }

            val httpResponse = EmbeddingsResponse(
                data = embeddings,
                model = request.model,
                usage = TokenUsage(
                    prompt_tokens = totalInputTokens,
                    completion_tokens = totalOutputTokens,
                    total_tokens = totalInputTokens + totalOutputTokens
                )
            )

            call.respond(httpResponse)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = ErrorDetail(
                        message = e.message ?: "Unknown error",
                        type = "invalid_request_error"
                    )
                )
            )
        }
    }

    private suspend fun handleTranscriptions(call: ApplicationCall) {
        try {
            val request = call.receive<TranscriptionRequest>()

            // Validate request
            if (request.model.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = "Model name is required",
                            type = "invalid_request_error"
                        )
                    )
                )
                return
            }

            // Note: Full STT integration requires voice provider integration (RFC-0022, D28)
            // This is deferred to Phase C. For now, return a placeholder.
            val response = TranscriptionResponse(
                text = "[Speech-to-text model loading not yet implemented in Phase B - deferred to Phase C]"
            )
            call.respond(response)
        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.BadRequest,
                ErrorResponse(
                    error = ErrorDetail(
                        message = e.message ?: "Unknown error",
                        type = "invalid_request_error"
                    )
                )
            )
        }
    }
}
