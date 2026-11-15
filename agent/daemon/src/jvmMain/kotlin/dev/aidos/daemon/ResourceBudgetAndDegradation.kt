package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.BatteryState
import dev.aidos.kernel.DegradationEvent
import dev.aidos.kernel.DegradationRung
import dev.aidos.kernel.DeviceSignals
import dev.aidos.kernel.MemoryPressureLevel
import dev.aidos.kernel.ThermalState
import dev.aidos.routing.DegradationLadder
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * SQLite store for `resource_budgets` in `user.db` (RFC-0045 §Data Model).
 */
class SqliteResourceBudgetStore(private val userDriver: SqlDriver) {

    fun set(scope: String, scopeId: String?, key: String, value: Long) {
        require(scope == SCOPE_USER || scope == SCOPE_PROJECT) {
            "Unsupported resource_budgets scope '$scope'; expected 'user' or 'project'"
        }
        // SQLite treats NULLs as distinct in composite PRIMARY KEYs, so we delete + insert when scopeId is null.
        if (scopeId == null) {
            userDriver.execute(
                identifier = null,
                sql = "DELETE FROM resource_budgets WHERE scope = ? AND scope_id IS NULL AND key = ?",
                parameters = 2,
            ) {
                bindString(0, scope)
                bindString(1, key)
            }
            userDriver.execute(
                identifier = null,
                sql = "INSERT INTO resource_budgets (scope, scope_id, key, value) VALUES (?, NULL, ?, ?)",
                parameters = 3,
            ) {
                bindString(0, scope)
                bindString(1, key)
                bindLong(2, value)
            }
        } else {
            userDriver.execute(
                identifier = null,
                sql = "INSERT INTO resource_budgets (scope, scope_id, key, value) VALUES (?, ?, ?, ?) " +
                    "ON CONFLICT(scope, scope_id, key) DO UPDATE SET value = excluded.value",
                parameters = 4,
            ) {
                bindString(0, scope)
                bindString(1, scopeId)
                bindString(2, key)
                bindLong(3, value)
            }
        }
    }

    fun get(scope: String, scopeId: String?, key: String): Long? =
        userDriver.executeQuery(
            identifier = null,
            sql = if (scopeId == null) {
                "SELECT value FROM resource_budgets WHERE scope = ? AND scope_id IS NULL AND key = ?"
            } else {
                "SELECT value FROM resource_budgets WHERE scope = ? AND scope_id = ? AND key = ?"
            },
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) },
            parameters = if (scopeId == null) 2 else 3,
        ) {
            bindString(0, scope)
            if (scopeId == null) {
                bindString(1, key)
            } else {
                bindString(1, scopeId)
                bindString(2, key)
            }
        }.value

    /**
     * Resolves [key] with project scope overriding user scope, falling back to [defaultValue].
     */
    fun resolve(projectId: String?, key: String, defaultValue: Long): Long {
        if (projectId != null) {
            get(SCOPE_PROJECT, projectId, key)?.let { return it }
        }
        return get(SCOPE_USER, null, key) ?: defaultValue
    }

    companion object {
        const val SCOPE_USER = "user"
        const val SCOPE_PROJECT = "project"
        const val KEY_MEMORY_MB = "memory_mb"
        const val KEY_BATTERY_FLOOR_PCT = "battery_floor_pct"
    }
}

/**
 * Production degradation monitor for the daemon (RFC-0045).
 *
 * Evaluates [DegradationLadder] against live [DeviceSignals], reconciles open/closed rows in
 * `degradation_events` on [projectDriver], and exposes admission/background-pause decisions.
 */
class DaemonDegradationMonitor(
    private val signalsProvider: () -> DeviceSignals = { defaultJvmSignals() },
    private val userDriver: SqlDriver? = null,
    private val idGen: () -> String = { UuidV7Generator().next() },
    private val nowIso: () -> String = { Clock.System.now().toString() },
) {
    private val budgetStore: SqliteResourceBudgetStore? = userDriver?.let(::SqliteResourceBudgetStore)

    /**
     * Evaluates current [DeviceSignals] and records any rung transitions into `degradation_events`.
     */
    fun evaluateAndRecord(
        projectDriver: SqlDriver,
        projectId: String?,
    ): Map<DegradationRung, String> {
        val batteryFloor = budgetStore
            ?.resolve(projectId, SqliteResourceBudgetStore.KEY_BATTERY_FLOOR_PCT, DEFAULT_BATTERY_FLOOR_PCT)
            ?.toInt()
            ?: DEFAULT_BATTERY_FLOOR_PCT.toInt()
        val active = DegradationLadder(batteryFloorPercent = batteryFloor).evaluate(signalsProvider())
        reconcileEvents(projectDriver, active, projectId, nowIso())
        return active
    }

    /**
     * Rung 5 (`PARK_RUNS`): under critical memory, do not start new Runs.
     */
    fun refusalForNewRun(projectDriver: SqlDriver, projectId: String): String? {
        val active = evaluateAndRecord(projectDriver, projectId)
        val reason = active[DegradationRung.PARK_RUNS] ?: return null
        return "Degradation rung 5 active ($reason): new Runs paused until memory recovers"
    }

    /**
     * Rung 1 (`PAUSE_INDEXING`) or Rung 4 (`SUSPEND_DEFERRED_WORK`): pause background compaction/indexing.
     */
    fun shouldPauseBackgroundMaintenance(projectDriver: SqlDriver, projectId: String): Boolean {
        val active = evaluateAndRecord(projectDriver, projectId)
        return DegradationRung.PAUSE_INDEXING in active || DegradationRung.SUSPEND_DEFERRED_WORK in active
    }

    fun history(projectDriver: SqlDriver, projectId: String?, limit: Int = 100): List<DegradationEvent> {
        val results = mutableListOf<DegradationEvent>()
        projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT id, rung, trigger, entered_at, exited_at, project_id FROM degradation_events " +
                "WHERE project_id IS ? ORDER BY entered_at DESC LIMIT ?",
            mapper = { cursor ->
                while (cursor.next().value) {
                    val level = cursor.getLong(1)!!.toInt()
                    results.add(
                        DegradationEvent(
                            id = cursor.getString(0)!!,
                            rung = DegradationRung.entries.first { it.level == level },
                            trigger = cursor.getString(2)!!,
                            enteredAt = Instant.parse(cursor.getString(3)!!),
                            exitedAt = cursor.getString(4)?.let { Instant.parse(it) },
                            projectId = cursor.getString(5),
                        )
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 2,
        ) {
            bindString(0, projectId)
            bindLong(1, limit.toLong())
        }
        return results
    }

    private fun reconcileEvents(
        driver: SqlDriver,
        activeRungs: Map<DegradationRung, String>,
        projectId: String?,
        timestampIso: String,
    ) {
        val open = mutableMapOf<DegradationRung, String>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, rung FROM degradation_events WHERE project_id IS ? AND exited_at IS NULL",
            mapper = { cursor ->
                while (cursor.next().value) {
                    val level = cursor.getLong(1)!!.toInt()
                    val rung = DegradationRung.entries.first { it.level == level }
                    open[rung] = cursor.getString(0)!!
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, projectId) }

        for ((rung, reason) in activeRungs) {
            if (rung !in open) {
                driver.execute(
                    identifier = null,
                    sql = "INSERT INTO degradation_events (id, rung, trigger, entered_at, exited_at, project_id) " +
                        "VALUES (?, ?, ?, ?, NULL, ?)",
                    parameters = 5,
                ) {
                    bindString(0, idGen())
                    bindLong(1, rung.level.toLong())
                    bindString(2, reason)
                    bindString(3, timestampIso)
                    bindString(4, projectId)
                }
            }
        }
        for ((rung, eventId) in open) {
            if (rung !in activeRungs) {
                driver.execute(
                    identifier = null,
                    sql = "UPDATE degradation_events SET exited_at = ? WHERE id = ?",
                    parameters = 2,
                ) {
                    bindString(0, timestampIso)
                    bindString(1, eventId)
                }
            }
        }
    }

    companion object {
        private const val DEFAULT_BATTERY_FLOOR_PCT = 20L

        fun defaultJvmSignals(): DeviceSignals {
            val rt = Runtime.getRuntime()
            val maxMem = rt.maxMemory().coerceAtLeast(1L)
            val usedMem = (rt.totalMemory() - rt.freeMemory()).coerceAtLeast(0L)
            val usedPct = (usedMem * 100L) / maxMem
            val pressure = when {
                usedPct >= 95L -> MemoryPressureLevel.CRITICAL
                usedPct >= 88L -> MemoryPressureLevel.ELEVATED
                usedPct >= 80L -> MemoryPressureLevel.BACKGROUND_PRESSURE
                else -> MemoryPressureLevel.NONE
            }
            return DeviceSignals(
                memoryPressure = pressure,
                battery = BatteryState(percent = 100, isCharging = true),
                thermal = ThermalState.NORMAL,
            )
        }
    }
}
