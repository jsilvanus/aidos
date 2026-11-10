package dev.aidos.retention

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Compaction (RFC-0056): replaces a large AGED payload with a small structured summary that
 * keeps the answerable questions — when it was compacted, how big it was, its SHA-256, a
 * one-line summary, and the first and last [HEAD_TAIL_CHARS] characters.
 *
 * ```
 * before: attempts.output_snapshot = <380KB of test runner stdout>
 * after:  {"compacted_at":"…","original_bytes":389421,"original_sha256":"…","summary":"…",
 *          "head":"<first 2KB>","tail":"<last 2KB>"}
 * ```
 *
 * What it touches: `attempts.input_snapshot`, `attempts.output_snapshot`,
 * `attempts.prompt_package_json` and `events.payload`, nothing else. Event headers (type,
 * sequence, timestamp, source, topic) are columns of their own and are never modified, so the
 * structural record stays whole. The audit log, capability tables, provenance edges and
 * `content_nodes` are PERMANENT and this class has no statement that writes to them.
 *
 * Properties (all tested):
 * - **Idempotent.** A compacted value starts with `{"compacted_at"` and is never selected again.
 * - **Audited.** Every compaction inserts a `compactions` row with the original size and hash in
 *   the same transaction as the update, so the trail says what was discarded and when.
 * - **Safe for live work.** Attempts of a Run that is PENDING, RUNNING or YIELDED, and events
 *   that triggered such a Run, are never touched (a parked Run rebuilds its history from them).
 * - **Bounded, cancellable.** Work is read in batches of [RetentionPolicy.batchSize], a cancellation
 *   check ([yield]) runs for every row, and each batch commits as one transaction, so an
 *   interrupted pass redoes at most one batch. A pass stops after
 *   [RetentionPolicy.maxBatchesPerPass] batches and reports `needsAnotherPass`.
 * - **Never grows a value.** A payload too small to shrink is left alone.
 * - **Blob-aware.** An output stored by [BlobStore] is read back for its head/tail/hash, and its
 *   reference is released in the same transaction, which is how deduplicated content is
 *   eventually reclaimed.
 */
class Compactor(
    private val driver: SqlDriver,
    private val blobs: BlobStore?,
    private val idGen: () -> String,
) {

    /**
     * One compaction pass over [projectId]. [maxAgeDays] caps every window (Stage 1 passes 7);
     * null uses the policy's windows as they are.
     */
    suspend fun compact(
        projectId: String,
        policy: RetentionPolicy,
        now: Instant = Instant.now(),
        maxAgeDays: Int? = null,
    ): CompactionResult {
        val result = CompactionResult()
        if (policy.neverCompact) return result
        val budget = intArrayOf(policy.maxBatchesPerPass)
        for (obj in RetainedObject.aged) {
            val configured = policy.daysFor(obj) ?: continue
            val days = if (maxAgeDays != null) minOf(configured, maxAgeDays) else configured
            if (!compactObject(projectId, obj, days, policy, maxAgeDays, now, budget, result)) break
        }
        return result
    }

    /** Returns false when the batch budget ran out, so the caller stops starting new objects. */
    private suspend fun compactObject(
        projectId: String,
        obj: RetainedObject,
        days: Int,
        policy: RetentionPolicy,
        maxAgeDays: Int?,
        now: Instant,
        budget: IntArray,
        result: CompactionResult,
    ): Boolean {
        // Oversized outputs age out sooner (RFC-0056: tool result payloads over 64KB, 7 days).
        val earliestDays =
            if (obj == RetainedObject.ATTEMPT_OUTPUT_SNAPSHOT) minOf(days, policy.toolResultDays.capBy(maxAgeDays)) else days
        val earliestCutoff = now.minus(earliestDays.toLong(), ChronoUnit.DAYS).toString()
        val cutoffFor = { bytes: Long ->
            val d = if (obj == RetainedObject.ATTEMPT_OUTPUT_SNAPSHOT && bytes > policy.toolResultThresholdBytes) earliestDays else days
            now.minus(d.toLong(), ChronoUnit.DAYS).toString()
        }

        var lastId = ""
        while (true) {
            if (budget[0] <= 0) {
                result.needsAnotherPass = true
                return false
            }
            val rows = select(obj, projectId, lastId, earliestCutoff, policy.batchSize)
            if (rows.isEmpty()) return true
            budget[0]--
            lastId = rows.last().id

            val planned = ArrayList<Planned>()
            for (row in rows) {
                yield() // cancellation point; an Android execution window can stop a pass here
                plan(row, cutoffFor, now)?.let { planned.add(it) }
            }
            if (planned.isNotEmpty()) apply(obj, planned, now, result)
            if (rows.size < policy.batchSize) return true
        }
    }

    private class Row(val id: String, val value: String, val timestamp: String)

    private class Planned(
        val id: String,
        val original: String,
        val replacement: String,
        val originalBytes: Long,
        val retainedBytes: Long,
        val sha256: String,
        val releaseHash: String?,
    )

    private fun select(obj: RetainedObject, projectId: String, afterId: String, cutoff: String, limit: Int): List<Row> {
        val read = { c: app.cash.sqldelight.db.SqlCursor -> Row(c.getString(0)!!, c.getString(1)!!, c.getString(2) ?: "") }
        return if (obj == RetainedObject.EVENT_PAYLOAD) {
            driver.queryList(
                "SELECT e.id, e.payload, e.timestamp FROM events e " +
                    "WHERE e.project_id = ? AND e.id > ? AND e.timestamp < ? " +
                    "AND length(e.payload) > ? AND e.payload NOT LIKE '$COMPACTED_PREFIX%' " +
                    "AND e.id NOT IN (SELECT trigger_event_id FROM runs WHERE state IN ($LIVE_RUN_STATES)) " +
                    "ORDER BY e.id LIMIT ?",
                parameters = 5,
                bind = { bindString(0, projectId); bindString(1, afterId); bindString(2, cutoff); bindLong(3, MIN_COMPACT_CHARS); bindLong(4, limit.toLong()) },
                read = read,
            )
        } else {
            val col = columnOf(obj)
            driver.queryList(
                "SELECT a.id, a.$col, COALESCE(a.ended_at, a.started_at) FROM attempts a " +
                    "JOIN tasks t ON t.id = a.task_id JOIN runs r ON r.id = t.run_id " +
                    "WHERE r.project_id = ? AND a.id > ? AND COALESCE(a.ended_at, a.started_at) < ? " +
                    "AND a.state <> 'RUNNING' AND r.state NOT IN ($LIVE_RUN_STATES) " +
                    "AND a.$col IS NOT NULL AND a.$col NOT LIKE '$COMPACTED_PREFIX%' " +
                    "AND (length(a.$col) > ? OR a.$col LIKE '$BLOB_REF_PREFIX%') " +
                    "ORDER BY a.id LIMIT ?",
                parameters = 5,
                bind = { bindString(0, projectId); bindString(1, afterId); bindString(2, cutoff); bindLong(3, MIN_COMPACT_CHARS); bindLong(4, limit.toLong()) },
                read = read,
            )
        }
    }

    private fun plan(row: Row, cutoffFor: (Long) -> String, now: Instant): Planned? {
        val ref = BlobStore.parseReference(row.value)
        val content: String?
        val originalBytes: Long
        val sha: String
        if (ref != null) {
            val bytes = blobs?.get(ref.first)
            content = bytes?.toString(Charsets.UTF_8)
            originalBytes = ref.second
            sha = ref.first
        } else {
            val bytes = row.value.toByteArray(Charsets.UTF_8)
            content = row.value
            originalBytes = bytes.size.toLong()
            sha = BlobStore.sha256Hex(bytes)
        }
        if (row.timestamp >= cutoffFor(originalBytes)) return null

        val replacement = compactedJson(content, originalBytes, sha, now)
        val retained = replacement.toByteArray(Charsets.UTF_8).size.toLong()
        if (ref == null && retained >= originalBytes) return null // never grow a value
        return Planned(row.id, row.value, replacement, originalBytes, retained, sha, ref?.first)
    }

    private fun apply(obj: RetainedObject, planned: List<Planned>, now: Instant, result: CompactionResult) {
        val (table, col) = if (obj == RetainedObject.EVENT_PAYLOAD) "events" to "payload" else "attempts" to columnOf(obj)
        val toDelete = ArrayList<String>()
        driver.inTransaction {
            for (p in planned) {
                // Guarded on the old value: if the row changed since it was read, leave it for the next pass.
                val changed = driver.update("UPDATE $table SET $col = ? WHERE id = ? AND $col = ?", 3) {
                    bindString(0, p.replacement); bindString(1, p.id); bindString(2, p.original)
                }
                if (changed == 0L) continue
                driver.update(
                    "INSERT INTO compactions (id, object_class, object_id, original_bytes, retained_bytes, original_sha256, compacted_at) " +
                        "VALUES (?, ?, ?, ?, ?, ?, ?)",
                    parameters = 7,
                ) {
                    bindString(0, idGen()); bindString(1, obj.key); bindString(2, p.id)
                    bindLong(3, p.originalBytes); bindLong(4, p.retainedBytes); bindString(5, p.sha256); bindString(6, now.toString())
                }
                if (p.releaseHash != null && blobs != null && blobs.releaseRow(p.releaseHash)) toDelete.add(p.releaseHash)
                result.compacted++
                result.bytesFreed += p.originalBytes - p.retainedBytes
                result.byObject.merge(obj.key, 1, Int::plus)
            }
        }
        // After commit: a rolled-back transaction must not have deleted a file still referenced.
        toDelete.forEach { blobs?.deleteFile(it) }
    }

    private fun columnOf(obj: RetainedObject): String = when (obj) {
        RetainedObject.ATTEMPT_INPUT_SNAPSHOT -> "input_snapshot"
        RetainedObject.ATTEMPT_OUTPUT_SNAPSHOT -> "output_snapshot"
        RetainedObject.PROMPT_PACKAGE -> "prompt_package_json"
        else -> error("$obj is not compacted by column")
    }

    private fun Int.capBy(max: Int?): Int = if (max == null) this else minOf(this, max)

    companion object {
        const val HEAD_TAIL_CHARS = 2048
        const val COMPACTED_PREFIX = "{\"compacted_at\":"
        private const val BLOB_REF_PREFIX = "{\"blob_ref\":"
        private const val LIVE_RUN_STATES = "'PENDING','RUNNING','YIELDED'"

        /** Head, tail, two escaped fields and framing: below this a value cannot shrink. */
        private const val MIN_COMPACT_CHARS = 4608L

        /** The compacted form. [content] is null when a referenced blob is gone. */
        fun compactedJson(content: String?, originalBytes: Long, sha256: String, now: Instant): String =
            buildJsonObject {
                put("compacted_at", now.toString())
                put("original_bytes", originalBytes)
                put("original_sha256", sha256)
                put("summary", summarize(content, originalBytes))
                put("head", if (content == null) "" else safeHead(content))
                put("tail", if (content == null) "" else safeTail(content))
            }.toString()

        private fun summarize(content: String?, originalBytes: Long): String {
            if (content == null) return "$originalBytes bytes; content no longer available"
            val lines = content.count { it == '\n' } + 1
            val keys = runCatching { Json.parseToJsonElement(content) as? JsonObject }.getOrNull()
                ?.keys?.take(8)?.joinToString(", ")
            return "$lines lines, $originalBytes bytes" + if (keys.isNullOrEmpty()) "" else "; keys: $keys"
        }

        private fun safeHead(s: String): String {
            if (s.length <= HEAD_TAIL_CHARS) return s
            var end = HEAD_TAIL_CHARS
            if (s[end - 1].isHighSurrogate()) end--
            return s.substring(0, end)
        }

        private fun safeTail(s: String): String {
            if (s.length <= HEAD_TAIL_CHARS) return s
            var start = s.length - HEAD_TAIL_CHARS
            if (s[start].isLowSurrogate()) start++
            return s.substring(start)
        }
    }
}

/** What a compaction pass did. */
class CompactionResult {
    var compacted: Int = 0
    var bytesFreed: Long = 0
    var needsAnotherPass: Boolean = false
    val byObject: MutableMap<String, Int> = LinkedHashMap()
}
