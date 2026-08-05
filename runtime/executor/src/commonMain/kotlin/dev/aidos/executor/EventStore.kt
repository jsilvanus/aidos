package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Writes and reads events from the event bus table (RFC-0004, M5).
 *
 * Events use `sequence` as the ordering key — not timestamp (RFC-0004). Sequence is
 * monotonically increasing per project, so late arrivals cannot reorder history.
 *
 * Causality is stored as the parent event ID. Causal depth is bounded (RFC-0028) to prevent
 * wake amplification — a session cannot wake itself indefinitely.
 */
class EventStore(private val driver: SqlDriver) {

    companion object {
        /** Maximum causal depth before a wake is refused (RFC-0028, M6 guard). */
        const val MAX_CAUSAL_DEPTH = 16
    }

    /**
     * Publishes an event and returns its sequence number.
     *
     * [causedBy] is the parent event ID; [causalDepth] is the parent's depth + 1.
     * Returns null and does NOT insert if [causalDepth] exceeds [MAX_CAUSAL_DEPTH].
     */
    fun publish(
        id: String,
        projectId: String,
        type: String,
        schemaVersion: Int = 1,
        category: String = "SIGNAL",
        visibility: String = "SESSION",
        source: String,
        topic: String? = null,
        payload: String = "{}",
        causedBy: String? = null,
        causalDepth: Int = 0,
        nowIso: String,
    ): Long? {
        if (causalDepth > MAX_CAUSAL_DEPTH) return null

        val seq = nextSequence(projectId)
        driver.execute(
            identifier = null,
            sql = "INSERT INTO events " +
                "(id, project_id, sequence, type, schema_version, category, visibility, " +
                "timestamp, source, topic, payload, causality, causal_depth) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            parameters = 13,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindLong(2, seq)
            bindString(3, type)
            bindLong(4, schemaVersion.toLong())
            bindString(5, category)
            bindString(6, visibility)
            bindString(7, nowIso)
            bindString(8, source)
            bindString(9, topic)
            bindString(10, payload)
            bindString(11, causedBy)
            bindLong(12, causalDepth.toLong())
        }
        return seq
    }

    /** Returns events in sequence order for a project, optionally filtered by type. */
    fun eventsForProject(projectId: String, type: String? = null): List<EventRow> {
        val rows = mutableListOf<EventRow>()
        val sql = if (type != null) {
            "SELECT id, sequence, type, source, payload, causality, causal_depth, timestamp " +
                "FROM events WHERE project_id = ? AND type = ? ORDER BY sequence"
        } else {
            "SELECT id, sequence, type, source, payload, causality, causal_depth, timestamp " +
                "FROM events WHERE project_id = ? ORDER BY sequence"
        }
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                while (cursor.next().value) {
                    rows.add(
                        EventRow(
                            id = cursor.getString(0)!!,
                            sequence = cursor.getLong(1)!!,
                            type = cursor.getString(2)!!,
                            source = cursor.getString(3)!!,
                            payload = cursor.getString(4)!!,
                            causality = cursor.getString(5),
                            causalDepth = cursor.getLong(6)?.toInt() ?: 0,
                            timestamp = cursor.getString(7)!!,
                        )
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = if (type != null) 2 else 1,
        ) {
            bindString(0, projectId)
            if (type != null) bindString(1, type)
        }
        return rows
    }

    private fun nextSequence(projectId: String): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COALESCE(MAX(sequence), 0) + 1 FROM events WHERE project_id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 1L else 1L) },
            parameters = 1,
        ) { bindString(0, projectId) }.value
}

data class EventRow(
    val id: String,
    val sequence: Long,
    val type: String,
    val source: String,
    val payload: String,
    val causality: String?,
    val causalDepth: Int,
    val timestamp: String,
)
