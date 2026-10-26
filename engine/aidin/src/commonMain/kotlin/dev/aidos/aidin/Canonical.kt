package dev.aidos.aidin

/**
 * Deterministic byte encoding of signed AIDIN statements (membership certificates, revocation
 * lists). Every field is length-prefixed and sets are sorted, so two implementations produce the
 * same bytes for the same statement without depending on JSON key order or whitespace. The first
 * field is always a type tag, so a signature over one kind of statement can never verify as
 * another.
 */
class Canonical(tag: String) {
    private val out = ArrayList<Byte>(256)

    init {
        string(tag)
    }

    fun string(value: String): Canonical = bytes(value.encodeToByteArray())

    fun bytes(value: ByteArray): Canonical {
        int(value.size)
        for (b in value) out.add(b)
        return this
    }

    fun long(value: Long): Canonical {
        for (shift in 56 downTo 0 step 8) out.add((value ushr shift).toByte())
        return this
    }

    fun strings(values: Collection<String>): Canonical {
        val sorted = values.sorted()
        int(sorted.size)
        sorted.forEach { string(it) }
        return this
    }

    fun toByteArray(): ByteArray = out.toByteArray()

    private fun int(value: Int) {
        for (shift in 24 downTo 0 step 8) out.add((value ushr shift).toByte())
    }
}
