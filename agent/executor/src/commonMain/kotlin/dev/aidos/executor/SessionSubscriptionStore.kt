package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Writes and reads durable session subscriptions to the event bus (RFC-0005 MVP item 1,
 * RFC-0004 "Subscription Model", `session_subscriptions` in `schema/project.sql`).
 *
 * This is the persisted counterpart to [SubscriptionRegistry]: a session's subscription must
 * survive a step boundary (D3), because the load-bearing case — a driver waking when its worker
 * completes — can span a process restart on Android. [SubscriptionRegistry] remains the right
 * shape for ephemeral, in-memory subscribers (a live UI stream, a subsystem watching one run)
 * that do not need to survive one.
 */
class SessionSubscriptionStore(private val driver: SqlDriver) {

    private val stringListSerializer = ListSerializer(String.serializer())

    fun subscribe(
        id: String,
        sessionId: String,
        topicPatterns: List<String>,
        eventTypes: List<String>? = null,
        selfWake: Boolean = false,
        nowIso: String,
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO session_subscriptions " +
                "(id, session_id, topic_patterns, event_types, self_wake, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            parameters = 6,
        ) {
            bindString(0, id)
            bindString(1, sessionId)
            bindString(2, Json.encodeToString(stringListSerializer, topicPatterns))
            bindString(3, eventTypes?.let { Json.encodeToString(stringListSerializer, it) })
            bindLong(4, if (selfWake) 1L else 0L)
            bindString(5, nowIso)
        }
    }

    fun unsubscribe(id: String) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM session_subscriptions WHERE id = ?",
            parameters = 1,
        ) { bindString(0, id) }
    }

    /** All subscriptions for one session. */
    fun forSession(sessionId: String): List<SessionSubscriptionRow> =
        query("SELECT id, session_id, topic_patterns, event_types, self_wake FROM session_subscriptions " +
            "WHERE session_id = ?") { bindString(0, sessionId) }

    /** Every subscription in the project — the set the Scheduler matches a published event against. */
    fun forProject(projectId: String): List<SessionSubscriptionRow> =
        query(
            "SELECT ss.id, ss.session_id, ss.topic_patterns, ss.event_types, ss.self_wake " +
                "FROM session_subscriptions ss JOIN sessions s ON s.id = ss.session_id " +
                "WHERE s.project_id = ?"
        ) { bindString(0, projectId) }

    private fun query(
        sql: String,
        binders: app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit,
    ): List<SessionSubscriptionRow> {
        val rows = mutableListOf<SessionSubscriptionRow>()
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                while (cursor.next().value) {
                    rows.add(
                        SessionSubscriptionRow(
                            id = cursor.getString(0)!!,
                            sessionId = cursor.getString(1)!!,
                            topicPatterns = Json.decodeFromString(stringListSerializer, cursor.getString(2)!!),
                            eventTypes = cursor.getString(3)?.let { Json.decodeFromString(stringListSerializer, it) },
                            selfWake = cursor.getLong(4) == 1L,
                        )
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = binders,
        )
        return rows
    }
}

data class SessionSubscriptionRow(
    val id: String,
    val sessionId: String,
    val topicPatterns: List<String>,
    val eventTypes: List<String>?,
    val selfWake: Boolean,
)
