package fi.italeino.aidos.sdk

import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import kotlin.time.Duration.Companion.seconds

/**
 * Loopback HTTP client for Aidos Engine (RFC-0103).
 *
 * Communicates with Aidos Engine via OpenAI-compatible endpoints on 127.0.0.1.
 *
 * All requests require a valid bearer token (short-lived, obtained from handshake).
 * Connection details (port, token) are obtained from [EngineHandshakeClient].
 *
 * RFC-0103: "Bulk traffic — generate, embed, transcribe — is plain HTTP to the loopback
 * port, using an OpenAI-compatible schema: `/v1/chat/completions` (streamed via SSE),
 * `/v1/embeddings`, `/v1/audio/transcriptions`. This reuses the wire shape RFC-0021/0023
 * already need for remote providers, and avoids Binder's transaction-size limits."
 */
class EngineHttpClient(
    private val loopbackPort: Int,
    private val tokenManager: EngineTokenManager,
    private val httpClient: OkHttpClient = defaultHttpClient(),
) {
    companion object {
        const val LOOPBACK_HOST = "127.0.0.1"
        const val AUTHORIZATION_HEADER = "Authorization"
        const val BEARER_PREFIX = "Bearer "
        const val JSON_CONTENT_TYPE = "application/json"

        private fun defaultHttpClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(10.seconds)
                .readTimeout(30.seconds)
                .writeTimeout(30.seconds)
                .build()
        }
    }

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Call `/v1/chat/completions` endpoint.
     *
     * Supports streaming and non-streaming modes based on request.stream flag.
     * For streaming, the caller should handle SSE event stream parsing separately
     * (this method returns the raw response body).
     *
     * Returns the response or null if unavailable/unauthorized.
     */
    suspend fun chatCompletions(request: ChatCompletionRequest): Result<ChatCompletionResponse> {
        return try {
            val token = tokenManager.getToken()
                ?: return Result.failure(IllegalStateException("No valid token available"))

            val url = buildUrl("/v1/chat/completions")
            val body = json.encodeToString(ChatCompletionRequest.serializer(), request)
                .toRequestBody(JSON_CONTENT_TYPE.toMediaType())

            val httpRequest = Request.Builder()
                .url(url)
                .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$token")
                .post(body)
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            when {
                response.code == 401 -> {
                    tokenManager.invalidate()
                    Result.failure(EngineUnavailability.ConnectionFailed("Unauthorized: token expired or invalid"))
                }
                response.isSuccessful -> {
                    val responseBody = response.body?.string()
                        ?: return Result.failure(IllegalStateException("Empty response body"))
                    val chatResponse = json.decodeFromString(ChatCompletionResponse.serializer(), responseBody)
                    Result.success(chatResponse)
                }
                else -> Result.failure(
                    EngineUnavailability.ConnectionFailed("HTTP ${response.code}: ${response.message}")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Call `/v1/embeddings` endpoint.
     *
     * Returns an embedding response containing the vector representation.
     */
    suspend fun embeddings(request: EmbeddingRequest): Result<EmbeddingResponse> {
        return try {
            val token = tokenManager.getToken()
                ?: return Result.failure(IllegalStateException("No valid token available"))

            val url = buildUrl("/v1/embeddings")
            val body = json.encodeToString(EmbeddingRequest.serializer(), request)
                .toRequestBody(JSON_CONTENT_TYPE.toMediaType())

            val httpRequest = Request.Builder()
                .url(url)
                .header(AUTHORIZATION_HEADER, "$BEARER_PREFIX$token")
                .post(body)
                .build()

            val response = httpClient.newCall(httpRequest).execute()
            when {
                response.code == 401 -> {
                    tokenManager.invalidate()
                    Result.failure(EngineUnavailability.ConnectionFailed("Unauthorized: token expired or invalid"))
                }
                response.isSuccessful -> {
                    val responseBody = response.body?.string()
                        ?: return Result.failure(IllegalStateException("Empty response body"))
                    val embeddingResponse = json.decodeFromString(EmbeddingResponse.serializer(), responseBody)
                    Result.success(embeddingResponse)
                }
                else -> Result.failure(
                    EngineUnavailability.ConnectionFailed("HTTP ${response.code}: ${response.message}")
                )
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Call `/v1/audio/transcriptions` endpoint.
     *
     * Sends audio data as multipart/form-data and receives transcribed text.
     *
     * TODO(RFC-0103): Implement multipart/form-data handling for audio streaming.
     * This may require custom body handling to avoid loading the entire audio file.
     */
    suspend fun transcribe(request: TranscriptionRequest): Result<TranscriptionResponse> {
        return try {
            val token = tokenManager.getToken()
                ?: return Result.failure(IllegalStateException("No valid token available"))

            val url = buildUrl("/v1/audio/transcriptions")

            // TODO(RFC-0103): Build multipart form data with file + model parameter
            // For now, return unimplemented
            Result.failure(NotImplementedError("Audio transcription multipart handling not yet implemented"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Build a full URL for an Engine endpoint.
     *
     * Always targets 127.0.0.1 on the ephemeral port from handshake.
     */
    private fun buildUrl(path: String): HttpUrl {
        return HttpUrl.Builder()
            .scheme("http")
            .host(LOOPBACK_HOST)
            .port(loopbackPort)
            .encodedPath(path)
            .build()
    }

    /**
     * Close and release HTTP client resources.
     */
    fun close() {
        httpClient.dispatcher.executorService.shutdown()
        httpClient.connectionPool.evictAll()
    }
}
