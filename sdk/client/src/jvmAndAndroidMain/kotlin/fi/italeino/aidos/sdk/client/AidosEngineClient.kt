package fi.italeino.aidos.sdk.client

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
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
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
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

    /** Engine's reported (newest) API version, or 0 if there has been no successful handshake yet. */
    fun apiVersion(): Int

    /**
     * Engine's build version string from the handshake, or null before a successful handshake or
     * from an Engine that predates it. For display and diagnostics, never for feature checks:
     * detect behaviour with [supportsEndpoint].
     */
    fun engineVersion(): String? = null

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

    /**
     * `/v1/audio/speech` (RFC-0117 amendment): speaks [SpeechRequest.input] with the voice model
     * [SpeechRequest.model] and returns the audio (WAV unless [SpeechRequest.response_format] is `pcm`). Null on any
     * failure. Additive: the default is null, so other implementers keep compiling (sdk/CONTRACT.md).
     */
    suspend fun speak(request: SpeechRequest): ByteArray? = null

    /**
     * `/v1/audio/transcriptions` as OpenAI-style `multipart/form-data` (RFC-0117 amendment): [wav] goes
     * up as raw bytes, without the base64 that [transcribe] needs. Null on any failure. Additive; the
     * default sends the same audio through [transcribe] so other implementers keep working.
     */
    suspend fun transcribeBytes(model: String, wav: ByteArray, language: String? = null, prompt: String? = null): TranscriptionResponse? =
        transcribeBytesResult(model, wav, language, prompt).getOrNull()

    /** [transcribeBytes] returning `Result<T>`, failing with the call's own [EngineError] (see [transcribeResult]). */
    suspend fun transcribeBytesResult(model: String, wav: ByteArray, language: String? = null, prompt: String? = null): Result<TranscriptionResponse> =
        transcribeResult(
            TranscriptionRequest(
                file = java.util.Base64.getEncoder().encodeToString(wav),
                model = model,
                language = language,
                prompt = prompt,
            )
        )

    /**
     * `/v1/systemone`: typed decisions ("System One"). Several typed questions about [DecisionRequest.state]
     * are answered in one model pass, with probabilities instead of generated text. Null on any
     * failure (including Engine unavailable, or the model not being a decision model).
     *
     * Added to API v1 as an additive default method (sdk/CONTRACT.md); the default exists so
     * existing implementations of this interface keep compiling.
     */
    suspend fun decide(request: DecisionRequest): DecisionResponse? = null

    /**
     * What this client tells Engine about where inference may run (RFC-0104), or null for "nothing":
     * Engine then applies its own default, which is [Locality.LOCAL_ONLY] (D47). A value is sent
     * with every request and Engine refuses a model served wider (HTTP 403, `locality_violation`,
     * surfaced here as a null result). Going wider than local is therefore always an explicit
     * choice of the caller: set [Locality.TRUSTED_CLUSTER] or [Locality.ANY].
     */
    val locality: Locality? get() = null

    /**
     * The most recent classified error from Engine (§2.7) from ANY call on this client, best
     * effort, or null if no call has failed yet. A successful call does not clear it, and with
     * concurrent calls it may belong to a different call than the one you just made: for the
     * error of one specific call use the `...Result` methods ([chatCompletionResult] and friends),
     * which carry it in the returned [Result].
     */
    val lastError: EngineError? get() = null

    /**
     * Per-call variants of the typed methods: the [Result] fails with [EngineApiException]
     * carrying this call's own [EngineError], so concurrent calls cannot see each other's errors.
     * Added to API v1 as additive default methods (sdk/CONTRACT.md); the defaults adapt the
     * nullable method and [lastError], so other implementers of this interface keep compiling.
     */
    suspend fun requestResult(endpoint: String, method: String = "POST", body: String? = null): Result<String> =
        request(endpoint, method, body).orFailure(lastError)

    suspend fun chatCompletionResult(request: ChatCompletionRequest): Result<ChatCompletionResponse> =
        chatCompletion(request).orFailure(lastError)

    suspend fun embeddingsResult(request: EmbeddingsRequest): Result<EmbeddingsResponse> =
        embeddings(request).orFailure(lastError)

    suspend fun transcribeResult(request: TranscriptionRequest): Result<TranscriptionResponse> =
        transcribe(request).orFailure(lastError)

    suspend fun decideResult(request: DecisionRequest): Result<DecisionResponse> =
        decide(request).orFailure(lastError)

    /** Release resources and forget the current handshake. */
    fun close()
}

/**
 * Detailed error reported by Aidos Engine (§2.7, RFC-0103).
 */
@Serializable
data class EngineError(
    val message: String,
    val type: String = "engine_error",
    val param: String? = null,
    val code: String? = null,
    val statusCode: Int? = null,
) {
    /**
     * Human-actionable message explaining what happened and how to resolve it.
     */
    fun userActionableMessage(): String {
        val hint = when (code) {
            "locality_violation" -> "Placement restricted: model cannot be served under requested locality"
            "model_unavailable" -> "No candidate models available in inference group. Check connected devices in Groups"
            "peer_busy" -> "Group peers are busy with other requests. Try again shortly"
            "plan_changed" -> "Inference group topology changed during execution. Please retry"
            "peer_lost" -> "A peer device disconnected during streaming generation. Retrying on local fallback"
            "split_failed" -> "Split layer execution failed across peers. Check device resources"
            "supervisor_failed" -> "Layer supervisor process failed. Please restart Engine"
            "insufficient_memory" -> "Device RAM full: close background apps or unload other models"
            "queue_saturated" -> "Engine busy: too many queued requests. Retry in a moment"
            "shutdown" -> "Engine is restarting or shutting down"
            "model_busy" -> "Model is currently processing another request. Retry shortly"
            "model_not_installed" -> "Model is not installed. Download it first"
            "model_integrity_mismatch" -> "Model file appears corrupted. Delete and re-download"
            "model_integrity_missing" -> "Model integrity verification missing"
            "incompatible_model" -> "Model is incompatible with this device runtime"
            "model_load_failed" -> "Failed to load model into memory"
            "native_failure" -> "Internal inference failure in native engine"
            "request_too_large" -> "Request payload exceeds allowed size limit"
            "generation_limit_exceeded" -> "Requested token generation limit exceeds allowed maximum"
            "request_limit_exceeded" -> "Request exceeds prompt or message limits"
            "request_timeout" -> "Generation timed out before completion"
            "request_cancelled" -> "Request was cancelled"
            else -> null
        }
        return if (hint != null) "$message ($hint)" else message
    }
}

private fun <T : Any> T?.orFailure(error: EngineError?): Result<T> =
    if (this != null) Result.success(this) else Result.failure(
        EngineApiException(
            error?.statusCode ?: 0,
            error ?: EngineError(message = "Engine returned no result", type = "engine_error")
        )
    )

/**
 * Thrown when an Aidos Engine HTTP streaming call fails with a classified error.
 */
class EngineApiException(
    val statusCode: Int,
    val error: EngineError,
    cause: Throwable? = null,
) : RuntimeException(
    "Engine API error ($statusCode${error.code?.let { ": $it" } ?: ""}): ${error.message}",
    cause
)

@Serializable
private data class ErrorResponseWire(
    val error: ErrorDetailWire
)

@Serializable
private data class ErrorDetailWire(
    val message: String,
    val type: String = "engine_error",
    val param: String? = null,
    val code: String? = null,
)

/**
 * The widest place a request's data may go (RFC-0104), sent as `X-Aidos-Locality`. [LOCAL_ONLY]:
 * Engine's own process on this device. [TRUSTED_CLUSTER]: also devices in an authorised Aidos group.
 * [ANY]: also third-party providers. A client that sends none is held to [LOCAL_ONLY] by Engine
 * (D47). Additive within API v1 (sdk/CONTRACT.md).
 */
enum class Locality(val wire: String) {
    LOCAL_ONLY("local_only"),
    TRUSTED_CLUSTER("trusted_cluster"),
    ANY("any")
}

/** Capability entry in [EngineCapabilities.endpoints] meaning Engine enforces `X-Aidos-Locality`. */
const val LOCALITY_CAPABILITY = "locality"
const val LOCALITY_HEADER = "X-Aidos-Locality"

/**
 * Sent on every request so Engine can record which SDK build and API version each app speaks
 * (Connected Apps) — recorded, never refused on. Additive within API v1 (sdk/CONTRACT.md).
 */
const val SDK_VERSION_HEADER = "X-Aidos-Sdk-Version"
const val API_VERSION_HEADER = "X-Aidos-Api-Version"

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
    val quantization: String? = null,
    /**
     * Where this model's requests are served: `local`, `cluster` or `remote` (RFC-0104); null from
     * an Engine that predates it. Treat anything but `local` as leaving the device.
     */
    val placement: String? = null
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
    val content: String? = null,
    /**
     * Pictures for a vision model, on `user` messages: base64 of the encoded image (PNG/JPEG/...)
     * or a `data:image/...;base64,` URL. [content] carries the question about them. Optional and
     * omitted from the JSON when null, so existing callers are unchanged (additive within API v1).
     */
    val images: List<String>? = null
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

/** `/v1/audio/speech` body (RFC-0117 amendment). `response_format` is `wav` (default) or `pcm`; `speed` is 0.25 to 4.0. */
@Serializable
data class SpeechRequest(
    val model: String,
    val input: String,
    val voice: String? = null,
    val response_format: String? = null,
    val speed: Double? = null,
)

@Serializable
data class TranscriptionRequest(
    // Base64-encoded audio (Engine's actual accepted shape, not OpenAI's multipart — see
    // docs/dictator-sdk-integration-plan.md's "Wire shape note"; aligning Engine to multipart is
    // future, Engine-side work, not an SDK bug).
    val file: String,
    val model: String,
    // ISO 639-1 code ("fi"). Say it when you know it: detection on a short clip is unreliable.
    val language: String? = null,
    // Optional spelling and vocabulary hint for Whisper (names, liturgical terms); additive within API v1.
    val prompt: String? = null
)

@Serializable
data class TranscriptionResponse(
    val text: String
)

// --- Typed decisions: /v1/systemone (additive within API v1) -------------------------------------
// Jev / OpenJev compatible shape: {model, state, questions} -> {model, answers, usage}.

@Serializable
data class DecisionRequest(
    val model: String,
    val state: String,
    val questions: Map<String, DecisionQuestionSpec>,
    /** Answers whose answer_confidence is below this come back with abstained = true. */
    val min_confidence: Double? = null
)

/**
 * One typed question. [criteria] is, by [type]: `choice` an object `{label: description|null}`;
 * `score` an ordered array of level descriptions; `noul` optional `{"false": .., "true": ..}`.
 */
@Serializable
data class DecisionQuestionSpec(
    val type: String,
    val instructions: String,
    val criteria: JsonElement? = null
) {
    companion object {
        fun choice(instructions: String, options: Map<String, String?>) = DecisionQuestionSpec(
            "choice", instructions,
            buildJsonObject { options.forEach { (label, desc) -> put(label, desc?.let { JsonPrimitive(it) } ?: JsonNull) } }
        )

        fun score(instructions: String, levels: List<String>) = DecisionQuestionSpec(
            "score", instructions, JsonArray(levels.map { JsonPrimitive(it) })
        )

        fun noul(instructions: String) = DecisionQuestionSpec("noul", instructions)
    }
}

@Serializable
data class DecisionResponse(
    val model: String,
    val answers: Map<String, DecisionAnswerWire>,
    val usage: DecisionUsageWire? = null
)

/** Fields present depend on [type]: choice -> [choice]; score -> [score] + [legend]; noul -> [noul]. */
@Serializable
data class DecisionAnswerWire(
    val type: String,
    val choice: String? = null,
    val score: Double? = null,
    val noul: Double? = null,
    val probabilities: Map<String, Double>? = null,
    val legend: Map<String, String>? = null,
    val confidence: Double = 0.0,
    val answer_confidence: Double = 0.0,
    val abstained: Boolean = false
)

@Serializable
data class DecisionUsageWire(
    val input_tokens: Int = 0,
    val output_tokens: Int = 0,
    val state_tokens: Int = 0,
    val state_tokens_dropped: Int = 0,
    val truncated: Boolean = false
)

// --- Handshake result shape ----------------------------------------------------------------

/**
 * The handshake Bundle vocabulary of Engine API v1 (RFC-0103) — frozen, see sdk/CONTRACT.md. Engine
 * keeps its own copy (`HandshakeWire` in engine/enginehost, a separate Gradle project by design);
 * both are pinned by a `HandshakeWireContractTest` so drift fails a test instead of a user's phone.
 */
internal object HandshakeWire {
    const val KEY_STATUS = "status"
    const val KEY_PORT = "port"
    const val KEY_TOKEN = "token"
    const val KEY_API_VERSION = "apiVersion"
    const val KEY_CAPABILITIES_JSON = "capabilitiesJson"
    const val KEY_DEEP_LINK = "deepLinkPendingIntent"
    // Added within API v1 (sdk/CONTRACT.md, "Version negotiation"): absent from an Engine that predates them.
    const val KEY_ENGINE_VERSION = "engineVersion"
    const val KEY_MIN_API_VERSION = "minApiVersion"

    const val STATUS_APPROVED = "APPROVED"
    const val STATUS_PENDING_APPROVAL = "PENDING_APPROVAL"
    const val STATUS_DENIED = "DENIED"
}

/**
 * Handshake response from Aidos Engine via Binder (RFC-0103), for the APPROVED case only — the
 * other statuses (`PENDING_APPROVAL`, `DENIED`) are modeled as distinct [HandshakeOutcome] cases
 * rather than fields on this type, so there's nothing here to leave unset.
 */
internal data class HandshakeResponse(
    val port: Int,
    val token: String,
    val apiVersion: Int = 1,
    val capabilities: CapabilitiesResponse,
    /** Oldest API version Engine still serves; null from an Engine that predates it, meaning only [apiVersion]. */
    val minApiVersion: Int? = null,
    val engineVersion: String? = null
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
    val quantization: String? = null,
    val placement: String? = null
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

private const val CAPABILITIES_TTL_MS = 5000L

/** The slice of `GET /v1/models` the client reads; unknown fields are ignored. */
@Serializable
internal data class ModelsListWire(val data: List<ModelCardWire> = emptyList())

@Serializable
internal data class ModelCardWire(
    val id: String,
    val kind: String,
    val context_window: Int? = null,
    val quantization: String? = null,
    val placement: String? = null
)

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
    // RFC-0103, "Version and capability contract": the API versions this client can speak. It is
    // compatible when they overlap Engine's [minApiVersion, apiVersion] (a missing minApiVersion
    // means an Engine that speaks only apiVersion), and incompatible rather than guessing when not.
    private val supportedApiVersions: IntRange = 1..1,
    // Reported to Engine in SDK_VERSION_HEADER on every request.
    private val sdkVersion: String = SDK_VERSION,
    // OkHttp's default 10 s read timeout is shorter than a cold model load plus inference (a first
    // transcription or chat on a freshly started Engine), which surfaced as a null result.
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .readTimeout(java.time.Duration.ofMinutes(5))
        .build(),
    // RFC-0104: what this client tells Engine. Fail closed for a limit narrower than ANY: if Engine
    // does not advertise that it enforces limits, requests are not sent at all, because an Engine
    // that ignores the header would serve them anywhere it likes.
    override val locality: Locality? = null
) : AidosEngineClient {
    private var handshakeResult: HandshakeResponse? = null
    private var availabilityState: EngineAvailability = EngineAvailability.NotInstalled
    private class CachedCapabilities(val value: EngineCapabilities, val at: Long)

    // Written by doHandshakeLocked/close and by capabilities() under capabilitiesMutex; @Volatile
    // so readers never see a torn pair (value and timestamp travel together).
    @Volatile private var cachedCapabilities: CachedCapabilities? = null
    // Bumped by every handshake, so a live capabilities fetch that overlapped a re-handshake does
    // not store a result that belongs to the previous Engine process.
    @Volatile private var handshakeGeneration = 0
    // Separate from handshakeMutex: the live fetch goes through the authorised request path, whose
    // 401 handling takes handshakeMutex, so sharing one would deadlock.
    private val capabilitiesMutex = Mutex()

    // Most recent error from any call, best effort (see AidosEngineClient.lastError). Only failing
    // calls write it: a success must not wipe the error a concurrent call has just set.
    @Volatile override var lastError: EngineError? = null
        private set
    // Guards concurrent callers from triggering overlapping re-handshakes (RFC-0103: "the port is
    // ephemeral... never assumed stable across Engine restarts" — two racing re-handshakes could
    // otherwise leave `handshakeResult` holding one caller's port with another's token).
    private val handshakeMutex = Mutex()

    private val handshakeTimeoutMs = 5000L

    override suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        handshakeMutex.withLock { doHandshakeLocked() }
    }

    private suspend fun doHandshakeLocked(): Boolean {
        handshakeGeneration++
        cachedCapabilities = null
        val outcome = try {
            withTimeoutOrNull(handshakeTimeoutMs) { handshakePerformer.performHandshake() }
        } catch (e: Exception) {
            null
        } ?: HandshakeOutcome.Failed

        availabilityState = when (outcome) {
            is HandshakeOutcome.Approved -> {
                handshakeResult = outcome.response
                if (negotiatedApiVersion(outcome.response) != null) {
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

    override fun engineVersion(): String? = handshakeResult?.engineVersion

    /** The newest API version both sides speak, or null when the ranges do not overlap. */
    private fun negotiatedApiVersion(handshake: HandshakeResponse): Int? {
        val engine = (handshake.minApiVersion ?: handshake.apiVersion)..handshake.apiVersion
        val newest = minOf(engine.last, supportedApiVersions.last)
        return newest.takeIf { it >= maxOf(engine.first, supportedApiVersions.first) }
    }

    /**
     * Engine's capabilities, cached for [CAPABILITIES_TTL_MS]. On expiry the model list is fetched
     * live from `GET /v1/models`, so a model installed or removed after the handshake shows up;
     * the endpoint list has no live source and always comes from the handshake. When the fetch
     * fails, or Engine does not advertise `models`, the handshake snapshot is returned.
     */
    override suspend fun capabilities(): EngineCapabilities = capabilitiesMutex.withLock {
        val now = System.currentTimeMillis()
        cachedCapabilities?.let { if (now - it.at < CAPABILITIES_TTL_MS) return@withLock it.value }

        val generation = handshakeGeneration
        val snapshot = handshakeResult?.toCapabilities()
            ?: return@withLock EngineCapabilities(endpoints = emptyList(), models = emptyList())
        val live = if ("models" in snapshot.endpoints) fetchLiveModels() else null
        val result = if (live != null) snapshot.copy(models = live) else snapshot
        // A re-handshake during the fetch makes this result stale: still return it, do not keep it.
        if (generation == handshakeGeneration) cachedCapabilities = CachedCapabilities(result, now)
        result
    }

    private fun HandshakeResponse.toCapabilities() = EngineCapabilities(
        endpoints = capabilities.endpoints,
        models = capabilities.models.map { model ->
            EngineModel(
                id = model.id,
                kind = model.kind,
                contextWindow = model.context_window,
                quantization = model.quantization,
                placement = model.placement
            )
        }
    )

    /** Live `GET /v1/models`, or null on any failure (the caller falls back to the handshake). */
    private suspend fun fetchLiveModels(): List<EngineModel>? = try {
        val body = withContext(Dispatchers.IO) {
            executeCall("models", "GET", null, allowReauth = true, record = false)
        }
        engineJson.decodeFromString<ModelsListWire>(body).data.map {
            EngineModel(
                id = it.id,
                kind = it.kind,
                contextWindow = it.context_window,
                quantization = it.quantization,
                placement = it.placement
            )
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    override suspend fun request(endpoint: String, method: String, body: String?): String? =
        withContext(Dispatchers.IO) { executeRaw(endpoint, method, body, allowReauth = true) }

    override suspend fun requestResult(endpoint: String, method: String, body: String?): Result<String> =
        withContext(Dispatchers.IO) { callResult { executeCall(endpoint, method, body, allowReauth = true) } }

    /** Nullable form of [executeCall]: null on any Engine failure, the error left in [lastError]. */
    private suspend fun executeRaw(endpoint: String, method: String, body: String?, allowReauth: Boolean): String? =
        try {
            executeCall(endpoint, method, body, allowReauth)
        } catch (e: EngineApiException) {
            null
        }

    /** Runs [block], turning an [EngineApiException] into a failed [Result] (cancellation still propagates). */
    private inline fun <T> callResult(block: () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: EngineApiException) {
        Result.failure(e)
    }

    /** Decodes [body] as [T]; an unreadable body is a failure of this call, recorded like any other. */
    private inline fun <reified T> decodeOrFail(body: String): T = try {
        engineJson.decodeFromString<T>(body)
    } catch (e: Exception) {
        throw fail(EngineError(message = "Unreadable response from Engine", type = "decode_error", code = "invalid_response"), 502)
    }

    /**
     * Records [error] as the most recent one and returns the exception carrying it. Every failure
     * path goes through here, so the error a call returns is always its own.
     */
    private fun fail(error: EngineError, status: Int = error.statusCode ?: 503, record: Boolean = true): EngineApiException {
        if (record) lastError = error
        return EngineApiException(status, error)
    }

    /**
     * The one transport path: the response body on success, otherwise throws [EngineApiException]
     * with this call's own [EngineError]. Only failures write [lastError], and only when [record].
     */
    private suspend fun executeCall(
        endpoint: String,
        method: String,
        body: String?,
        allowReauth: Boolean,
        record: Boolean = true,
        // A body that is not JSON (multipart transcription). Reused as is on a re-handshake retry.
        rawBody: RequestBody? = null,
    ): String = executeCallRaw(endpoint, method, body, allowReauth, record, rawBody) { it.body?.string() ?: "" }

    /**
     * [executeCall] with the success body read by [read]: binary answers (speech audio, RFC-0117 amendment) use it
     * with `bytes()`. Auth, locality, the 401 re-handshake and error mapping are the same for both.
     */
    private suspend fun <T> executeCallRaw(
        endpoint: String,
        method: String,
        body: String?,
        allowReauth: Boolean,
        record: Boolean,
        rawBody: RequestBody?,
        read: (okhttp3.Response) -> T,
    ): T {
        val current = handshakeResult ?: throw fail(
            EngineError(
                message = "Engine is not initialized or unavailable ($availabilityState)",
                type = "availability_error",
                code = "engine_unavailable"
            ), 503, record
        )
        if (!localityEnforceable(current)) {
            throw fail(
                EngineError(
                    message = "Locality restriction ($locality) cannot be enforced by this Engine",
                    type = "locality_error",
                    code = "locality_unsupported"
                ), 403, record
            )
        }
        // OkHttp requires a non-null body for methods that carry one (POST/PUT/PATCH) — unlike
        // HttpURLConnection, it refuses to build the request rather than sending an empty body.
        val requestBody = rawBody ?: body?.toRequestBody(jsonMediaType)
            ?: if (method in methodsRequiringBody) "".toRequestBody(jsonMediaType) else null
        val httpRequest = Request.Builder()
            .url("http://127.0.0.1:${current.port}/v1/$endpoint")
            .method(method, requestBody)
            .header("Authorization", "Bearer ${current.token}")
            .withLocality()
            .withVersions(current)
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
                        if (reconnected) executeCallRaw(endpoint, method, body, allowReauth = false, record = record, rawBody = rawBody, read = read) else {
                            throw fail(
                                EngineError(
                                    message = "Engine token expired and re-handshake failed",
                                    type = "auth_error",
                                    code = "unauthorized",
                                    statusCode = 401
                                ), 401, record
                            )
                        }
                    }
                    response.isSuccessful -> read(response)
                    else -> {
                        val errorBody = response.body?.string()
                        throw fail(parseErrorDetail(errorBody, response.code), response.code, record)
                    }
                }
            }
        } catch (e: java.net.ConnectException) {
            // Nothing is listening on the port from the last handshake: Engine restarted, and its
            // port is ephemeral (RFC-0103: "never assumed stable across Engine restarts"). A 401
            // can never arrive from a port that is gone, so this is the same reconnect case.
            if (allowReauth && handshakeMutex.withLock { doHandshakeLocked() }) {
                executeCallRaw(endpoint, method, body, allowReauth = false, record = record, rawBody = rawBody, read = read)
            } else {
                throw fail(
                    EngineError(
                        message = e.message ?: "Engine is not reachable",
                        type = "io_error",
                        code = "connection_failed"
                    ), 503, record
                )
            }
        } catch (e: IOException) {
            throw fail(
                EngineError(
                    message = e.message ?: "Network error connecting to Engine",
                    type = "io_error",
                    code = "connection_failed"
                ), 503, record
            )
        }
    }

    private fun parseErrorDetail(body: String?, statusCode: Int): EngineError {
        if (!body.isNullOrBlank()) {
            try {
                val wire = engineJson.decodeFromString<ErrorResponseWire>(body)
                return EngineError(
                    message = wire.error.message,
                    type = wire.error.type,
                    param = wire.error.param,
                    code = wire.error.code,
                    statusCode = statusCode
                )
            } catch (_: Exception) {
                return EngineError(
                    message = body.take(200),
                    type = "http_error",
                    statusCode = statusCode
                )
            }
        }
        return EngineError(
            message = "HTTP $statusCode",
            type = "http_error",
            statusCode = statusCode
        )
    }

    /** True when this client requires no limit, or Engine says it enforces them. */
    private fun localityEnforceable(handshake: HandshakeResponse): Boolean =
        locality == null || locality == Locality.ANY || LOCALITY_CAPABILITY in handshake.capabilities.endpoints

    private fun Request.Builder.withLocality(): Request.Builder =
        locality?.let { header(LOCALITY_HEADER, it.wire) } ?: this

    private fun Request.Builder.withVersions(handshake: HandshakeResponse): Request.Builder =
        header(SDK_VERSION_HEADER, sdkVersion)
            .header(API_VERSION_HEADER, (negotiatedApiVersion(handshake) ?: handshake.apiVersion).toString())

    override suspend fun supportsEndpoint(endpoint: String): Boolean =
        capabilities().endpoints.contains(endpoint)

    override suspend fun chatCompletion(request: ChatCompletionRequest): ChatCompletionResponse? = chatCompletionResult(request).getOrNull()

    override suspend fun chatCompletionResult(request: ChatCompletionRequest): Result<ChatCompletionResponse> = callResult {
        decodeOrFail<ChatCompletionResponse>(executeCall("chat/completions", "POST", engineJson.encodeToString(request), allowReauth = true))
    }

    override fun streamChatCompletion(request: ChatCompletionRequest): Flow<ChatCompletionChunk> = flow {
        // One reconnect, as for [request]: a stale token (401) or a port nothing listens on any
        // more both mean Engine restarted since the last handshake (RFC-0103).
        var mayReconnect = true
        while (true) {
            val reconnect = streamOnce(request, mayReconnect)
            if (!reconnect) return@flow
            mayReconnect = false
            if (!handshakeMutex.withLock { doHandshakeLocked() }) {
                val err = EngineError(
                    message = "Engine restarted and re-handshake failed",
                    type = "auth_error",
                    code = "unauthorized",
                    statusCode = 401
                )
                lastError = err
                throw EngineApiException(401, err)
            }
        }
    }.flowOn(Dispatchers.IO)

    /**
     * One attempt at the stream. Returns true when nothing was emitted and the caller should
     * re-handshake and try again; only possible while [mayReconnect].
     */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatCompletionChunk>.streamOnce(
        request: ChatCompletionRequest,
        mayReconnect: Boolean,
    ): Boolean {
        val current = handshakeResult ?: run {
            lastError = EngineError(
                message = "Engine is not initialized or unavailable ($availabilityState)",
                type = "availability_error",
                code = "engine_unavailable"
            )
            return false
        }
        if (!localityEnforceable(current)) {
            lastError = EngineError(
                message = "Locality restriction ($locality) cannot be enforced by this Engine",
                type = "locality_error",
                code = "locality_unsupported"
            )
            return false
        }
        val streamingRequest = if (request.stream) request else request.copy(stream = true)
        val httpRequest = Request.Builder()
            .url("http://127.0.0.1:${current.port}/v1/chat/completions")
            .post(engineJson.encodeToString(streamingRequest).toRequestBody(jsonMediaType))
            .header("Authorization", "Bearer ${current.token}")
            .withLocality()
            .withVersions(current)
            .build()

        val call = httpClient.newCall(httpRequest)
        // The read below blocks, so cancelling the collector would not otherwise reach it: the
        // connection stayed open and Engine kept generating for a reader that had left.
        val onCancel = currentCoroutineContext()[Job]?.invokeOnCompletion { call.cancel() }
        try {
            call.execute().use { response ->
                if (response.code == 401 && mayReconnect) return true
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string()
                    val err = parseErrorDetail(errorBody, response.code)
                    lastError = err
                    throw EngineApiException(response.code, err)
                }
                val source = response.body?.source() ?: return@use
                val parser = SseFrameParser()
                while (!source.exhausted()) {
                    val line = source.readUtf8Line() ?: break
                    val payload = parser.onLine(line) ?: continue
                    if (payload == "[DONE]") break
                    if (payload.contains("\"error\"")) {
                        try {
                            val errWire = engineJson.decodeFromString<ErrorResponseWire>(payload)
                            val err = EngineError(
                                message = errWire.error.message,
                                type = errWire.error.type,
                                param = errWire.error.param,
                                code = errWire.error.code,
                                statusCode = 500
                            )
                            lastError = err
                            throw EngineApiException(500, err)
                        } catch (e: EngineApiException) {
                            throw e
                        } catch (_: Exception) {
                            // If decoding as error fails, continue to normal chunk decode
                        }
                    }
                    emit(engineJson.decodeFromString<ChatCompletionChunk>(payload))
                }
            }
        } catch (e: java.net.ConnectException) {
            if (mayReconnect) return true
            val err = EngineError(message = e.message ?: "Engine is not reachable", type = "io_error", code = "connection_failed")
            lastError = err
            throw EngineApiException(503, err, e)
        } catch (e: IOException) {
            // A read that failed because the collector was cancelled is a cancellation, not an error.
            currentCoroutineContext().ensureActive()
            val err = EngineError(
                message = e.message ?: "Connection error during streaming",
                type = "io_error",
                code = "connection_failed"
            )
            lastError = err
            throw EngineApiException(503, err, e)
        } finally {
            onCancel?.dispose()
        }
        return false
    }

    override suspend fun embeddings(request: EmbeddingsRequest): EmbeddingsResponse? = embeddingsResult(request).getOrNull()

    override suspend fun embeddingsResult(request: EmbeddingsRequest): Result<EmbeddingsResponse> = callResult {
        decodeOrFail<EmbeddingsResponse>(executeCall("embeddings", "POST", engineJson.encodeToString(request), allowReauth = true))
    }

    override suspend fun transcribe(request: TranscriptionRequest): TranscriptionResponse? = transcribeResult(request).getOrNull()

    override suspend fun transcribeResult(request: TranscriptionRequest): Result<TranscriptionResponse> = callResult {
        decodeOrFail<TranscriptionResponse>(executeCall("audio/transcriptions", "POST", engineJson.encodeToString(request), allowReauth = true))
    }

    override suspend fun speak(request: SpeechRequest): ByteArray? = callResult {
        // RFC-0117 amendment: JSON in, binary out. Same auth, locality and 401 re-handshake as the other calls.
        executeCallRaw("audio/speech", "POST", engineJson.encodeToString(request), allowReauth = true, record = true, rawBody = null) {
            it.body?.bytes() ?: ByteArray(0)
        }
    }.getOrNull()

    override suspend fun transcribeBytesResult(model: String, wav: ByteArray, language: String?, prompt: String?): Result<TranscriptionResponse> = callResult {
        // RFC-0117 amendment: the same route as JSON, multipart body. Same auth, locality and 401 re-handshake.
        val form = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", model)
            .addFormDataPart("file", "audio.wav", wav.toRequestBody("audio/wav".toMediaType()))
        language?.let { form.addFormDataPart("language", it) }
        prompt?.let { form.addFormDataPart("prompt", it) }
        decodeOrFail<TranscriptionResponse>(executeCall("audio/transcriptions", "POST", null, allowReauth = true, rawBody = form.build()))
    }

    override suspend fun decide(request: DecisionRequest): DecisionResponse? = decideResult(request).getOrNull()

    override suspend fun decideResult(request: DecisionRequest): Result<DecisionResponse> = callResult {
        decodeOrFail<DecisionResponse>(executeCall("systemone", "POST", engineJson.encodeToString(request), allowReauth = true))
    }

    override fun close() {
        availabilityState = EngineAvailability.NotInstalled
        handshakeResult = null
        handshakeGeneration++
        cachedCapabilities = null
        lastError = null
    }

    /**
     * Internal: Set handshake result (for testing or direct initialization).
     */
    internal fun setHandshakeResult(result: HandshakeResponse) {
        handshakeResult = result
        availabilityState = EngineAvailability.Available
        handshakeGeneration++
        cachedCapabilities = null
    }
}

/**
 * Outcome of a handshake performed by something other than the Binder transport — see
 * [AidosEngineClientFactory.create]. Mirrors the Binder handshake's statuses (sdk/CONTRACT.md).
 */
sealed interface EngineHandshakeResult {
    /** Engine accepted the caller: [port] and [token] are for `http://127.0.0.1:<port>/v1/`. */
    data class Approved(
        val port: Int,
        val token: String,
        val capabilities: EngineCapabilities,
        val apiVersion: Int = 1,
        /** Oldest API version the Engine still serves; null means only [apiVersion]. */
        val minApiVersion: Int? = null,
        val engineVersion: String? = null
    ) : EngineHandshakeResult

    data object NotInstalled : EngineHandshakeResult
    data object PendingApproval : EngineHandshakeResult
    data object Denied : EngineHandshakeResult
    data object Failed : EngineHandshakeResult
}

/**
 * A way to obtain a handshake other than Android's Binder call. The reason it exists: Engine
 * itself, in its own process (its Test Chat screen, its desktop debug app), talks to its own
 * `/v1/` endpoint through this same client rather than a private duplicate of the transport, and
 * tests can drive the client against a real Engine host on the JVM.
 */
fun interface EngineHandshakeSource {
    suspend fun handshake(): EngineHandshakeResult
}

/**
 * Factory for creating Aidos Engine client instances (RFC-0103).
 */
object AidosEngineClientFactory {
    /**
     * Create a client with no way to reach Engine — there is no Binder on the jvm() target this
     * factory also builds for, so [AidosEngineClient.initialize] always returns `false`. On
     * Android, use `AndroidAidosEngineClientFactory.createClient(context)` (sdk/client's androidMain)
     * instead, which performs the real handshake.
     */
    fun createClient(): AidosEngineClient = EngineClientImpl()

    /**
     * Create a client whose handshake comes from [source] instead of Binder. Behaviour after the
     * handshake (transport, 401 → re-handshake, streaming, availability) is identical.
     */
    fun create(
        source: EngineHandshakeSource,
        requiredApiVersion: Int = 1,
        locality: Locality? = null,
        supportedApiVersions: IntRange = requiredApiVersion..requiredApiVersion
    ): AidosEngineClient =
        EngineClientImpl(
            handshakePerformer = { source.handshake().toOutcome() },
            supportedApiVersions = supportedApiVersions,
            locality = locality
        )
}

private fun EngineHandshakeResult.toOutcome(): HandshakeOutcome = when (this) {
    is EngineHandshakeResult.Approved -> HandshakeOutcome.Approved(
        HandshakeResponse(
            port = port,
            token = token,
            apiVersion = apiVersion,
            minApiVersion = minApiVersion,
            engineVersion = engineVersion,
            capabilities = CapabilitiesResponse(
                endpoints = capabilities.endpoints,
                models = capabilities.models.map {
                    ModelInfoResponse(it.id, it.kind, it.contextWindow, it.quantization, it.placement)
                }
            )
        )
    )
    EngineHandshakeResult.NotInstalled -> HandshakeOutcome.NotInstalled
    EngineHandshakeResult.PendingApproval -> HandshakeOutcome.PendingApproval
    EngineHandshakeResult.Denied -> HandshakeOutcome.Denied
    EngineHandshakeResult.Failed -> HandshakeOutcome.Failed
}
