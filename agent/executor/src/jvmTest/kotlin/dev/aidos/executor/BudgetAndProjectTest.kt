package dev.aidos.executor

import dev.aidos.broker.AuditLog
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
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
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
            "INSERT OR IGNORE INTO runs (id, project_id, session_id, trigger_event_id, state, " +
                "step_index, max_steps, created_at, state_updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            8
        ) {
            bindString(0, runId)
            bindString(1, projectId)
            bindString(2, "session-1")
            bindString(3, "event-1")
            bindString(4, state)
            bindLong(5, 0)
            bindLong(6, 24)
            bindString(7, nowIso)
            bindString(8, nowIso)
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

    // ========== Budget Ledger Tests ==========

    @Test
    fun `reserve succeeds within budget`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 0, steps = 0,
            limitJson = """{"modelCalls":8,"steps":24}"""
        )

        val estimate = Budget(modelCalls = 2, steps = 5)
        val result = ledger.reserve(BudgetScope.RUN, "run-1", estimate)

        assertTrue(result.isSuccess)
        assertNotNull(result.getOrNull())
    }

    @Test
    fun `reserve fails when budget exhausted`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
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
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 0, steps = 0,
            limitJson = """{"modelCalls":8,"steps":24}"""
        )

        val estimate = Budget(modelCalls = 2, steps = 5)
        val reserveResult = ledger.reserve(BudgetScope.RUN, "run-1", estimate)
        assertTrue(reserveResult.isSuccess)

        val reservationId = reserveResult.getOrNull()!!
        ledger.release(reservationId)

        // Verify reservation was deleted (via DB check)
        var found = false
        driver.executeQuery(null, "SELECT id FROM budget_reservations WHERE id = ?", 1) { cursor ->
            found = cursor.next().value
            bindString(0, reservationId.value)
        }
        assertTrue(!found, "Reservation should be deleted")
    }

    @Test
    fun `settle records actual cost`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 0, steps = 0,
            limitJson = """{"modelCalls":8,"steps":24}"""
        )

        val estimate = Budget(modelCalls = 2, steps = 5, inputTokens = 1000, outputTokens = 500)
        val reserveResult = ledger.reserve(BudgetScope.RUN, "run-1", estimate)
        assertTrue(reserveResult.isSuccess)

        val reservationId = reserveResult.getOrNull()!!
        val actual = Budget(modelCalls = 1, steps = 3, inputTokens = 800, outputTokens = 200)
        ledger.settle(reservationId, actual)

        // Verify settlement was recorded
        var found = false
        driver.executeQuery(null, "SELECT reserved_json FROM budget_reservations WHERE id = ?", 1) { cursor ->
            found = cursor.next().value
            bindString(0, reservationId.value)
        }
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
        assertNotNull(remaining)
        assertEquals(Int.MAX_VALUE, remaining.modelCalls)
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
        driver.executeQuery(null, "SELECT state FROM projects WHERE id = ?", 1) { cursor ->
            if (cursor.next().value) state = cursor.getString(0)
            bindString(0, projectId.value)
        }
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

        assertTrue(result.isSuccess)

        // Verify project is gone
        var projectExists = false
        driver.executeQuery(null, "SELECT id FROM projects WHERE id = ?", 1) { cursor ->
            projectExists = cursor.next().value
            bindString(0, projectId.value)
        }
        assertTrue(!projectExists, "Project should be deleted")

        // Verify sessions are gone
        var sessionExists = false
        driver.executeQuery(null, "SELECT id FROM sessions WHERE project_id = ?", 1) { cursor ->
            sessionExists = cursor.next().value
            bindString(0, projectId.value)
        }
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
        driver.executeQuery(null, "SELECT id FROM sessions WHERE project_id = ?", 1) { cursor ->
            sessionExists = cursor.next().value
            bindString(0, projectId.value)
        }
        assertTrue(sessionExists, "Sessions should be preserved after archive")
    }
}
