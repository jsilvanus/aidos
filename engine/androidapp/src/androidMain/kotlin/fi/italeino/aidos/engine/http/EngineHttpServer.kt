package fi.italeino.aidos.engine.http

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
 * All endpoints require ****** authentication via Authorization header.
 * Binds to 127.0.0.1 on an ephemeral port (chosen by OS).
 * Server lifecycle is managed by [EngineService].
 */
class EngineHttpServer(
    private val tokenManager: TokenManager,
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
            // TODO(RFC-0103): Integrate with modelruntime for actual inference
            // For now, return a stub response
            val response = ChatCompletionResponse(
                id = "chatcmpl-${UUID.randomUUID()}",
                created = System.currentTimeMillis() / 1000,
                model = request.model,
                choices = listOf(
                    Choice(
                        index = 0,
                        message = ChatMessage(role = "assistant", content = "[Model inference not yet implemented]"),
                        finish_reason = "stop"
                    )
                ),
                usage = TokenUsage(
                    prompt_tokens = 0,
                    completion_tokens = 0,
                    total_tokens = 0
                )
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

    private suspend fun handleEmbeddings(call: ApplicationCall) {
        try {
            val request = call.receive<EmbeddingsRequest>()
            // TODO(RFC-0103): Integrate with modelruntime for actual embedding inference
            val response = EmbeddingsResponse(
                data = request.input.mapIndexed { index, _ ->
                    Embedding(
                        embedding = FloatArray(1024) { 0.0f }.toList(),  // Placeholder: 1024-dim zero vector
                        index = index
                    )
                },
                model = request.model,
                usage = TokenUsage(
                    prompt_tokens = 0,
                    completion_tokens = 0,
                    total_tokens = 0
                )
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

    private suspend fun handleTranscriptions(call: ApplicationCall) {
        try {
            val request = call.receive<TranscriptionRequest>()
            // TODO(RFC-0103): Integrate with voice providers for STT
            val response = TranscriptionResponse(
                text = "[Speech-to-text not yet implemented]"
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
