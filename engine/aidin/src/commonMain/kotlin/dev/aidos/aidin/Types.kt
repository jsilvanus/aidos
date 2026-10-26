package dev.aidos.aidin

/**
 * RFC-0105 "Node identity": a node is one Engine install, named by a hash of its public key.
 * Stable across address changes; a reinstall makes a new key and therefore a new node.
 */
data class NodeId(val value: String) {
    init {
        require(value.length == LENGTH && value.all { it in Base32.ALPHABET }) { "not a node id: $value" }
    }

    override fun toString(): String = value

    companion object {
        /** 26 base32 characters = 130 bits of the SHA-256 of the public key. */
        const val LENGTH = 26
    }
}

/**
 * RFC-0105 "Trust": what a trusted peer may do. A credential carries a set of these, and every
 * authorisation asks for exactly one.
 */
enum class Scope(val wire: String) {
    /** The peer may send me requests. */
    DELEGATE("delegate"),
    /** The peer may serve my requests. */
    SERVE("serve"),
    /** The peer may be a helper (hold layers) for a split model I anchor. */
    HELP("help"),
    /** The peer may be the anchor of a split model I take part in or use. */
    ANCHOR("anchor");

    companion object {
        val ALL: Set<Scope> = entries.toSet()
        fun fromWire(value: String): Scope? = entries.firstOrNull { it.wire == value }
    }
}

/**
 * Where a credential comes from. Personal pairs and each group are separate trust domains:
 * a credential in one never authorises anything in another (RFC-0105, aidin.md).
 */
sealed interface TrustDomain {
    data object Pairing : TrustDomain
    data class Group(val groupId: GroupId) : TrustDomain
}

data class GroupId(val value: String) {
    init {
        require(value.length == NodeId.LENGTH && value.all { it in Base32.ALPHABET }) { "not a group id: $value" }
    }

    override fun toString(): String = value
}

/** RFC 4648 base32, lower case, no padding. Used for ids so they survive QR codes, DNS-SD and URLs. */
object Base32 {
    const val ALPHABET = "abcdefghijklmnopqrstuvwxyz234567"

    fun encode(bytes: ByteArray): String {
        val out = StringBuilder((bytes.size * 8 + 4) / 5)
        var buffer = 0
        var bits = 0
        for (b in bytes) {
            buffer = (buffer shl 8) or (b.toInt() and 0xff)
            bits += 8
            while (bits >= 5) {
                out.append(ALPHABET[(buffer shr (bits - 5)) and 31])
                bits -= 5
            }
        }
        if (bits > 0) out.append(ALPHABET[(buffer shl (5 - bits)) and 31])
        return out.toString()
    }
}
