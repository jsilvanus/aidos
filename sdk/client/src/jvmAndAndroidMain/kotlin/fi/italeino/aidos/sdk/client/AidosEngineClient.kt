package fi.italeino.aidos.sdk.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

/**
 * Aidos SDK client library — the one client-side implementation of Aidos Engine's handshake and
 * loopback transport (RFC-0103), published as `aidos-sdk-client` (Dictator plan D-1). Carries no
 * dependency on `:kernel`: a third-party consumer can ask for a chat completion without linking
 * Aidos's frozen contract types. The `ModelAdapter` bindings that do need `:kernel` live in the
 * separate `aidos-sdk-adapters` artifact (`sdk/adapters`).
 *
 * See RFC-0103: Aidos Engine — Shared Local Inference Service (docs/rfcs/0103-aidos-engine.md)
 * and docs/dictator-sdk-integration-plan.md.
 */
interface AidosEngineClient {
    /**
     * Perform the Binder handshake with Aidos Engine. Safe to call again after [availability]
     * reports anything other than [EngineAvailability.Available] — e.g. after the user approves
     * the app on Engine's ConnectedAppsScreen following a [EngineAvailability.PendingApproval].
     * @return true if the handshake succeeded and Engine is available.
     */
    suspend fun initialize(): Boolean

    /** Shorthand for `availability() == EngineAvailability.Available`. */
    fun isAvailable(): Boolean

    /**
     * Why Engine is or isn't usable right now (RFC-0103, "Degradation") — structured so a caller
     * can tell "not installed" (offer install) from "pending approval" (offer the approval deep
     * link) from "incompatible version," rather than one flattened boolean.
     */
    fun availability(): EngineAvailability

    /** Engine's reported API version, or 0 if there has been no successful handshake yet. */
    fun apiVersion(): Int

    /** Engine's supported capabilities/endpoints (RFC-0103). */
    suspend fun capabilities(): EngineCapabilities

    /**
     * Low-level escape hatch: an authenticated HTTP request to Engine's `/v1/` namespace,
     * re-handshaking once and retrying on a 401 (RFC-0103: "a client reconnecting after an
     * Engine restart re-handshakes; it does not reuse a stale token"). Prefer the typed
     * [chatCompletion]/[embeddings]/[transcribe]/[streamChatCompletion] methods where they cover
     * your use case.
     */
    suspend fun request(endpoint: String, method: String = "POST", body: String? = null): String?

    /** Check if Engine supports a specific endpoint (RFC-0103). */
    suspend fun supportsEndpoint(endpoint: String): Boolean

    /** Non-streaming `/v1/chat/completions`. Null on any failure (including Engine unavailable). */
    suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse?

    /**
     * Streaming `/v1/chat/completions` (`stream: true` is set regardless of [request]'s own
     * value). Emits one [ChatCompletionChunk] per SSE frame Engine sends and completes on Engine's
     * `data: [DONE]` terminator; emits nothing if Engine is unavailable or the call fails.
     */
    fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionChunk>

    /** `/v1/embeddings`. Null on any failure (including Engine unavailable). */
    suspend fun embeddings(request: EmbeddingsRequest): EmbeddingsResponse?

    /** `/v1/audio/transcriptions`. Null on any failure (including Engine unavailable). */
    suspend fun transcribe(request: TranscriptionRequest): TranscriptionResponse?

    /** Release resources and forget the current handshake. */
    fun close()
}

/**
 * Why Engine is or isn't usable right now (RFC-0103, "Degradation"). [PendingApproval]'s deep
 * link intent is Android-only (`PendingIntent` doesn't exist on the jvm() target this interface
 * also compiles for) and is surfaced separately by the Android entry point
 * (`AndroidAidosEngineClientFactory` in sdk/client's androidMain).
 */
enum class EngineAvailability {
    Available,
    NotInstalled,
    PendingApproval,
    Denied,
    IncompatibleVersion,
    HandshakeFailed
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

// --- OpenAI-compatible wire schema (RFC-0103, "Handshake and transport") -----------------------
// Mirrors engine/androidapp's OpenAiSchema.kt field-for-field so JSON round-trips, but is not the
// same type: this module has no dependency on engine/ or :kernel. Tool-calling (ToolDefinition,
// tool_calls) is deliberately not modeled here yet — no current consumer (Dictator's plan) needs
// it, and ignoreUnknownKeys means a response that includes it still decodes; add it when a real
// consumer does, the same "don't build it before something uses it" reasoning
// docs/dictator-sdk-integration-plan.md applies to embeddings.

@Serializable
data class ChatMessage(
    val role: String,  // "system", "user", "assistant", "tool"
    val content: String? = null
)

@Serializable
data class ChatCompletionRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Float = 0.7f,
    val max_tokens: Int? = null,
    val top_p: Float = 1.0f,
    val stream: Boolean = false
)

@Serializable
data class TokenUsage(
    val prompt_tokens: Int,
    val completion_tokens: Int,
    val total_tokens: Int
)

@Serializable
data class ChatChoice(
    val index: Int,
    val message: ChatMessage,
    val finish_reason: String? = null
)

@Serializable
data class ChatCompletionResponse(
    val id: String,
    val created: Long,
    val model: String,
    val choices: List<ChatChoice>,
    val usage: TokenUsage
)

@Serializable
data class ChunkDelta(
    val content: String? = null,
    val role: String? = null
)

@Serializable
data class ChunkChoice(
    val index: Int,
    val delta: ChunkDelta,
    val finish_reason: String? = null
)

@Serializable
data class ChatCompletionChunk(
    val id: String,
    val created: Long,
    val model: String,
    val choices: List<ChunkChoice>
)

@Serializable
data class EmbeddingsRequest(
    val model: String,
    val input: List<String>
)

@Serializable
data class Embedding(
    val embedding: List<Float>,
    val index: Int
)

@Serializable
data class EmbeddingsResponse(
    val data: List<Embedding>,
    val model: String,
    val usage: TokenUsage
)

@Serializable
data class TranscriptionRequest(
    // Base64-encoded audio (Engine's actual accepted shape, not OpenAI's multipart — see
    // docs/dictator-sdk-integration-plan.md's "Wire shape note"; aligning Engine to multipart is
    // future, Engine-side work, not an SDK bug).
    val file: String,
    val model: String,
    val language: String? = null
)

@Serializable
data class TranscriptionResponse(
    val text: String
)

// --- Handshake result shape ----------------------------------------------------------------

/**
 * Handshake response from Aidos Engine via Binder (RFC-0103), for the APPROVED case only — the
 * other statuses (`PENDING_APPROVAL`, `DENIED`) are modeled as distinct [HandshakeOutcome] cases
 * rather than fields on this type, so there's nothing here to leave unset.
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

// ignoreUnknownKeys tolerates Engine's wire shape carrying fields this module doesn't model (e.g.
// `object`, `owned_by`, tool-calling fields) — the same independent-versioning tolerance the
// Binder handshake's Bundle wire shape is for (see fi.italeino.aidos.engine.IEngineHandshake).
internal val engineJson = Json { ignoreUnknownKeys = true }

/**
 * Parses the `capabilitiesJson` field of the handshake Bundle (see
 * `fi.italeino.aidos.engine.IEngineHandshake.performHandshake` for the key contract) into
 * [CapabilitiesResponse]. Kept here, rather than in the Android-only Binder code, because JSON
 * decoding is ordinary Kotlin the jvm() target can compile and unit-test without Android.
 */
internal fun parseCapabilitiesJson(json: String): CapabilitiesResponse =
    engineJson.decodeFromString(json)

/**
 * Result of a Binder handshake attempt (RFC-0103), modeling all statuses Engine's handshake can
 * return (plus the client-only "call itself failed") — see IEngineHandshake.aidl's Bundle key
 * contract for what Engine actually sends.
 */
internal sealed class HandshakeOutcome {
    data class Approved(val response: HandshakeResponse) : HandshakeOutcome()
    data object NotInstalled : HandshakeOutcome()
    data object PendingApproval : HandshakeOutcome()
    data object Denied : HandshakeOutcome()
    data object Failed : HandshakeOutcome()
}

internal fun interface HandshakePerformer {
    suspend fun performHandshake(): HandshakeOutcome
}

// --- SSE ------------------------------------------------------------------------------------

/**
 * Accumulates SSE `data:` lines into complete event payloads: per the SSE spec, an event ends at
 * a blank line, and multiple `data:` lines within one event join with `\n`. Feed it complete
 * lines — Okio's `BufferedSource.readUtf8Line()` already reassembles lines split across network
 * reads, so this layer only reassembles lines into events, not bytes into lines.
 *
 * Engine (`EngineHttpServer.streamChatCompletions`) only ever writes single-line `data: ...`
 * frames, so other SSE fields (`event:`, `id:`, `retry:`, `:` comments) are accepted but ignored
 * rather than rejected, in case a future Engine version adds one.
 */
internal class SseFrameParser {
    private val dataLines = mutableListOf<String>()

    /** Returns the completed event payload, or null if [line] didn't complete one. */
    fun onLine(line: String): String? {
        if (line.isEmpty()) {
            if (dataLines.isEmpty()) return null
            val payload = dataLines.joinToString("\n")
            dataLines.clear()
            return payload
        }
        if (line.startsWith("data:")) {
            dataLines.add(line.removePrefix("data:").trimStart())
        }
        return null
    }
}

private val jsonMediaType = "application/json".toMediaType()
private val methodsRequiringBody = setOf("POST", "PUT", "PATCH")

/**
 * Engine client implementation with Binder handshake and OkHttp transport (RFC-0103).
 */
internal class EngineClientImpl(
    // Default performer never finds Engine — correct for the jvm() target, which has no Binder
    // to speak of. The real implementation is `EngineBinderHandshake` (sdk/client/androidMain),
    // wired in via `AndroidAidosEngineClientFactory`.
    private val handshakePerformer: HandshakePerformer = HandshakePerformer { HandshakeOutcome.NotInstalled },
    // RFC-0103, "Version and capability contract": a client whose required major version does
    // not match Engine's treats it as incompatible rather than guessing.
    private val requiredApiVersion: Int = 1,
    private val httpClient: OkHttpClient = OkHttpClient()
) : AidosEngineClient {
    private var handshakeResult: HandshakeResponse? = null
    private var availabilityState: EngineAvailability = EngineAvailability.NotInstalled
    private var cachedCapabilities: EngineCapabilities? = null
    private var lastCapabilitiesRefresh = 0L
    // Guards concurrent callers from triggering overlapping re-handshakes (RFC-0103: "the port is
    // ephemeral... never assumed stable across Engine restarts" — two racing re-handshakes could
    // otherwise leave `handshakeResult` holding one caller's port with another's token).
    private val handshakeMutex = Mutex()

    private val handshakeTimeoutMs = 5000L

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        handshakeMutex.withLock { doHandshakeLocked() }
    }

    private suspend fun doHandshakeLocked(): Boolean {
        val outcome = try {
            withTimeoutOrNull(handshakeTimeoutMs) { handshakePerformer.performHandshake() }
        } catch (e: Exception) {
            null
        } ?: HandshakeOutcome.Failed

        availabilityState = when (outcome) {
            is HandshakeOutcome.Approved -> {
                handshakeResult = outcome.response
                if (outcome.response.apiVersion == requiredApiVersion) {
                    EngineAvailability.Available
                } else {
                    EngineAvailability.IncompatibleVersion
                }
            }
            HandshakeOutcome.NotInstalled -> { handshakeResult = null; EngineAvailability.NotInstalled }
            HandshakeOutcome.PendingApproval -> { handshakeResult = null; EngineAvailability.PendingApproval }
            HandshakeOutcome.Denied -> { handshakeResult = null; EngineAvailability.Denied }
            HandshakeOutcome.Failed -> { handshakeResult = null; EngineAvailability.HandshakeFailed }
        }
        return availabilityState == EngineAvailability.Available
    }

    override fun isAvailable(): Boolean = availabilityState == EngineAvailability.Available

    override fun availability(): EngineAvailability = availabilityState

    override fun apiVersion(): Int = handshakeResult?.apiVersion ?: 0

    override suspend fun capabilities(): EngineCapabilities {
        val now = System.currentTimeMillis()

        // Cache capabilities for 5 seconds
        cachedCapabilities?.let { if (now - lastCapabilitiesRefresh < 5000) return it }

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
                cachedCapabilities = it
                lastCapabilitiesRefresh = now
            }
        } ?: EngineCapabilities(endpoints = emptyList(), models = emptyList())
    }

    override suspend fun request(endpoint: String, method: String, body: String?): String? =
        withContext(Dispatchers.IO) { executeRaw(endpoint, method, body, allowReauth = true) }

    private suspend fun executeRaw(
        endpoint: String,
        method: String,
        body: String?,
        allowReauth: Boolean
    ): String? {
        val current = handshakeResult ?: return null
        // OkHttp requires a non-null body for methods that carry one (POST/PUT/PATCH) — unlike
        // HttpURLConnection, it refuses to build the request rather than sending an empty body.
        val requestBody = body?.toRequestBody(jsonMediaType)
            ?: if (method in methodsRequiringBody) "".toRequestBody(jsonMediaType) else null
        val httpRequest = Request.Builder()
            .url("http://127.0.0.1:${current.port}/v1/$endpoint")
            .method(method, requestBody)
            .header("Authorization", "Bearer ${current.token}")
            .build()

        return try {
            httpClient.newCall(httpRequest).execute().use { response ->
                when {
                    // A 401 means the cached token is stale — most likely Engine restarted and
                    // minted a fresh one (RFC-0103: tokens are "scoped to one handshake"; a
                    // client reconnecting after a restart re-handshakes rather than reusing a
                    // stale token). Retry exactly once with a fresh handshake.
                    response.code == 401 && allowReauth -> {
                        val reconnected = handshakeMutex.withLock { doHandshakeLocked() }
                        if (reconnected) executeRaw(endpoint, method, body, allowReauth = false) else null
                    }
                    response.isSuccessful -> response.body?.string()
                    else -> null
                }
            }
        } catch (e: IOException) {
            null
        }
    }

    override suspend fun supportsEndpoint(endpoint: String): Boolean =
        capabilities().endpoints.contains(endpoint)

    override suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse? {
        val responseBody = executeRaw(
            "chat/completions", "POST", engineJson.encodeToString(request), allowReauth = true
        ) ?: return null
        return try {
            engineJson.decodeFromString(responseBody)
        } catch (e: Exception) {
            null
        }
    }

    override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionChunk> = flow {
        val current = handshakeResult ?: return@flow
        val streamingRequest = if (request.stream) request else request.copy(stream = true)
        val httpRequest = Request.Builder()
            .url("http://127.0.0.1:${current.port}/v1/chat/completions")
            .post(engineJson.encodeToString(streamingRequest).toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer ${current.token}")
            .build()

        try {
            httpClient.newCall(httpRequest).execute().use { response ->
                if (!response.isSuccessful) return@use
                val source = response.body?.source() ?: return@use
                val parser = SseFrameParser()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val payload = parser.onLine(line) ?: continue
                    if (payload == "[DONE]") break
                    emit(engineJson.decodeFromString<ChatCompletionChunk>(payload))
                }
            }
        } catch (e: IOException) {
            // Stream simply ends — matches every other method's "unavailable means nothing to
            // report" behavior rather than propagating the exception into a collector.
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun embeddings(request: EmbeddingsRequest): EmbeddingsResponse? {
        val responseBody = executeRaw(
            "embeddings", "POST", engineJson.encodeToString(request), allowReauth = true
        ) ?: return null
        return try {
            engineJson.decodeFromString(responseBody)
        } catch (e: Exception) {
            null
        }
    }

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResponse? {
        val responseBody = executeRaw(
            "audio/transcriptions", "POST", engineJson.encodeToString(request), allowReauth = true
        ) ?: return null
        return try {
            engineJson.decodeFromString(responseBody)
        } catch (e: Exception) {
            null
        }
    }

    override fun close() {
        availabilityState = EngineAvailability.NotInstalled
        handshakeResult = null
        cachedCapabilities = null
    }

    /**
     * Internal: Set handshake result (for testing or direct initialization).
     */
    internal fun setHandshakeResult(result: HandshakeResponse) {
        handshakeResult = result
        availabilityState = EngineAvailability.Available
    }
}

/**
 * Factory for creating Aidos Engine client instances (RFC-0103).
 */
object AidosEngineClientFactory {
    /**
     * Create a client with no way to reach Engine — there is no Binder on the jvm() target this
     * factory also builds for, so [AidosEngineClient.initialize] always returns `false`. On
     * Android, use `AndroidAidosEngineClientFactory.createClient(context)` (sdk/client/androidMain)
     * instead, which performs the real handshake.
     */
    fun createClient(): AidosEngineClient = EngineClientImpl()
}
