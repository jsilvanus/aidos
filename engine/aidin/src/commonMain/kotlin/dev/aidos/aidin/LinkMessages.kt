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
