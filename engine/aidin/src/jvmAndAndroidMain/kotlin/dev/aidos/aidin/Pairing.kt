package dev.aidos.aidin

import java.security.SecureRandom
import java.util.Base64

/** AIDIN wire protocol version, advertised in discovery and invites (RFC-0105 "Discovery"). */
const val AIDIN_PROTOCOL_VERSION = 1

/**
 * What one device shows as a QR code so another can pair with it or enrol it in a group
 * (RFC-0105 "Trust"). It carries the public key, so the scanning side can derive and check the
 * node id itself, and a one-time secret that both sides mix into the confirmation code.
 *
 * An invite is not a credential: nothing is trusted until both people have compared the
 * six-digit [Sas] code and confirmed.
 */
data class PairingInvite(
    val publicKey: ByteArray,
    val secret: ByteArray,
    val addresses: List<String>,
    val port: Int,
    val protocolVersion: Int = AIDIN_PROTOCOL_VERSION,
) {
    val nodeId: NodeId get() = Keys.nodeIdOf(publicKey)

    init {
        require(secret.size == SECRET_BYTES) { "invite secret must be $SECRET_BYTES bytes" }
        require(port in 1..65535) { "bad port $port" }
    }

    /** `aidin:pair?v=1&k=<key>&s=<secret>&a=<addr,addr>&p=<port>`, base64url without padding. */
    fun toUri(): String = buildString {
        append(SCHEME).append("?v=").append(protocolVersion)
        append("&k=").append(b64(publicKey))
        append("&s=").append(b64(secret))
        append("&a=").append(addresses.joinToString(","))
        append("&p=").append(port)
    }

    override fun equals(other: Any?): Boolean =
        other is PairingInvite && publicKey.contentEquals(other.publicKey) && secret.contentEquals(other.secret) &&
            addresses == other.addresses && port == other.port && protocolVersion == other.protocolVersion

    override fun hashCode(): Int = publicKey.contentHashCode() * 31 + port

    companion object {
        const val SECRET_BYTES = 16
        private const val SCHEME = "aidin:pair"
        private val random = SecureRandom()

        fun create(key: SigningKey, addresses: List<String>, port: Int): PairingInvite =
            PairingInvite(key.publicKey, ByteArray(SECRET_BYTES).also(random::nextBytes), addresses, port)

        /** Null for anything that is not a well-formed invite of a protocol version we speak. */
        fun parse(uri: String): PairingInvite? {
            if (!uri.startsWith("$SCHEME?")) return null
            val params = uri.removePrefix("$SCHEME?").split('&').mapNotNull {
                val i = it.indexOf('=')
                if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
            }.toMap()
            return try {
                val version = params["v"]?.toInt() ?: return null
                if (version != AIDIN_PROTOCOL_VERSION) return null
                PairingInvite(
                    publicKey = unb64(params["k"] ?: return null),
                    secret = unb64(params["s"] ?: return null),
                    addresses = params["a"].orEmpty().split(',').filter { it.isNotBlank() },
                    port = params["p"]?.toInt() ?: return null,
                    protocolVersion = version,
                )
            } catch (e: IllegalArgumentException) {
                null
            }
        }

        private fun b64(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        private fun unb64(text: String) = Base64.getUrlDecoder().decode(text)
    }
}

/**
 * Short authentication string: six digits both screens show after the scanning side has
 * connected (RFC-0105 "Pairing"). Equal codes mean both devices hold the same two public keys and
 * the same invite secret, so nobody in between swapped a key. Order-independent: both sides
 * compute it from (my key, your key) and get the same digits.
 */
object Sas {
    fun code(publicKeyA: ByteArray, publicKeyB: ByteArray, secret: ByteArray): String {
        val (first, second) = listOf(publicKeyA, publicKeyB).sortedWith { x, y -> compareBytes(x, y) }
        val digest = Keys.sha256(
            Canonical("aidin-sas-v1").bytes(first).bytes(second).bytes(secret).toByteArray(),
        )
        val number = ((digest[0].toLong() and 0xff) shl 24) or ((digest[1].toLong() and 0xff) shl 16) or
            ((digest[2].toLong() and 0xff) shl 8) or (digest[3].toLong() and 0xff)
        return (number % 1_000_000).toString().padStart(6, '0')
    }

    private fun compareBytes(x: ByteArray, y: ByteArray): Int {
        for (i in 0 until minOf(x.size, y.size)) {
            val c = (x[i].toInt() and 0xff) - (y[i].toInt() and 0xff)
            if (c != 0) return c
        }
        return x.size - y.size
    }
}

/**
 * A pairwise trust record (RFC-0105 "(a) Pairing"): stored on each side after both people
 * confirmed the [Sas] code. Revocation is deleting it.
 */
data class PairRecord(
    val publicKey: ByteArray,
    val label: String,
    val createdAtMillis: Long,
    val scopes: Set<Scope> = Scope.ALL,
) {
    val nodeId: NodeId get() = Keys.nodeIdOf(publicKey)

    override fun equals(other: Any?): Boolean =
        other is PairRecord && publicKey.contentEquals(other.publicKey) && label == other.label &&
            createdAtMillis == other.createdAtMillis && scopes == other.scopes

    override fun hashCode(): Int = publicKey.contentHashCode()
}
