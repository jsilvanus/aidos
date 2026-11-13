package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.Budget
import dev.aidos.kernel.BudgetScope
import dev.aidos.kernel.ProjectId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for BudgetLedger (RFC-0028) and ProjectLifecycle (RFC-0002).
 */
class BudgetAndProjectTest {

    private val nowIso = "2026-08-05T00:00:00Z"
    private val idGen = UuidV7Generator()

    private fun openDriver() = run {
        val root = Files.createTempDirectory("budget-project-test").toFile()
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
        driver.execute(null, "PRAGMA foreign_keys = OFF", 0) {}
        driver
    }

    private fun seedProject(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
    ) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, " +
                "created_at, updated_at, state_updated_at) VALUES (?, 'test', '/', 'generic', ?, ?, ?)",
            4
        ) {
            bindString(0, projectId)
            bindString(1, nowIso)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        driver.execute(
            null,
            "INSERT OR IGNORE INTO project_revocation_epoch (project_id, epoch) VALUES (?, 0)",
            1
        ) {
            bindString(0, projectId)
        }
    }

    private fun seedSession(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
        state: String = "RUNNING",
    ) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO sessions (id, project_id, name, role, state, " +
                "created_at, last_active_at, state_updated_at) VALUES (?, ?, 'test', 'DRIVER', ?, ?, ?, ?)",
            5
        ) {
            bindString(0, sessionId)
            bindString(1, projectId)
            bindString(2, state)
            bindString(3, nowIso)
            bindString(4, nowIso)
            bindString(5, nowIso)
        }
    }

    private fun seedRun(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        runId: String,
        state: String = "PENDING",
    ) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO runs (" +
                "id, session_id, project_id, trigger_event_id, started_at, state, " +
                "retry_policy_json, platform_profile, device_id, step_index, max_steps" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, 'DESKTOP', 'test-device', ?, ?)",
            9
        ) {
            bindString(0, runId)
            bindString(1, "session-1")
            bindString(2, projectId)
            bindString(3, "event-1")
            bindString(4, nowIso)
            bindString(5, state)
            bindString(6, """{"mode":"none"}""")
            bindLong(7, 0)
            bindLong(8, 24)
        }
    }

    private fun seedBudgetLedger(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        scope: String,
        scopeId: String,
        modelCalls: Long = 0,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        costUnits: Long = 0,
        steps: Long = 0,
        limitJson: String? = null,
    ) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO budget_ledger (id, scope, scope_id, model_calls, input_tokens, " +
                "output_tokens, cost_units, steps, limit_json, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9
        ) {
            bindString(0, "${scope}-${scopeId}")
            bindString(1, scope)
            bindString(2, scopeId)
            bindLong(3, modelCalls)
            bindLong(4, inputTokens)
            bindLong(5, outputTokens)
            bindLong(6, costUnits)
            bindLong(7, steps)
            bindString(8, limitJson)
            bindString(9, nowIso)
        }
    }

    private fun seedAttemptForReservation(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
        runId: String = "run-1",
        attemptId: String = "run-1",
    ) {
        seedSession(driver, projectId, "session-1", "SLEEPING")
        seedRun(driver, projectId, runId, "RUNNING")
        driver.execute(
            null,
            "INSERT OR IGNORE INTO audit_log (" +
                "id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id, detail_json" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9
        ) {
            bindString(0, "audit-1")
            bindString(1, projectId)
            bindLong(2, 1)
            bindString(3, nowIso)
            bindString(4, "Test")
            bindString(5, "USER")
            bindString(6, "user-1")
            bindString(7, "device-1")
            bindString(8, "{}")
        }
        driver.execute(
            null,
            "INSERT OR IGNORE INTO tasks (" +
                "id, run_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9
        ) {
            bindString(0, "task-1")
            bindString(1, runId)
            bindString(2, "session-1")
            bindString(3, projectId)
            bindLong(4, 0)
            bindString(5, "MODEL_CALL")
            bindString(6, "test task")
            bindString(7, "RUNNING")
            bindString(8, """{"mode":"none"}""")
        }
        driver.execute(
            null,
            "INSERT OR IGNORE INTO attempts (" +
                "id, task_id, attempt_number, started_at, state, audit_ref" +
                ") VALUES (?, ?, ?, ?, ?, ?)",
            6
        ) {
            bindString(0, attemptId)
            bindString(1, "task-1")
            bindLong(2, 1)
            bindString(3, nowIso)
            bindString(4, "RUNNING")
            bindString(5, "audit-1")
        }
    }

    // ========== Budget Ledger Tests ==========

    @Test
    fun `reserve succeeds within budget`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedAttemptForReservation(driver, projectId, runId = "run-1", attemptId = "run-1")
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 0, steps = 0,
            limitJson = """{"modelCalls":8,"steps":24}"""
        )

        val estimate = Budget(modelCalls = 2, steps = 5)
        val result = ledger.reserve(BudgetScope.RUN, "run-1", estimate)

        assertTrue(result.isSuccess, "reserve failed: ${result.exceptionOrNull()?.message}")
        assertNotNull(result.getOrNull())
        Unit
    }

    @Test
    fun `reserve fails when budget exhausted`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedAttemptForReservation(driver, projectId, runId = "run-1", attemptId = "run-1")
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 8, steps = 24,
            limitJson = """{"modelCalls":8,"steps":24}"""
        )

        val estimate = Budget(modelCalls = 1, steps = 1)
        val result = ledger.reserve(BudgetScope.RUN, "run-1", estimate)

        assertTrue(result.isFailure)
    }

    @Test
    fun `release cancels reservation`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedAttemptForReservation(driver, projectId, runId = "run-1", attemptId = "run-1")
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 0, steps = 0,
            limitJson = """{"modelCalls":8,"steps":24}"""
        )

        val estimate = Budget(modelCalls = 2, steps = 5)
        val reserveResult = ledger.reserve(BudgetScope.RUN, "run-1", estimate)
        assertTrue(reserveResult.isSuccess, "reserve failed: ${reserveResult.exceptionOrNull()?.message}")

        val reservationId = reserveResult.getOrNull()!!
        ledger.release(reservationId)

        // Verify reservation was deleted (via DB check)
        var found = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id FROM budget_reservations WHERE id = ?",
            mapper = { cursor ->
                found = cursor.next().value
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, reservationId.value) },
        )
        assertTrue(!found, "Reservation should be deleted")
    }

    @Test
    fun `settle records actual cost`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedAttemptForReservation(driver, projectId, runId = "run-1", attemptId = "run-1")
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 0, steps = 0,
            limitJson = """{"modelCalls":8,"steps":24}"""
        )

        val estimate = Budget(modelCalls = 2, steps = 5, inputTokens = 1000, outputTokens = 500)
        val reserveResult = ledger.reserve(BudgetScope.RUN, "run-1", estimate)
        assertTrue(reserveResult.isSuccess, "reserve failed: ${reserveResult.exceptionOrNull()?.message}")

        val reservationId = reserveResult.getOrNull()!!
        val actual = Budget(modelCalls = 1, steps = 3, inputTokens = 800, outputTokens = 200)
        ledger.settle(reservationId, actual)

        // Verify settlement was recorded
        var found = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT reserved_json FROM budget_reservations WHERE id = ?",
            mapper = { cursor ->
                found = cursor.next().value
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, reservationId.value) },
        )
        assertTrue(found, "Reservation should be updated with actual cost")
    }

    @Test
    fun `remaining returns available budget`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 3, steps = 15,
            limitJson = """{"modelCalls":8,"steps":24}"""
        )

        val remaining = ledger.remaining(BudgetScope.RUN, "run-1")

        assertNotNull(remaining)
        assertEquals(5, remaining.modelCalls) // 8 - 3
        assertEquals(9, remaining.steps) // 24 - 15
    }

    @Test
    fun `remaining returns unlimited when no ledger entry`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)

        val remaining = ledger.remaining(BudgetScope.SESSION, "session-1")

        // No entry → no limits
        assertEquals(null, remaining)
    }

    // ========== Project Lifecycle Tests ==========

    @Test
    fun `archiveProject succeeds with no active sessions`() = runBlocking {
        val driver = openDriver()
        val projectId = ProjectId("proj-1")
        val lifecycle = ProjectLifecycle(driver, { nowIso })

        seedProject(driver, projectId.value)

        val result = lifecycle.archiveProject(projectId)

        assertTrue(result.isSuccess)

        // Verify state changed
        var state: String? = null
        driver.executeQuery(
            identifier = null,
            sql = "SELECT state FROM projects WHERE id = ?",
            mapper = { cursor ->
                if (cursor.next().value) state = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, projectId.value) },
        )
        assertEquals("ARCHIVED", state)
    }

    @Test
    fun `archiveProject fails with active sessions`() = runBlocking {
        val driver = openDriver()
        val projectId = ProjectId("proj-1")
        val lifecycle = ProjectLifecycle(driver, { nowIso })

        seedProject(driver, projectId.value)
        seedSession(driver, projectId.value, "session-1", "RUNNING")

        val result = lifecycle.archiveProject(projectId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("active session") == true)
    }

    @Test
    fun `archiveProject fails with open runs`() = runBlocking {
        val driver = openDriver()
        val projectId = ProjectId("proj-1")
        val lifecycle = ProjectLifecycle(driver, { nowIso })

        seedProject(driver, projectId.value)
        seedSession(driver, projectId.value, "session-1", "SLEEPING")
        seedRun(driver, projectId.value, "run-1", "RUNNING")

        val result = lifecycle.archiveProject(projectId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("open run") == true)
    }

    @Test
    fun `deleteProject removes all project data`() = runBlocking {
        val driver = openDriver()
        val projectId = ProjectId("proj-1")
        val lifecycle = ProjectLifecycle(driver, { nowIso })

        seedProject(driver, projectId.value)
        seedSession(driver, projectId.value, "session-1", "SLEEPING")
        seedRun(driver, projectId.value, "run-1", "COMPLETED")

        val result = lifecycle.deleteProject(projectId)

        assertTrue(result.isSuccess, "delete failed: ${result.exceptionOrNull()?.message}")

        // Verify project is gone
        var projectExists = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id FROM projects WHERE id = ?",
            mapper = { cursor ->
                projectExists = cursor.next().value
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, projectId.value) },
        )
        assertTrue(!projectExists, "Project should be deleted")

        // Verify sessions are gone
        var sessionExists = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id FROM sessions WHERE project_id = ?",
            mapper = { cursor ->
                sessionExists = cursor.next().value
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, projectId.value) },
        )
        assertTrue(!sessionExists, "Sessions should be deleted")
    }

    @Test
    fun `deleteProject fails with active sessions`() = runBlocking {
        val driver = openDriver()
        val projectId = ProjectId("proj-1")
        val lifecycle = ProjectLifecycle(driver, { nowIso })

        seedProject(driver, projectId.value)
        seedSession(driver, projectId.value, "session-1", "RUNNING")

        val result = lifecycle.deleteProject(projectId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("active session") == true)
    }

    @Test
    fun `deleteProject fails with open runs`() = runBlocking {
        val driver = openDriver()
        val projectId = ProjectId("proj-1")
        val lifecycle = ProjectLifecycle(driver, { nowIso })

        seedProject(driver, projectId.value)
        seedSession(driver, projectId.value, "session-1", "SLEEPING")
        seedRun(driver, projectId.value, "run-1", "PENDING")

        val result = lifecycle.deleteProject(projectId)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("open run") == true)
    }

    @Test
    fun `archiveProject preserves project with completed runs`() = runBlocking {
        val driver = openDriver()
        val projectId = ProjectId("proj-1")
        val lifecycle = ProjectLifecycle(driver, { nowIso })

        seedProject(driver, projectId.value)
        seedSession(driver, projectId.value, "session-1", "SLEEPING")
        seedRun(driver, projectId.value, "run-1", "COMPLETED")

        val result = lifecycle.archiveProject(projectId)

        assertTrue(result.isSuccess)

        // Sessions should still exist
        var sessionExists = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id FROM sessions WHERE project_id = ?",
            mapper = { cursor ->
                sessionExists = cursor.next().value
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, projectId.value) },
        )
        assertTrue(sessionExists, "Sessions should be preserved after archive")
    }
}
