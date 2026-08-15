package fi.italeino.aidos.sdk

import android.content.Context
import dev.aidos.kernel.ModelAdapter

/**
 * Aidos Engine client library for Android applications (RFC-0103).
 *
 * This is the single, unified client-side implementation of the Aidos Engine handshake,
 * transport, and API endpoints for consuming applications (Aidos Agent and others).
 *
 * **Applications should not re-implement the Engine protocol; use this library instead.**
 *
 * The SDK handles:
 * - Binder handshake with Aidos Engine for token acquisition
 * - Loopback HTTP client for model inference endpoints
 * - Token refresh and cache management
 * - Version and capability negotiation
 * - ModelAdapter implementations for seamless integration with RFC-0021
 * - Graceful degradation when Engine is unavailable or incompatible
 *
 * ## Usage
 *
 * ```
 * // Initialize once at app startup
 * val engineClient = AidosEngineClient.create(context)
 *
 * // Check if Engine is available and compatible
 * when (engineClient) {
 *     is AidosEngineClient.Available -> {
 *         val llmModels = engineClient.listLlmModels()
 *         val adapter = engineClient.createLlmAdapter("qwen2.5-3b-q4")
 *         val response = adapter.invoke(modelRequest)
 *     }
 *     is AidosEngineClient.Unavailable -> {
 *         // Fall back to remote provider
 *         reportUnavailability(engineClient.reason)
 *     }
 * }
 * ```
 *
 * ## Integration with Aidos Agent
 *
 * Aidos Agent's routing layer treats Engine as a primary provider (RFC-0021 symmetry).
 * The router automatically falls back to configured remote providers if Engine is
 * unavailable or incompatible, preserving offline-first guarantees.
 *
 * See RFC-0103: Aidos Engine — Shared Local Inference Service
 * (docs/rfcs/0103-aidos-engine.md)
 */
sealed class AidosEngineClient {
    /**
     * Engine is available and compatible.
     *
     * Provides access to model discovery and adapter creation.
     */
    class Available internal constructor(
        internal val factory: EngineModelAdapterFactory,
        internal val capabilityChecker: EngineCapabilityChecker,
        internal val capabilities: EngineCapabilities,
        internal val httpClient: EngineHttpClient,
    ) : AidosEngineClient() {
        /**
         * Get all available LLM models.
         */
        fun listLlmModels(): List<String> = factory.listLlmModels()

        /**
         * Get all available Embedding models.
         */
        fun listEmbeddingModels(): List<String> = factory.listEmbeddingModels()

        /**
         * Get all available STT models.
         */
        fun listSttModels(): List<String> = factory.listSttModels()

        /**
         * Create an LLM adapter for the given model.
         *
         * Returns null if the model is not available or the endpoint is missing.
         */
        fun createLlmAdapter(modelId: String): ModelAdapter? = factory.createLlmAdapter(modelId, null)

        /**
         * Create an Embedding adapter for the given model.
         */
        fun createEmbeddingAdapter(modelId: String): ModelAdapter? = factory.createEmbeddingAdapter(modelId)

        /**
         * Create an STT adapter for the given model.
         */
        fun createSttAdapter(modelId: String): ModelAdapter? = factory.createSttAdapter(modelId)

        /**
         * Get a ModelAdapter for any kind.
         */
        fun createAdapter(modelId: String): ModelAdapter? = factory.createAdapter(modelId)

        /**
         * Get the raw capabilities reported by Engine (for advanced usage).
         */
        fun getCapabilities(): EngineCapabilities = capabilities
    }

    /**
     * Engine is unavailable or incompatible.
     *
     * RFC-0103 Degradation: "Aidos SDK surfaces 'Engine not installed' and
     * 'handshake or version negotiation fails' as one signal — local inference
     * unavailable — which every consuming app, Aidos Agent included, handles
     * the same way rather than each inventing its own detection."
     */
    class Unavailable internal constructor(
        val reason: EngineUnavailability,
    ) : AidosEngineClient()

    companion object {
        /**
         * Initialize Aidos Engine client.
         *
         * This is the main entry point. Performs:
         * 1. Binder handshake with Aidos Engine
         * 2. API version compatibility check
         * 3. Capability negotiation
         * 4. HTTP client setup
         *
         * Returns Available if Engine is present and compatible, Unavailable otherwise.
         */
        suspend fun create(context: Context): AidosEngineClient {
            val handshakeClient = EngineHandshakeClient(context)
            val tokenManager = EngineTokenManager()

            val handshakeResult = handshakeClient.handshake()
            return handshakeResult.fold(
                onSuccess = { response ->
                    // Check API version compatibility
                    val capabilityChecker = EngineCapabilityChecker()
                    when (val versionCheck = capabilityChecker.checkApiVersion(response.apiVersion)) {
                        is ApiVersionResult.Incompatible -> {
                            Unavailable(versionCheck.let {
                                EngineUnavailability.VersionIncompatible(it.clientRequired, it.serverHas)
                            })
                        }
                        is ApiVersionResult.Compatible -> {
                            // Store token and create HTTP client
                            tokenManager.store(response.token)
                            val httpClient = EngineHttpClient(response.port, tokenManager)
                            val factory = EngineModelAdapterFactory(httpClient, response.capabilities)

                            Available(factory, capabilityChecker, response.capabilities, httpClient)
                        }
                    }
                },
                onFailure = { error ->
                    handshakeClient.disconnect()
                    Unavailable(error as? EngineUnavailability ?: EngineUnavailability.HandshakeFailed(error.message ?: "Unknown error"))
                }
            )
        }
    }
}
