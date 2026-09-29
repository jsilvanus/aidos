package fi.italeino.aidos.sdk.client

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Tripwires for the frozen Engine API v1 contract (sdk/CONTRACT.md). Nothing here tests behavior —
 * the other test classes do — it makes an accidental change to the *shape* fail loudly. If one of
 * these fails and the change is deliberate, that is a contract change: follow the process in
 * CONTRACT.md (additive changes edit the expected list; breaking ones bump the API version).
 */
class ContractFreezeTest {

    // --- Handshake wire (Engine pins the same lists in its own HandshakeWireContractTest) -----

    @Test
    fun handshakeBundleKeysAreFrozen() {
        assertEquals(
            listOf("status", "port", "token", "apiVersion", "capabilitiesJson", "deepLinkPendingIntent"),
            listOf(
                HandshakeWire.KEY_STATUS, HandshakeWire.KEY_PORT, HandshakeWire.KEY_TOKEN,
                HandshakeWire.KEY_API_VERSION, HandshakeWire.KEY_CAPABILITIES_JSON, HandshakeWire.KEY_DEEP_LINK
            )
        )
    }

    @Test
    fun handshakeStatusValuesAreFrozen() {
        assertEquals(
            listOf("APPROVED", "PENDING_APPROVAL", "DENIED"),
            listOf(HandshakeWire.STATUS_APPROVED, HandshakeWire.STATUS_PENDING_APPROVAL, HandshakeWire.STATUS_DENIED)
        )
    }

    // --- Public client surface --------------------------------------------------------------

    @Test
    fun clientInterfaceMethodsAreFrozen() {
        assertEquals(
            listOf(
                "apiVersion", "availability", "capabilities", "chatCompletion", "close", "embeddings",
                "initialize", "isAvailable", "request", "streamChatCompletion", "supportsEndpoint", "transcribe"
            ),
            AidosEngineClient::class.java.methods.map { it.name }.filter { !it.contains("$") }.distinct().sorted()
        )
    }

    @Test
    fun availabilityStatesAreFrozen() {
        assertEquals(
            listOf("Available", "NotInstalled", "PendingApproval", "Denied", "IncompatibleVersion", "HandshakeFailed"),
            EngineAvailability.entries.map { it.name }
        )
    }

    // --- Wire types: field names are the JSON keys ------------------------------------------

    private fun fields(type: Class<*>) = type.declaredFields.filter { !it.isSynthetic && !it.name.startsWith("$") && it.name != "Companion" }.map { it.name }

    @Test
    fun requestAndResponseWireFieldsAreFrozen() {
        assertEquals(listOf("model", "messages", "temperature", "max_tokens", "top_p", "stream"), fields(ChatCompletionRequest::class.java))
        assertEquals(listOf("role", "content"), fields(ChatMessage::class.java))
        assertEquals(listOf("id", "created", "model", "choices", "usage"), fields(ChatCompletionResponse::class.java))
        assertEquals(listOf("index", "message", "finish_reason"), fields(ChatChoice::class.java))
        assertEquals(listOf("id", "created", "model", "choices"), fields(ChatCompletionChunk::class.java))
        assertEquals(listOf("index", "delta", "finish_reason"), fields(ChunkChoice::class.java))
        assertEquals(listOf("content", "role"), fields(ChunkDelta::class.java))
        assertEquals(listOf("prompt_tokens", "completion_tokens", "total_tokens"), fields(TokenUsage::class.java))
        assertEquals(listOf("model", "input"), fields(EmbeddingsRequest::class.java))
        assertEquals(listOf("embedding", "index"), fields(Embedding::class.java))
        assertEquals(listOf("data", "model", "usage"), fields(EmbeddingsResponse::class.java))
        assertEquals(listOf("file", "model", "language"), fields(TranscriptionRequest::class.java))
        assertEquals(listOf("text"), fields(TranscriptionResponse::class.java))
    }

    @Test
    fun capabilityFieldsAreFrozen() {
        assertEquals(listOf("endpoints", "models"), fields(EngineCapabilities::class.java))
        assertEquals(listOf("id", "kind", "contextWindow", "quantization"), fields(EngineModel::class.java))
        // The handshake's capabilitiesJson, which Engine serializes: snake_case keys.
        assertEquals(listOf("endpoints", "models"), fields(CapabilitiesResponse::class.java))
        assertEquals(listOf("id", "kind", "context_window", "quantization"), fields(ModelInfoResponse::class.java))
    }
}
