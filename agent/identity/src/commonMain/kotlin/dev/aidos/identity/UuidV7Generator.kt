package dev.aidos.identity

import dev.aidos.kernel.IdGenerator

/**
 * UUIDv7 generator (RFC-0054).
 *
 * UUIDv7 is time-ordered: the first 48 bits are milliseconds since the Unix epoch, the next
 * 12 bits are a sub-millisecond sequence counter, and the remaining 62 bits are random.
 *
 * - Monotonic within a process (counter prevents same-ms collisions).
 * - Unique across concurrent runtimes (48-bit random suffix).
 * - Time-ordered (SQLite B-tree exploits this for free).
 *
 * Platform-specific. The JVM implementation lives in jvmMain; Android arrives with Phase 4.
 */
expect class UuidV7Generator() : IdGenerator
