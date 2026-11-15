package dev.aidos.testing

/**
 * Reproducible seeded ID generator for tests (RFC-0038).
 * Produces deterministic UUID-shaped strings so golden files and snapshots stay stable.
 */
class SeededIdGenerator(
    private val prefix: String = "01940000-0000-7000-8000",
    private var counter: Long = 0L,
) {
    fun next(): String {
        val current = ++counter
        val suffix = current.toString(16).padStart(12, '0')
        return "$prefix-$suffix"
    }

    fun reset(to: Long = 0L) {
        counter = to
    }
}
