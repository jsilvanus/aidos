package fi.italeino.aidos.sdk

import kotlinx.coroutines.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Aidos Engine client library for Android applications (RFC-0103).
 *
 * Provides a single, unified client-side implementation of the Aidos Engine handshake,
 * transport, and API endpoints for consuming applications (Aidos Agent and others).
 *
 * This library handles:
 * - Binder handshake with Aidos Engine for token acquisition
 * - Loopback HTTP client for model inference endpoints
 * - Token refresh and cache management
 * - Version and capability negotiation
 * - ModelAdapter implementations for seamless integration with RFC-0021
 *
 * Applications should not re-implement the Engine protocol; use this library instead.
 *
 * See RFC-0103: Aidos Engine — Shared Local Inference Service
 * (docs/rfcs/0103-aidos-engine.md)
 */
interface AidosEngineClient {
    /**
     * Initialize connection to Aidos Engine via Binder handshake (RFC-0103, Phase C.3).
     * @return true if handshake successful and Engine is available
     */
    suspend fun initialize(): Boolean

    /**
     * Get whether Engine is available and connected (RFC-0103).
     */
    fun isAvailable(): Boolean

    /**
     * Get Engine's reported API version (RFC-0103).
     */
    fun apiVersion(): Int

    /**
     * Get Engine's supported capabilities/endpoints (RFC-0103).
     */
    suspend fun capabilities(): EngineCapabilities

    /**
     * Make an HTTP request to Engine /v1/ endpoint with automatic token authentication (RFC-0103, Phase C.3).
     * @param endpoint e.g., "chat/completions", "embeddings", "audio/transcriptions"
     * @param method HTTP method (GET, POST, etc.)
     * @param body request body JSON string (for POST/PUT requests)
     * @return response body as string
     */
    suspend fun request(endpoint: String, method: String = "POST", body: String? = null): String?

    /**
     * Check if Engine supports a specific endpoint (RFC-0103).
     */
    suspend fun supportsEndpoint(endpoint: String): Boolean

    /**
     * Release resources and close connection (RFC-0103).
     */
    fun close()
}

/**
 * Engine capabilities reported via handshake (RFC-0103).
 */
data class EngineCapabilities(
    val endpoints: List<String>,  // ["chat.completions", "embeddings", "audio.transcriptions"]
    val models: List<EngineModel>
)

/**
 * Model info reported by Engine (RFC-0103).
 */
data class EngineModel(
    val id: String,
    val kind: String,  // "llm", "embedding", "stt", "tts"
    val contextWindow: Int? = null,
    val quantization: String? = null
)

/**
 * Handshake response from Aidos Engine via Binder (RFC-0103, Phase C.3).
 */
internal data class HandshakeResponse(
    val port: Int,
    val token: String,
    val apiVersion: Int = 1,
    val capabilities: CapabilitiesResponse
)

@Serializable
internal data class CapabilitiesResponse(
    val endpoints: List<String>,
    val models: List<ModelInfoResponse>
)

@Serializable
internal data class ModelInfoResponse(
    val id: String,
    val kind: String,
    val context_window: Int? = null,
    val quantization: String? = null
)

// ignoreUnknownKeys tolerates Engine's wire shape carrying fields the SDK doesn't model (e.g.
// `object`, `owned_by`) — the same independent-versioning tolerance the Bundle switch was for.
private val capabilitiesJsonFormat = Json { ignoreUnknownKeys = true }

/**
 * Parses the `capabilitiesJson` field of the handshake Bundle (see
 * `fi.italeino.aidos.engine.IEngineHandshake.performHandshake` for the key contract) into
 * [CapabilitiesResponse]. Kept here, rather than in the Android-only Binder code, because JSON
 * decoding is ordinary Kotlin the jvm() target can compile and unit-test without Android.
 */
internal fun parseCapabilitiesJson(json: String): CapabilitiesResponse =
    capabilitiesJsonFormat.decodeFromString(json)

/**
 * Result of a Binder handshake attempt (RFC-0103): either a full [HandshakeResponse], or `null`
 * for every case where Engine is not usable right now (not installed, denied, pending approval,
 * or the call itself failing) — collapsed to one signal per the RFC's "Degradation" section.
 */
internal fun interface HandshakePerformer {
    suspend fun performHandshake(): HandshakeResponse?
}

/**
 * Engine client implementation with Binder handshake and HTTP transport (RFC-0103, Phase C.3).
 * 
 * This implementation:
 * - Discovers Aidos Engine via Binder handshake
 * - Caches token and port from handshake
 * - Provides HTTP client for /v1/ endpoints
 * - Handles authentication and error cases
 * - Offers graceful degradation when Engine is unavailable
 */
internal class EngineClientImpl(
    // Default performer never finds Engine — correct for the jvm() target, which has no Binder
    // to speak of. The real implementation is `EngineBinderHandshake` (sdk/androidMain), wired
    // in via `AndroidAidosEngineClientFactory`.
    private val handshakePerformer: HandshakePerformer = HandshakePerformer { null }
) : AidosEngineClient {
    private var handshakeResult: HandshakeResponse? = null
    private var isConnected = false
    private var capabilities: EngineCapabilities? = null
    private var lastCapabilitiesRefresh = 0L

    // Binder handshake timeout
    private val HANDSHAKE_TIMEOUT_MS = 5000L

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        val result = try {
            withTimeoutOrNull(HANDSHAKE_TIMEOUT_MS) { handshakePerformer.performHandshake() }
        } catch (e: Exception) {
            null
        }
        handshakeResult = result
        isConnected = result != null
        isConnected
    }

    override fun isAvailable(): Boolean = isConnected && handshakeResult != null

    override fun apiVersion(): Int = handshakeResult?.apiVersion ?: 0

    override suspend fun capabilities(): EngineCapabilities {
        val now = System.currentTimeMillis()
        
        // Cache capabilities for 5 seconds
        if (capabilities != null && (now - lastCapabilitiesRefresh) < 5000) {
            return capabilities!!
        }

        return handshakeResult?.capabilities?.let { caps ->
            EngineCapabilities(
                endpoints = caps.endpoints,
                models = caps.models.map { model ->
                    EngineModel(
                        id = model.id,
                        kind = model.kind,
                        contextWindow = model.context_window,
                        quantization = model.quantization
                    )
                }
            ).also {
                capabilities = it
                lastCapabilitiesRefresh = now
            }
        } ?: EngineCapabilities(endpoints = emptyList(), models = emptyList())
    }

    override suspend fun request(
        endpoint: String,
        method: String,
        body: String?
    ): String? = withContext(Dispatchers.IO) {
        val result = handshakeResult ?: return@withContext null

        try {
            // Build URL to Engine's HTTP server on loopback interface
            val url = URL("http://127.0.0.1:${result.port}/v1/$endpoint")

            // Create HTTP request with token authentication
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "******")
            connection.setRequestProperty("Content-Type", "application/json")
            connection.connectTimeout = 10000
            connection.readTimeout = 30000

            // Write request body if provided
            if (body != null && method in listOf("POST", "PUT", "PATCH")) {
                connection.doOutput = true
                connection.outputStream.use { stream ->
                    stream.write(body.toByteArray(Charsets.UTF_8))
                }
            }

            // Read response
            val responseCode = connection.responseCode
            return@withContext if (responseCode in 200..299) {
                connection.inputStream.bufferedReader().use { it.readText() }
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun supportsEndpoint(endpoint: String): Boolean {
        val caps = capabilities()
        return caps.endpoints.contains(endpoint)
    }

    override fun close() {
        isConnected = false
        handshakeResult = null
        capabilities = null
    }

    /**
     * Internal: Set handshake result (for testing or direct initialization).
     */
    internal fun setHandshakeResult(result: HandshakeResponse) {
        handshakeResult = result
        isConnected = true
    }
}

/**
 * Factory for creating Aidos Engine client instances (RFC-0103, Phase C.3).
 */
object AidosEngineClientFactory {
    /**
     * Create a client with no way to reach Engine — there is no Binder on the jvm() target this
     * factory also builds for, so [AidosEngineClient.initialize] always returns `false`. On
     * Android, use `AndroidAidosEngineClientFactory.createClient(context)` (sdk/androidMain)
     * instead, which performs the real handshake.
     */
    fun createClient(): AidosEngineClient = EngineClientImpl()
}

