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
    ) : LinkMessage

    @Serializable
    @SerialName("heartbeat")
    data class Beat(val heartbeat: Heartbeat) : LinkMessage

    @Serializable
    @SerialName("revocations")
    data class Revocations(val list: WireRevocationList) : LinkMessage

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
