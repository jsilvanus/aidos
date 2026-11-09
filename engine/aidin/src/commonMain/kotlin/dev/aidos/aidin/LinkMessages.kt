package dev.aidos.aidin

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Messages on the authenticated peer link (RFC-0105 "Peer link"). Each is one length-prefixed
 * JSON frame. Binary values (keys, signatures) are base64url strings.
 *
 * The TLS handshake proves which key is on the other end; [Hello] then carries the group
 * certificates that key holds, and only after the receiving side's [TrustStore] admits it does
 * anything else flow.
 */
@Serializable
sealed interface LinkMessage {
    @Serializable
    @SerialName("hello")
    data class Hello(
        val protocolVersion: Int,
        val certificates: List<WireCertificate> = emptyList(),
        /**
         * Only when connecting to pair with (or enrol) a device whose invite was scanned: an HMAC,
         * keyed by the invite secret, over both public keys. Lets that device accept a link from a
         * key it does not trust yet, for pairing messages only.
         */
        val pairingProof: String? = null,
    ) : LinkMessage

    @Serializable
    @SerialName("heartbeat")
    data class Beat(val heartbeat: Heartbeat) : LinkMessage

    @Serializable
    @SerialName("revocations")
    data class Revocations(val list: WireRevocationList) : LinkMessage

    /**
     * Renewed group membership certificate pushed by a group admin while the member device is seen
     * (RFC-0105: default validity 30 days, renewed while the device is seen).
     */
    @Serializable
    @SerialName("cert-renewal")
    data class CertificateRenewal(val certificate: WireCertificate) : LinkMessage

    /**
     * Pairing link only, first message from the side that scanned the invite: what it wants.
     * [group] set means "I am the admin of this group and want to enrol you"; null means pairing.
     * The scanned device shows this before its user confirms anything.
     */
    @Serializable
    @SerialName("pair-offer")
    data class PairOffer(val group: String? = null) : LinkMessage

    /**
     * Pairing link only: this side's user compared the six-digit code and confirmed. [scopes] is
     * what this side grants the other (RFC-0105 pairing default: all). [group] is set when this
     * side is a group admin enrolling the other side rather than pairing with it.
     */
    @Serializable
    @SerialName("pair-confirm")
    data class PairConfirm(val scopes: List<String>, val group: String? = null) : LinkMessage

    /** Pairing link only, sent by a group admin after its user confirmed: the new member's certificate. */
    @Serializable
    @SerialName("enrolment")
    data class Enrolment(
        val certificate: WireCertificate,
        val revocations: WireRevocationList? = null,
    ) : LinkMessage

    /**
     * RFC-0105 Phase 2 Delegation request over the authenticated peer link:
     * `/aidin/v1/route`, `/aidin/v1/chat/completions`, `/aidin/v1/embeddings`,
     * `/aidin/v1/audio/transcriptions`, `/aidin/v1/systemone`.
     */
    @Serializable
    @SerialName("delegate-req")
    data class DelegateRequest(
        val requestId: String,
        val endpoint: String,
        val bodyJson: String,
        val locality: String,
        val hop: Int = 1,
        val planVersion: Long? = null,
        val stream: Boolean = false,
    ) : LinkMessage

    /** Non-streaming delegation response (or pre-stream error response). */
    @Serializable
    @SerialName("delegate-resp")
    data class DelegateResponse(
        val requestId: String,
        val status: Int,
        val bodyJson: String,
        val placement: String? = null,
        val route: String? = null,
    ) : LinkMessage

    /** Streaming SSE chunk or terminal event for a delegated streaming chat completion. */
    @Serializable
    @SerialName("delegate-stream")
    data class DelegateStreamEvent(
        val requestId: String,
        val sseData: String,
        val done: Boolean = false,
        val placement: String? = null,
    ) : LinkMessage

    /** RFC-0105 Phase 3: Anchor assigns a versioned layer-split plan to a helper. */
    @Serializable
    @SerialName("plan-assign")
    data class PlanAssign(val plan: WireSplitPlan) : LinkMessage

    /** RFC-0105 Phase 3: Helper acknowledges (or refuses) a split plan assignment. */
    @Serializable
    @SerialName("plan-ack")
    data class PlanAck(
        val planId: String,
        val version: Long,
        val accepted: Boolean,
        val reason: String? = null,
    ) : LinkMessage

    /** RFC-0105 Phase 3: Anchor opens a loopback tunnel stream to the helper's `ggml-rpc-server`. */
    @Serializable
    @SerialName("tunnel-open")
    data class TunnelOpen(
        val streamId: String,
        val planId: String,
        val planVersion: Long,
    ) : LinkMessage

    /** RFC-0105 Phase 3: Forwarded bytes on an open loopback tunnel stream (base64url encoded). */
    @Serializable
    @SerialName("tunnel-data")
    data class TunnelData(
        val streamId: String,
        val data: String,
    ) : LinkMessage

    /** RFC-0105 Phase 3: Closes an open loopback tunnel stream. */
    @Serializable
    @SerialName("tunnel-close")
    data class TunnelClose(
        val streamId: String,
        val reason: String? = null,
    ) : LinkMessage

    /** Closing on purpose. [reason] is for logs, not for users. */
    @Serializable
    @SerialName("bye")
    data class Bye(val reason: String) : LinkMessage
}

@Serializable
data class WireCertificate(
    val groupKey: String,
    val nodeKey: String,
    val roles: List<String>,
    val notBefore: Long,
    val notAfter: Long,
    val signature: String,
)

@Serializable
data class WireRevocationList(
    val groupKey: String,
    val serial: Long,
    val revoked: List<String>,
    val issuedAt: Long,
    val signature: String,
)

@Serializable
data class WireHelperShare(
    val nodeId: String,
    val layerStart: Int,
    val layerEndExclusive: Int,
    val allocatedRamBytes: Long,
    val tensorSplitWeight: Float,
)

@Serializable
data class WireSplitPlan(
    val planId: String,
    val version: Long,
    /** `"pair"` or `"group:<groupId>"`. */
    val domain: String,
    val modelId: String,
    val modelDigest: String,
    val anchorNodeId: String,
    val helpers: List<WireHelperShare>,
    val anchorLayerStart: Int,
    val anchorLayerEndExclusive: Int,
    val anchorAllocatedRamBytes: Long,
    val tensorSplitArg: String,
    val contextLength: Int,
    val llamaCppBuildId: String,
)

object LinkCodec {
    /** Frames larger than this close the link: no control message needs more. */
    const val MAX_FRAME_BYTES = 1 shl 20

    val json = Json {
        ignoreUnknownKeys = true
        classDiscriminator = "type"
        encodeDefaults = true
    }

    fun encode(message: LinkMessage): ByteArray = json.encodeToString(LinkMessage.serializer(), message).encodeToByteArray()

    /** Null for anything that is not a message this version understands. */
    fun decode(bytes: ByteArray): LinkMessage? =
        try {
            json.decodeFromString(LinkMessage.serializer(), bytes.decodeToString())
        } catch (e: Exception) {
            null
        }
}
