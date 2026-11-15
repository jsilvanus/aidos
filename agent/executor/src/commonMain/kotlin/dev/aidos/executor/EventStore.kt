package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ActorKind

/** Structural event categories (RFC-0004 "Event Categories"). */
enum class EventCategory {
    /** Something happened. Immutable, past tense. Persisted. */
    FACT,

    /** Something is requested. Frontends and runtime only; sessions may not publish COMMAND. */
    COMMAND,

    /** Lossy progress or wakeup hint. Best-effort, coalescible, NOT persisted. */
    SIGNAL,
}

/** Event visibility tiers (RFC-0004 "Subscription Authorization"). */
enum class EventVisibility {
    /** Any subscriber in the project. */
    PUBLIC,

    /** The originating session, its parent session, and the user/runtime. */
    SESSION,

    /** User and runtime components only (never delivered to sessions). */
    PRIVILEGED,
}

/**
 * Writes and reads events from the event bus table (RFC-0004, M5).
 *
 * Events use `sequence` as the ordering key — not timestamp (RFC-0004). Sequence is
 * monotonically increasing per project, so late arrivals cannot reorder history.
 *
 * Causality is stored as the parent event ID. Causal depth is bounded (RFC-0028) to prevent
 * wake amplification — a session cannot wake itself indefinitely.
 */
class EventStore(
    private val driver: SqlDriver,
    /**
     * RFC-0035: events are permanent, so the payload is redacted before it is written. Defaults
     * to identity for callers with no redactor.
     */
    private val redactPayload: (String) -> String = { it },
) {

    companion object {
        /** Maximum causal depth before a wake is refused (RFC-0028: maxCausalDepth = 8). */
        const val MAX_CAUSAL_DEPTH = 8
    }

    private val signalListeners = mutableListOf<(EventRow) -> Unit>()

    fun addSignalListener(listener: (EventRow) -> Unit) {
        signalListeners.add(listener)
    }

    fun removeSignalListener(listener: (EventRow) -> Unit) {
        signalListeners.remove(listener)
    }

    /**
     * Publishes an event and returns its sequence number, or `null` if refused (causal depth
     * exceeded) or not persisted (`SIGNAL` category).
     *
     * Enforces RFC-0004 category rules:
     * - Sessions/workers may not publish `COMMAND` events (throws [IllegalArgumentException]).
     * - `SIGNAL` events are delivered to in-memory listeners and NOT persisted to SQLite.
     */
    fun publish(
        id: String,
        projectId: String,
        type: String,
        schemaVersion: Int = 1,
        category: String = EventCategory.FACT.name,
        visibility: String = EventVisibility.PUBLIC.name,
        source: String,
        topic: String? = null,
        payload: String = "{}",
        causedBy: String? = null,
        causalDepth: Int = 0,
        nowIso: String,
        publisherKind: ActorKind? = null,
    ): Long? {
        val parsedCategory = EventCategory.valueOf(category)
        EventVisibility.valueOf(visibility)

        val isSessionPublisher = publisherKind == ActorKind.SESSION ||
            publisherKind == ActorKind.WORKER ||
            (publisherKind == null && (source.startsWith("session:") || source.startsWith("worker:")))
        if (parsedCategory == EventCategory.COMMAND && isSessionPublisher) {
            throw IllegalArgumentException("Sessions may not publish COMMAND events (RFC-0004)")
        }

        if (causalDepth > MAX_CAUSAL_DEPTH) return null

        val redacted = redactPayload(payload)

        // RFC-0004: SIGNAL events are lossy hints and are not persisted to the events table.
        if (parsedCategory == EventCategory.SIGNAL) {
            val signalEvent = EventRow(
                id = id,
                sequence = 0L,
                type = type,
                category = parsedCategory.name,
                visibility = visibility,
                source = source,
                payload = redacted,
                causality = causedBy,
                causalDepth = causalDepth,
                timestamp = nowIso,
                topic = topic,
            )
            signalListeners.toList().forEach { it(signalEvent) }
            return null
        }

        // Sequence is allocated atomically by inlining the MAX()+1 subquery into the INSERT.
        // This collapses read + write into a single SQLite statement, eliminating the race
        // where two concurrent callers observe the same MAX and collide on the UNIQUE index.
        driver.execute(
            identifier = null,
            sql = "INSERT INTO events " +
                "(id, project_id, sequence, type, schema_version, category, visibility, " +
                "timestamp, source, topic, payload, causality, causal_depth) " +
                "VALUES (?, ?, " +
                "(SELECT COALESCE(MAX(sequence), 0) + 1 FROM events WHERE project_id = ?), " +
                "?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            parameters = 13,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindString(2, projectId)   // for the sequence subquery
            bindString(3, type)
            bindLong(4, schemaVersion.toLong())
            bindString(5, parsedCategory.name)
            bindString(6, visibility)
            bindString(7, nowIso)
            bindString(8, source)
            bindString(9, topic)
            bindString(10, redacted)
            bindString(11, causedBy)
            bindLong(12, causalDepth.toLong())
        }
        // Read back the sequence that was assigned for the just-inserted row.
        return driver.executeQuery(
            identifier = null,
            sql = "SELECT sequence FROM events WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) },
            parameters = 1,
        ) { bindString(0, id) }.value
    }

    /**
     * Checks whether [subscriberKind] / [subscriberId] is authorized to receive [event] based on
     * its [EventVisibility] (RFC-0004 "Subscription Authorization").
     */
    fun canDeliver(event: EventRow, subscriberKind: ActorKind, subscriberId: String): Boolean {
        return when (runCatching { EventVisibility.valueOf(event.visibility) }.getOrDefault(EventVisibility.PUBLIC)) {
            EventVisibility.PUBLIC -> true
            EventVisibility.PRIVILEGED -> subscriberKind == ActorKind.USER || subscriberKind == ActorKind.RUNTIME
            EventVisibility.SESSION -> {
                if (subscriberKind == ActorKind.USER || subscriberKind == ActorKind.RUNTIME) {
                    true
                } else {
                    val originSessionId = extractOriginSessionId(event)
                    originSessionId == null ||
                        subscriberId == originSessionId ||
                        subscriberId == parentSessionId(originSessionId)
                }
            }
        }
    }

    /**
     * Returns events in sequence order visible to [subscriberKind] / [subscriberId].
     */
    fun eventsForSubscriber(
        projectId: String,
        subscriberKind: ActorKind,
        subscriberId: String,
        type: String? = null,
        topicPattern: String? = null,
    ): List<EventRow> = eventsForProject(projectId, type, topicPattern)
        .filter { canDeliver(it, subscriberKind, subscriberId) }

    /**
     * Returns events in sequence order for a project, optionally filtered by type and/or topic
     * pattern (RFC-0004 "Topics and Filtering"; MVP item 5, replay by topic).
     */
    fun eventsForProject(
        projectId: String,
        type: String? = null,
        topicPattern: String? = null,
    ): List<EventRow> {
        val rows = queryEvents(
            sql = if (type != null) {
                "SELECT id, sequence, type, category, visibility, source, payload, causality, causal_depth, timestamp, topic " +
                    "FROM events WHERE project_id = ? AND type = ? ORDER BY sequence"
            } else {
                "SELECT id, sequence, type, category, visibility, source, payload, causality, causal_depth, timestamp, topic " +
                    "FROM events WHERE project_id = ? ORDER BY sequence"
            },
            parameters = if (type != null) 2 else 1,
        ) {
            bindString(0, projectId)
            if (type != null) bindString(1, type)
        }
        return if (topicPattern != null) rows.filter { TopicMatcher.matches(topicPattern, it.topic) } else rows
    }

    /**
     * Returns events for a project within `[fromIso, toIso]` (inclusive), in sequence order,
     * optionally filtered by topic pattern (RFC-0004 MVP item 5).
     */
    fun eventsBetween(
        projectId: String,
        fromIso: String,
        toIso: String,
        topicPattern: String? = null,
    ): List<EventRow> {
        val rows = queryEvents(
            sql = "SELECT id, sequence, type, category, visibility, source, payload, causality, causal_depth, timestamp, topic " +
                "FROM events WHERE project_id = ? AND timestamp >= ? AND timestamp <= ? ORDER BY sequence",
            parameters = 3,
        ) {
            bindString(0, projectId)
            bindString(1, fromIso)
            bindString(2, toIso)
        }
        return if (topicPattern != null) rows.filter { TopicMatcher.matches(topicPattern, it.topic) } else rows
    }

    private fun extractOriginSessionId(event: EventRow): String? {
        if (event.source.startsWith("session:")) return event.source.removePrefix("session:").substringBefore(':')
        if (event.source.startsWith("worker:")) return event.source.removePrefix("worker:").substringBefore(':')
        val topic = event.topic ?: return null
        if (topic.startsWith("session:")) return topic.removePrefix("session:").substringBefore(':')
        return null
    }

    private fun parentSessionId(sessionId: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT parent_session_id FROM sessions WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 1,
        ) { bindString(0, sessionId) }.value

    private fun queryEvents(
        sql: String,
        parameters: Int,
        binders: app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit,
    ): List<EventRow> {
        val rows = mutableListOf<EventRow>()
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
                            category = cursor.getString(3)!!,
                            visibility = cursor.getString(4)!!,
                            source = cursor.getString(5)!!,
                            payload = cursor.getString(6)!!,
                            causality = cursor.getString(7),
                            causalDepth = cursor.getLong(8)?.toInt() ?: 0,
                            timestamp = cursor.getString(9)!!,
                            topic = cursor.getString(10),
                        )
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = parameters,
            binders = binders,
        )
        return rows
    }
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
    val topic: String? = null,
    val category: String = EventCategory.FACT.name,
    val visibility: String = EventVisibility.PUBLIC.name,
)
