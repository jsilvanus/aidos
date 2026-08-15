package fi.italeino.aidos.engine.http

import android.util.Log
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.Turn
import dev.aidos.kernel.TrustLevel
import dev.aidos.modelruntime.GlobalModelRuntime
import fi.italeino.aidos.engine.ModelStateManager
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.plugins.ContentNegotiation
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import java.net.InetAddress
import java.util.UUID

/**
 * HTTP server for Aidos Engine (RFC-0103).
 *
 * Provides OpenAI-compatible API endpoints for chat completions and embeddings.
 * Server binds to 127.0.0.1 on an ephemeral port and is accessible only locally.
 *
 * Features:
 * - POST /v1/chat/completions - Chat inference endpoint
 * - Token-based authentication via bearer token in Authorization header
 * - Automatic model loading on first request
 * - OpenAI-compatible request/response format
 */
class EngineHttpServer {
    private var server: ApplicationEngine? = null
    private val serverScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val modelStateManager = ModelStateManager.getInstance()
    
    private companion object {
        private const val TAG = "EngineHttpServer"
        private const val BIND_ADDRESS = "127.0.0.1"
        private const val PORT = 0 // Use ephemeral port
    }
    
    /**
     * Start the HTTP server.
     *
     * @return Pair of (port, token) for client connection, or null if startup failed
     */
    suspend fun start(): Pair<Int, String>? {
        return try {
            val token = UUID.randomUUID().toString()
            
            val engine = embeddedServer(
                factory = Netty,
                host = BIND_ADDRESS,
                port = PORT,
                module = { setupRouting(token) }
            ).apply {
                start(wait = false)
            }
            
            server = engine
            
            // Get the actual port assigned
            val actualPort = engine.resolvedConnectors().firstOrNull()?.port
            if (actualPort != null) {
                Log.i(TAG, "EngineHttpServer started on $BIND_ADDRESS:$actualPort")
                return Pair(actualPort, token)
            } else {
                Log.e(TAG, "Failed to determine server port")
                engine.stop()
                return null
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start EngineHttpServer", e)
            return null
        }
    }
    
    /**
     * Stop the HTTP server.
     */
    suspend fun stop() {
        try {
            server?.stop(gracePeriodMillis = 1000, timeoutMillis = 3000)
            server = null
            Log.i(TAG, "EngineHttpServer stopped")
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping EngineHttpServer", e)
        }
    }
    
    /**
     * Configure routing and handlers.
     */
    private fun Application.setupRouting(expectedToken: String) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        
        routing {
            // POST /v1/chat/completions - OpenAI-compatible chat endpoint
            post("/v1/chat/completions") {
                try {
                    // Verify authorization token
                    val authHeader = call.request.headers["Authorization"]
                    val token = authHeader?.removePrefix("Bearer ") ?: ""
                    
                    if (token != expectedToken) {
                        call.respond(
                            HttpStatusCode.Unauthorized,
                            OpenAiErrorResponse(
                                error = OpenAiError(
                                    message = "Invalid authentication token",
                                    code = "invalid_api_key"
                                )
                            )
                        )
                        return@post
                    }
                    
                    val request = call.receive<OpenAiChatCompletionRequest>()
                    
                    // Validate request
                    if (request.messages.isEmpty()) {
                        call.respond(
                            HttpStatusCode.BadRequest,
                            OpenAiErrorResponse(
                                error = OpenAiError(
                                    message = "Messages list cannot be empty",
                                    code = "invalid_request_error"
                                )
                            )
                        )
                        return@post
                    }
                    
                    // Load model if not already loaded
                    if (!modelStateManager.getLoadedModels().contains(request.model)) {
                        modelStateManager.loadModel(request.model)
                    }
                    
                    val runtime = modelStateManager.getRuntime() as? GlobalModelRuntime
                    if (runtime == null) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            OpenAiErrorResponse(
                                error = OpenAiError(
                                    message = "Model runtime not available",
                                    code = "server_error"
                                )
                            )
                        )
                        return@post
                    }
                    
                    // Load model adapter
                    val adapterResult = runtime.load(request.model)
                    if (!adapterResult.isSuccess) {
                        call.respond(
                            HttpStatusCode.NotFound,
                            OpenAiErrorResponse(
                                error = OpenAiError(
                                    message = "Model not found or failed to load: ${request.model}",
                                    code = "model_not_found"
                                )
                            )
                        )
                        return@post
                    }
                    
                    val adapter = adapterResult.getOrThrow()
                    
                    // Convert OpenAI messages to kernel Turn format
                    val messages = request.messages.mapNotNull { msg ->
                        when (msg.role) {
                            "system" -> Turn.System(msg.content ?: "")
                            "user" -> Turn.User(
                                content = listOf(ContentBlock.Text(msg.content ?: "")),
                                trustLevel = TrustLevel.TRUSTED
                            )
                            "assistant" -> Turn.Assistant(
                                text = msg.content,
                                toolCalls = emptyList()
                            )
                            else -> null
                        }
                    }
                    
                    // Create model request
                    val modelRequest = ModelRequest(
                        messages = messages,
                        tools = emptyList(),
                        toolChoice = ToolChoice.None,
                        maxOutputTokens = request.maxTokens ?: 512,
                        stopConditions = request.stop ?: emptyList()
                    )
                    
                    // Run inference
                    val startTime = System.currentTimeMillis()
                    val responseResult = adapter.invoke(modelRequest)
                    val endTime = System.currentTimeMillis()
                    
                    if (!responseResult.isSuccess) {
                        call.respond(
                            HttpStatusCode.InternalServerError,
                            OpenAiErrorResponse(
                                error = OpenAiError(
                                    message = "Inference failed: ${responseResult.exceptionOrNull()?.message}",
                                    code = "inference_error"
                                )
                            )
                        )
                        return@post
                    }
                    
                    val response = responseResult.getOrThrow()
                    
                    // Convert kernel response to OpenAI format
                    val finishReason = when {
                        response.stopReason.name == "END_TURN" -> "stop"
                        response.toolCalls.isNotEmpty() -> "tool_calls"
                        else -> "stop"
                    }
                    
                    val assistantMessage = response.text ?: ""
                    
                    val openAiResponse = OpenAiChatCompletionResponse(
                        id = UUID.randomUUID().toString(),
                        created = System.currentTimeMillis() / 1000,
                        model = request.model,
                        choices = listOf(
                            OpenAiChoice(
                                index = 0,
                                message = OpenAiMessage(
                                    role = "assistant",
                                    content = assistantMessage
                                ),
                                finishReason = finishReason
                            )
                        ),
                        usage = OpenAiUsage(
                            promptTokens = response.usage.inputTokens,
                            completionTokens = response.usage.outputTokens,
                            totalTokens = response.usage.inputTokens + response.usage.outputTokens
                        )
                    )
                    
                    Log.d(TAG, "Chat completion succeeded in ${endTime - startTime}ms for model ${request.model}")
                    call.respond(HttpStatusCode.OK, openAiResponse)
                    
                } catch (e: Exception) {
                    Log.e(TAG, "Error handling chat completion request", e)
                    call.respond(
                        HttpStatusCode.InternalServerError,
                        OpenAiErrorResponse(
                            error = OpenAiError(
                                message = e.message ?: "Internal server error",
                                code = "internal_error"
                            )
                        )
                    )
                }
            }
        }
    }
}
