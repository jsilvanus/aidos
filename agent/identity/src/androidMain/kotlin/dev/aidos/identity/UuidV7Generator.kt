package dev.aidos.identity

import dev.aidos.kernel.IdGenerator
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random

/**
 * Android implementation of UUIDv7 (RFC-0054, draft-ietf-uuidrev-rfc4122bis method 1).
 *
 * Identical to the JVM/desktop actual (`jvmMain`) — `java.util.concurrent.atomic.AtomicInteger`,
 * `kotlin.random.Random`, and `System.currentTimeMillis()` are all part of the Android runtime,
 * so nothing here needs to differ. Kept as a separate `actual` rather than a shared intermediate
 * source set to match this module's existing per-target convention (no other `expect`/`actual`
 * pair in this codebase uses an intermediate source set).
 *
 * Layout (128 bits):
 *   [0..47]  unix_ts_ms     — ms since epoch
 *   [48..51] ver = 0b0111   — version 7
 *   [52..63] seq_hi         — 12 high bits of sub-ms sequence
 *   [64..65] var = 0b10     — RFC 4122 variant
 *   [66..79] seq_lo         — 14 low bits of sequence
 *   [80..127] rand          — 48 random bits
 */
actual class UuidV7Generator actual constructor() : IdGenerator {

    private val random = Random.Default
    private var lastMs = 0L
    private val counter = AtomicInteger(0)
    private val lock = Any()

    override fun next(): String {
        val ms: Long
        val seq: Int
        synchronized(lock) {
            val now = System.currentTimeMillis()
            when {
                now > lastMs -> {
                    lastMs = now
                    counter.set(0)
                    ms = now; seq = 0
                }
                now == lastMs -> {
                    val s = counter.incrementAndGet()
                    if (s >= (1 shl 26)) {
                        var spin = now
                        while (spin <= lastMs) spin = System.currentTimeMillis()
                        lastMs = spin
                        counter.set(0)
                        ms = spin; seq = 0
                    } else {
                        ms = now; seq = s
                    }
                }
                else -> {
                    ms = lastMs; seq = counter.incrementAndGet()
                }
            }
        }

        val rand = random.nextLong() and 0x0000_FFFF_FFFF_FFFFL
        val hi = (ms shl 16) or 0x7000L or ((seq.toLong() ushr 14) and 0x0FFFL)
        val lo = Long.MIN_VALUE or ((seq.toLong() and 0x3FFFL) shl 48) or rand
        return formatUuid(hi, lo)
    }

    private fun formatUuid(hi: Long, lo: Long): String {
        val bytes = ByteArray(16)
        for (i in 0..7) bytes[i] = ((hi ushr ((7 - i) * 8)) and 0xFF).toByte()
        for (i in 0..7) bytes[i + 8] = ((lo ushr ((7 - i) * 8)) and 0xFF).toByte()
        return buildString(36) {
            for (i in bytes.indices) {
                append(HEX[bytes[i].toInt() ushr 4 and 0xF])
                append(HEX[bytes[i].toInt() and 0xF])
                if (i == 3 || i == 5 || i == 7 || i == 9) append('-')
            }
        }
    }

    companion object {
        private const val HEX = "0123456789abcdef"
    }
}
