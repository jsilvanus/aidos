package dev.aidos.androidapp.degradation

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.DegradationEvent
import dev.aidos.kernel.DegradationRung
import kotlinx.datetime.Instant

/**
 * Persists degradation-ladder transitions to `degradation_events` (RFC-0045). The ladder
 * decision itself ([dev.aidos.routing.DegradationLadder]) is pure and stateless; this store
 * owns turning successive snapshots of "what's active now" into open/close events, by querying
 * currently-open rows rather than tracking state in memory — recovery is a query, not a
 * remembered variable, matching this codebase's executor/storage convention (D3).
 */
class SqliteDegradationEventStore(private val driver: SqlDriver) {

    /**
     * Reconciles [activeRungs] (this tick's [dev.aidos.routing.DegradationLadder] output)
     * against currently-open events for [projectId]: opens a new row for a rung that just
     * became active, closes (`exited_at`) a row for a rung that's no longer active. Idempotent
     * — calling this again with the same [activeRungs] is a no-op.
     */
    fun apply(
        activeRungs: Map<DegradationRung, String>,
        projectId: String?,
        nowIso: String,
        nextId: () -> String,
    ) {
        val open = openEventsByRung(projectId)
        for ((rung, reason) in activeRungs) {
            if (rung !in open) {
                insert(nextId(), rung, reason, projectId, nowIso)
            }
        }
        for ((rung, eventId) in open) {
            if (rung !in activeRungs) {
                close(eventId, nowIso)
            }
        }
    }

    /** Most recent events for [projectId] first, open or closed. */
    fun history(projectId: String?, limit: Int = 100): List<DegradationEvent> {
        val results = mutableListOf<DegradationEvent>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, rung, trigger, entered_at, exited_at, project_id FROM degradation_events " +
                "WHERE project_id IS ? ORDER BY entered_at DESC LIMIT ?",
            mapper = { cursor ->
                while (cursor.next().value) results.add(cursor.toEvent())
                QueryResult.Value(Unit)
            },
            parameters = 2,
        ) {
            bindString(0, projectId)
            bindLong(1, limit.toLong())
        }
        return results
    }

    private fun openEventsByRung(projectId: String?): Map<DegradationRung, String> {
        val results = mutableMapOf<DegradationRung, String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, rung FROM degradation_events WHERE project_id IS ? AND exited_at IS NULL",
            mapper = { cursor ->
                while (cursor.next().value) {
                    results[degradationRungFromLevel(cursor.getLong(1)!!.toInt())] = cursor.getString(0)!!
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, projectId) }
        return results
    }

    private fun insert(id: String, rung: DegradationRung, trigger: String, projectId: String?, nowIso: String) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO degradation_events (id, rung, trigger, entered_at, exited_at, project_id) " +
                "VALUES (?, ?, ?, ?, NULL, ?)",
            parameters = 5,
        ) {
            bindString(0, id)
            bindLong(1, rung.level.toLong())
            bindString(2, trigger)
            bindString(3, nowIso)
            bindString(4, projectId)
        }
    }

    private fun close(eventId: String, nowIso: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE degradation_events SET exited_at = ? WHERE id = ?",
            parameters = 2,
        ) {
            bindString(0, nowIso)
            bindString(1, eventId)
        }
    }

    private fun app.cash.sqldelight.db.SqlCursor.toEvent(): DegradationEvent = DegradationEvent(
        id = getString(0)!!,
        rung = degradationRungFromLevel(getLong(1)!!.toInt()),
        trigger = getString(2)!!,
        enteredAt = Instant.parse(getString(3)!!),
        exitedAt = getString(4)?.let { Instant.parse(it) },
        projectId = getString(5),
    )
}

private fun degradationRungFromLevel(level: Int): DegradationRung =
    DegradationRung.entries.first { it.level == level }
