package fi.italeino.aidos.engine.http

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.ModelRuntime
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TokenUsage
import dev.aidos.kernel.TrustLevel
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
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.encodeToString
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
                            ContentBlock.Text(msg.content ?: "")
                        ),
                        trustLevel = TrustLevel.TRUSTED
                    )
                    "assistant" -> Turn.Assistant(
                        text = msg.content,
                        toolCalls = msg.tool_calls?.map { tc ->
                            dev.aidos.kernel.ToolCall(
                                callId = tc.id,
                                toolName = tc.function.name,
                                arguments = parseJsonObject(tc.function.arguments),
                                capabilityId = null,
                                rawText = tc.function.arguments
                            )
                        } ?: emptyList()
                    )
                    "tool" -> Turn.ToolResult(
                        result = ToolCallResult(
                            callId = msg.tool_call_id ?: UUID.randomUUID().toString(),
                            outcome = ToolOutcome.Ok,
                            content = listOf(ContentBlock.Text(msg.content ?: "")),
                            trustLevel = TrustLevel.TRUSTED
                        )
                    )
                    else -> Turn.System("")  // fallback
                }
            }

            // Convert tools to kernel format
            val tools = request.tools?.map { toolDef ->
                // Convert parameters map to JsonObject
                val parametersJson = toolDef.function.parameters?.let { params ->
                    val entries = params.mapValues { (_, v) ->
                        when (v) {
                            is String -> JsonPrimitive(v)
                            is Number -> JsonPrimitive(v)
                            is Boolean -> JsonPrimitive(v)
                            else -> JsonPrimitive(v.toString())
                        }
                    }
                    JsonObject(entries)
                } ?: JsonObject(emptyMap())

                dev.aidos.kernel.ToolDescriptor(
                    name = toolDef.function.name,
                    title = toolDef.function.name,
                    description = toolDef.function.description ?: "",
                    schema = parametersJson
                )
            } ?: emptyList()

            // Parse tool_choice
            val toolChoice = when (request.tool_choice?.lowercase()) {
                "none" -> ToolChoice.None
                "required" -> ToolChoice.Required
                "auto" -> ToolChoice.Auto
                else -> if (tools.isNotEmpty()) ToolChoice.Auto else ToolChoice.None
            }

            // Build model request
            val modelRequest = ModelRequest(
                messages = turns,
                tools = tools,
                toolChoice = toolChoice,
                maxOutputTokens = request.max_tokens ?: 2000,
                stopConditions = emptyList()
            )

            // Invoke the model
            val inferenceResult = adapter.invoke(modelRequest)
            if (inferenceResult.isFailure) {
                val error = inferenceResult.exceptionOrNull()
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = error?.message ?: "Inference failed",
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

            // Handle streaming or non-streaming response (RFC-0103, Phase C.2)
            if (request.stream) {
                // Send SSE streaming response
                streamChatCompletions(call, response, request.model)
            } else {
                // Send complete response
                val message = ChatMessage(
                    role = "assistant",
                    content = response.text,
                    tool_calls = response.toolCalls.map { tc ->
                        ToolCall(
                            id = tc.callId,
                            type = "function",
                            function = ToolFunctionCall(
                                name = tc.toolName,
                                arguments = tc.arguments.toString()
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
            }
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

    /**
     * Stream chat completion response as Server-Sent Events (RFC-0103, Phase C.2).
     * Converts complete model response into incremental chunks for streaming clients.
     */
    private suspend fun streamChatCompletions(
        call: ApplicationCall,
        response: ModelResponse,
        modelId: String
    ) {
        val completionId = "chatcmpl-${UUID.randomUUID()}"
        val responseText = response.text ?: ""

        // Set response headers for streaming
        call.response.header(HttpHeaders.ContentType, "text/event-stream")
        call.response.header(HttpHeaders.CacheControl, "no-cache")
        call.response.header("Connection", "keep-alive")

        // Send initial chunk with start of response
        val text = responseText.ifEmpty { "" }

        // Split response into tokens (words/spaces) for streaming effect
        // This simulates token-by-token streaming while working with complete response
        val tokens = tokenizeResponseText(text)

        try {
            // Stream each token as a separate SSE event
            for ((tokenIndex, token) in tokens.withIndex()) {
                val chunk = ChatCompletionChunk(
                    id = completionId,
                    created = System.currentTimeMillis() / 1000,
                    model = modelId,
                    choices = listOf(
                        ChunkChoice(
                            index = 0,
                            delta = ChunkDelta(content = token),
                            finish_reason = null
                        )
                    )
                )

                val json = Json.encodeToString(ChatCompletionChunk.serializer(), chunk)
                call.response.write("data: $json\n\n".encodeToByteArray())
            }

            // Send final chunk with stop reason
            val finalChunk = ChatCompletionChunk(
                id = completionId,
                created = System.currentTimeMillis() / 1000,
                model = modelId,
                choices = listOf(
                    ChunkChoice(
                        index = 0,
                        delta = ChunkDelta(content = ""),
                        finish_reason = response.stopReason.name.lowercase()
                    )
                )
            )

            val finalJson = Json.encodeToString(ChatCompletionChunk.serializer(), finalChunk)
            call.response.write("data: $finalJson\n\n".encodeToByteArray())

            // Send stream end marker
            call.response.write("data: [DONE]\n\n".encodeToByteArray())
        } catch (e: Exception) {
            // Client disconnected or other streaming error
            // Gracefully handle without throwing
        }
    }

    /**
     * Tokenize response text for streaming (RFC-0103, Phase C.2).
     * Splits text into tokens while preserving meaningful units.
     * Simple implementation: split on spaces and punctuation boundaries.
     */
    private fun tokenizeResponseText(text: String): List<String> {
        if (text.isEmpty()) return emptyList()

        val tokens = mutableListOf<String>()
        var current = StringBuilder()

        for (char in text) {
            when {
                char == ' ' -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current = StringBuilder()
                    }
                    tokens.add(" ")
                }
                char in ".,!?;:" -> {
                    if (current.isNotEmpty()) {
                        tokens.add(current.toString())
                        current = StringBuilder()
                    }
                    tokens.add(char.toString())
                }
                else -> current.append(char)
            }
        }

        if (current.isNotEmpty()) {
            tokens.add(current.toString())
        }

        return tokens
    }

                        when (v) {
                            is String -> JsonPrimitive(v)
                            is Number -> JsonPrimitive(v)
                            is Boolean -> JsonPrimitive(v)
                            else -> JsonPrimitive(v.toString())
                        }
                    }
                    JsonObject(entries)
                } ?: JsonObject(emptyMap())

                dev.aidos.kernel.ToolDescriptor(
                    name = toolDef.function.name,
                    title = toolDef.function.name,  // Use name as title for now
                    description = toolDef.function.description ?: "",
                    inputSchema = parametersJson
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
                            arguments = tc.arguments.toString()
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

    /**
     * Parse a JSON string into a JsonObject.
     * Returns an empty JsonObject if parsing fails.
     */
    private fun parseJsonObject(jsonString: String): JsonObject {
        return try {
            Json.parseToJsonElement(jsonString) as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: Exception) {
            JsonObject(emptyMap())
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

            if (request.file.isBlank()) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = "Audio file is required",
                            type = "invalid_request_error"
                        )
                    )
                )
                return
            }

            // Decode base64 audio (RFC-0103, Phase C: STT integration)
            val audioBytes = try {
                Base64.getDecoder().decode(request.file)
            } catch (e: IllegalArgumentException) {
                call.respond(
                    HttpStatusCode.BadRequest,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = "Invalid base64-encoded audio file: ${e.message}",
                            type = "invalid_request_error"
                        )
                    )
                )
                return
            }

            // Try to load the STT model (RFC-0103, Phase C: Model loading)
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
                            message = error?.message ?: "Failed to load STT model ${request.model}",
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

            // Build prompt hint if provided (helps guide transcription)
            val systemPrompt = if (!request.prompt.isNullOrBlank()) {
                request.prompt
            } else {
                "Transcribe the following audio to text. Output only the transcription, nothing else."
            }

            // Create model request with audio data as Image content block
            // Use audio/wav as mime type (generic audio format)
            val modelRequest = ModelRequest(
                messages = listOf(
                    Turn.System(systemPrompt),
                    Turn.User(
                        content = listOf(
                            ContentBlock.Image(
                                mimeType = "audio/wav",
                                data = audioBytes
                            )
                        ),
                        trustLevel = TrustLevel.TRUSTED
                    )
                ),
                tools = emptyList(),
                toolChoice = ToolChoice.None,
                maxOutputTokens = request.prompt?.length?.coerceAtMost(500) ?: 500,
                stopConditions = emptyList()
            )

            // Invoke the model for transcription
            val inferenceResult = adapter.invoke(modelRequest)
            if (inferenceResult.isFailure) {
                val error = inferenceResult.exceptionOrNull()
                call.respond(
                    HttpStatusCode.InternalServerError,
                    ErrorResponse(
                        error = ErrorDetail(
                            message = error?.message ?: "STT model inference failed",
                            type = "inference_error"
                        )
                    )
                )
                return
            }

            val modelResponse = inferenceResult.getOrNull()
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

            // Extract transcribed text from model response
            val transcribedText = modelResponse.text ?: ""

            // Return transcription response (RFC-0103, Phase C: STT integration complete)
            val response = TranscriptionResponse(text = transcribedText)
            call.respond(response)

        } catch (e: Exception) {
            call.respond(
                HttpStatusCode.InternalServerError,
                ErrorResponse(
                    error = ErrorDetail(
                        message = e.message ?: "Unknown error during transcription",
                        type = "internal_error"
                    )
                )
            )
        }
    }
}
