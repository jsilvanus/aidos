package fi.italeino.aidos.sdk

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
 *
 * TODO(RFC-0103): Implement handshake, token management, loopback HTTP client,
 * and ModelAdapter implementations for LLM, embedding, and STT.
 * This includes:
 * - Binder IPC to Aidos Engine's handshake surface
 * - Token acquisition and refresh logic
 * - OpenAI-compatible HTTP client for /v1/chat/completions, /v1/embeddings, /v1/audio/transcriptions
 * - Capability negotiation and version checking
 * - Graceful degradation when Engine is unavailable or incompatible
 * - Integration with RFC-0021's ModelAdapter interface
 */
interface AidosEngineClient {
    // TODO(RFC-0103): Define handshake(), openSession(), chat(), embed(), transcribe() methods
}
