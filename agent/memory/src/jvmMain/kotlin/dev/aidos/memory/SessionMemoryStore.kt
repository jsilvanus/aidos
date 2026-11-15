package dev.aidos.memory

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.TrustLevel
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.util.UUID

/**
 * Session memory store (RFC-0026, RFC-0046, M16b).
 *
 * Enforced invariants (D32, D33):
 * - Nothing summarizes a session into memory. The model's `SUMMARY` kind does not exist.
 * - [write] accepts only `FACT`, `DECISION`, or `TASK_STATE` entries.
 * - `source_refs` must be non-empty (RFC-0026 provenance requirement).
 * - `trust_level` is the max taint of the entry's sources — set by the caller, who has
 *   the Run taint level.
 * - Scope is always `SESSION` on creation; promotion to `PROJECT` is a user-only action
 *   (D33 / [promoteToProject]).
 * - A `TASK_STATE` entry may never be promoted to `PROJECT` (schema-checked).
 * - An `UNTRUSTED` entry may never be promoted to `PROJECT` (schema-checked).
 *
 * The schema's CHECK constraints are the enforcement layer; this code matches that policy
 * but does not re-implement it — the database will reject a write that violates a CHECK.
 */
class SessionMemoryStore(
    private val driver: SqlDriver,
    /** RFC-0035: before a secret becomes a remembered "fact". Defaults to identity. */
    private val redact: (String) -> String = { it },
) {

    /**
     * Writes a memory entry.
     *
     * Throws [IllegalArgumentException] if [sourceRefs] is empty (RFC-0026 provenance).
     * The entry is session-scoped; promotion is a separate user action.
     */
    fun write(entry: MemoryEntry): String {
        require(entry.sourceRefs.isNotEmpty()) {
            "source_refs must not be empty — every memory entry must have provenance (RFC-0026)"
        }
        val id = UUID.randomUUID().toString()
        val nowInstant = Instant.now()
        val now = nowInstant.toString()
        val resolvedExpiresAt = entry.expiresAt ?: when (entry.kind) {
            MemoryKind.FACT -> nowInstant.plusSeconds(DEFAULT_FACT_TTL_DAYS * 86_400L).toString()
            MemoryKind.DECISION, MemoryKind.TASK_STATE -> null
        }
        val sourceRefsJson = Json.encodeToString(entry.sourceRefs)

        driver.execute(
            identifier = null,
            sql = """INSERT INTO memory_entries
                (id, session_id, project_id, kind, content, source_refs_json,
                 created_by_kind, created_by_id, confidence, trust_level, scope, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 'SESSION', ?, ?)""",
            parameters = 12,
        ) {
            bindString(0, id)
            bindString(1, entry.sessionId)
            bindString(2, entry.projectId)
            bindString(3, entry.kind.name)
            bindString(4, redact(entry.content))
            bindString(5, sourceRefsJson)
            bindString(6, entry.createdByKind.name)
            bindString(7, entry.createdById)
            bindString(8, entry.confidence.name)
            bindString(9, entry.trustLevel.name)
            bindString(10, now)
            bindString(11, resolvedExpiresAt)
        }
        return id
    }

    /** Reads all active (non-superseded, non-expired) entries for a session. */
    fun readForSession(
        sessionId: String,
        nowIso: String = Instant.now().toString(),
    ): List<MemoryRow> {
        val rows = mutableListOf<MemoryRow>()
        driver.executeQuery(
            identifier = null,
            sql = """SELECT id, kind, content, source_refs_json, trust_level, scope, confidence,
                     created_by_kind, created_by_id, created_at, expires_at, superseded_by
                     FROM memory_entries
                     WHERE session_id = ? AND superseded_by IS NULL
                       AND (expires_at IS NULL OR expires_at > ?)
                     ORDER BY created_at""",
            mapper = { cursor ->
                while (cursor.next().value) {
                    rows.add(mapRow(cursor))
                }
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            },
            parameters = 2,
        ) {
            bindString(0, sessionId)
            bindString(1, nowIso)
        }
        return rows
    }

    /**
     * Reads all active, non-expired entries visible to [sessionId]: its own session-scoped
     * entries plus [projectId]'s user-promoted `PROJECT` entries (RFC-0026).
     */
    fun readForSessionAndProject(
        sessionId: String,
        projectId: String,
        nowIso: String = Instant.now().toString(),
    ): List<MemoryRow> {
        val rows = mutableListOf<MemoryRow>()
        driver.executeQuery(
            identifier = null,
            sql = """SELECT id, kind, content, source_refs_json, trust_level, scope, confidence,
                     created_by_kind, created_by_id, created_at, expires_at, superseded_by
                     FROM memory_entries
                     WHERE (session_id = ? OR (project_id = ? AND scope = 'PROJECT'))
                       AND superseded_by IS NULL
                       AND (expires_at IS NULL OR expires_at > ?)
                     ORDER BY created_at""",
            mapper = { cursor ->
                while (cursor.next().value) {
                    rows.add(mapRow(cursor))
                }
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            },
            parameters = 3,
        ) {
            bindString(0, sessionId)
            bindString(1, projectId)
            bindString(2, nowIso)
        }
        return rows
    }

    /** Inspects a single memory entry by [entryId] (including provenance and expiry metadata). */
    fun inspect(entryId: String): MemoryRow? {
        var found: MemoryRow? = null
        driver.executeQuery(
            identifier = null,
            sql = """SELECT id, kind, content, source_refs_json, trust_level, scope, confidence,
                     created_by_kind, created_by_id, created_at, expires_at, superseded_by
                     FROM memory_entries WHERE id = ?""",
            mapper = { cursor ->
                if (cursor.next().value) {
                    found = mapRow(cursor)
                }
                app.cash.sqldelight.db.QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, entryId) }
        return found
    }

    /**
     * Deletes an entry permanently — no tombstone retains the content (RFC-0026).
     * Clears any `superseded_by` back-references first so the foreign key constraint allows deletion.
     */
    fun delete(entryId: String): Boolean {
        driver.execute(
            identifier = null,
            sql = "UPDATE memory_entries SET superseded_by = NULL WHERE superseded_by = ?",
            parameters = 1,
        ) { bindString(0, entryId) }
        val count = driver.execute(
            identifier = null,
            sql = "DELETE FROM memory_entries WHERE id = ?",
            parameters = 1,
        ) { bindString(0, entryId) }
        return count.value > 0L
    }

    /** Refreshes a `FACT` entry's 90-day expiry on recall (RFC-0026). */
    fun refreshFactExpiry(
        entryId: String,
        nowIso: String = Instant.now().toString(),
    ): Boolean {
        val newExpiry = Instant.parse(nowIso).plusSeconds(DEFAULT_FACT_TTL_DAYS * 86_400L).toString()
        val count = driver.execute(
            identifier = null,
            sql = "UPDATE memory_entries SET expires_at = ? WHERE id = ? AND kind = 'FACT'",
            parameters = 2,
        ) {
            bindString(0, newExpiry)
            bindString(1, entryId)
        }
        return count.value > 0L
    }

    /** Expires `TASK_STATE` entries tied to [runId] when that Run reaches a terminal state (RFC-0026). */
    fun expireTaskStateForRun(
        runId: String,
        nowIso: String = Instant.now().toString(),
    ): Int {
        val count = driver.execute(
            identifier = null,
            sql = """UPDATE memory_entries
                SET expires_at = ?
                WHERE kind = 'TASK_STATE'
                  AND source_refs_json LIKE ?
                  AND (expires_at IS NULL OR expires_at > ?)""",
            parameters = 3,
        ) {
            bindString(0, nowIso)
            bindString(1, "%\"run:$runId\"%")
            bindString(2, nowIso)
        }
        return count.value.toInt()
    }

    /** Permanently deletes all entries whose `expires_at` is at or before [nowIso]. */
    fun deleteExpired(nowIso: String = Instant.now().toString()): Int {
        driver.execute(
            identifier = null,
            sql = """UPDATE memory_entries SET superseded_by = NULL
                WHERE superseded_by IN (
                    SELECT id FROM memory_entries WHERE expires_at IS NOT NULL AND expires_at <= ?
                )""",
            parameters = 1,
        ) { bindString(0, nowIso) }
        val count = driver.execute(
            identifier = null,
            sql = "DELETE FROM memory_entries WHERE expires_at IS NOT NULL AND expires_at <= ?",
            parameters = 1,
        ) { bindString(0, nowIso) }
        return count.value.toInt()
    }

    private fun mapRow(cursor: app.cash.sqldelight.db.SqlCursor): MemoryRow =
        MemoryRow(
            id = cursor.getString(0)!!,
            kind = MemoryKind.valueOf(cursor.getString(1)!!),
            content = cursor.getString(2)!!,
            sourceRefs = Json.decodeFromString(cursor.getString(3)!!),
            trustLevel = TrustLevel.valueOf(cursor.getString(4)!!),
            scope = MemoryScope.valueOf(cursor.getString(5)!!),
            confidence = Confidence.valueOf(cursor.getString(6)!!),
            createdByKind = CreatedByKind.valueOf(cursor.getString(7)!!),
            createdById = cursor.getString(8)!!,
            createdAt = cursor.getString(9)!!,
            expiresAt = cursor.getString(10),
            supersededBy = cursor.getString(11),
        )

    companion object {
        const val DEFAULT_FACT_TTL_DAYS: Long = 90L
    }

    /**
     * Promotes an entry to project scope (D33).
     *
     * Only a user may promote. The schema's CHECK constraints reject:
     * - TASK_STATE entries (scope must stay SESSION)
     * - UNTRUSTED entries (cannot be promoted to PROJECT)
     */
    fun promoteToProject(entryId: String, userId: String): Boolean {
        val now = Instant.now().toString()
        val count = driver.execute(
            identifier = null,
            sql = """UPDATE memory_entries
                SET scope = 'PROJECT', promoted_by_user_id = ?, promoted_at = ?
                WHERE id = ? AND scope = 'SESSION'""",
            parameters = 3,
        ) {
            bindString(0, userId)
            bindString(1, now)
            bindString(2, entryId)
        }
        return count.value > 0L
    }

    /** Supersedes an entry (D32: entries replaced, not overwritten). */
    fun supersede(oldEntryId: String, newEntryId: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE memory_entries SET superseded_by = ? WHERE id = ?",
            parameters = 2,
        ) {
            bindString(0, newEntryId)
            bindString(1, oldEntryId)
        }
    }
}

enum class MemoryKind { FACT, DECISION, TASK_STATE }
enum class MemoryScope { SESSION, PROJECT }
enum class Confidence { OBSERVED, INFERRED, USER_STATED }
enum class CreatedByKind { USER, SESSION, WORKER, RUNTIME }

data class MemoryEntry(
    val sessionId: String,
    val projectId: String,
    val kind: MemoryKind,
    val content: String,
    val sourceRefs: List<String>,
    val createdByKind: CreatedByKind,
    val createdById: String,
    val confidence: Confidence,
    /** Max taint of sources — the Run taint at write time (RFC-0027). */
    val trustLevel: TrustLevel,
    val expiresAt: String? = null,
)

data class MemoryRow(
    val id: String,
    val kind: MemoryKind,
    val content: String,
    val sourceRefs: List<String>,
    val trustLevel: TrustLevel,
    val scope: MemoryScope,
    val confidence: Confidence,
    val createdByKind: CreatedByKind,
    val createdById: String,
    val createdAt: String,
    val expiresAt: String? = null,
    val supersededBy: String? = null,
)
