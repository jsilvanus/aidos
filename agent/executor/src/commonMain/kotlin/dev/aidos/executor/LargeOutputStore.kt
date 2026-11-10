package dev.aidos.executor

/**
 * Content-addressed storage for large Attempt output payloads (RFC-0056, "Deduplication").
 *
 * The executor depends on this seam, not on a file layout: `commonMain` code has no file API, and
 * the implementation (`dev.aidos.retention.BlobStore`, wired by the daemon's composition root)
 * lives on the JVM. The contract is:
 *
 * - [externalize] returns a small reference string to store in `attempts.output_snapshot` in place
 *   of [text], or null when [text] is small enough to stay inline. Identical text yields the same
 *   blob, with its reference count incremented once per call.
 * - [resolve] turns whatever was stored back into the original text. A value that is not a
 *   reference (an inline snapshot, or a compacted one) is returned unchanged, so callers can
 *   resolve unconditionally.
 */
interface LargeOutputStore {
    fun externalize(text: String): String?
    fun resolve(stored: String): String
}
