package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.Budget
import dev.aidos.kernel.BudgetScope
import dev.aidos.kernel.ProjectId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for BudgetLedger (RFC-0028) and ProjectLifecycle (RFC-0002) with `PRAGMA foreign_keys = ON`.
 */
class BudgetAndProjectTest {

    private val nowIso = "2026-08-05T00:00:00Z"
    private val idGen = UuidV7Generator()
    private val json = Json { ignoreUnknownKeys = true }

    private fun openDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("budget-project-test").toFile()
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as JdbcSqliteDriver
        var fkEnabled = 0L
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA foreign_keys",
            mapper = { cursor ->
                if (cursor.next().value) fkEnabled = cursor.getLong(0) ?: 0L
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        assertEquals(1L, fkEnabled, "PRAGMA foreign_keys must stay ON")
        return driver
    }

    private fun seedProject(
        driver: JdbcSqliteDriver,
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
        driver: JdbcSqliteDriver,
        projectId: String,
        sessionId: String,
        state: String = "RUNNING",
        parentSessionId: String? = null,
    ) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO sessions (id, project_id, name, role, state, parent_session_id, " +
                "created_at, last_active_at, state_updated_at) VALUES (?, ?, 'test', 'DRIVER', ?, ?, ?, ?, ?)",
            7
        ) {
            bindString(0, sessionId)
            bindString(1, projectId)
            bindString(2, state)
            bindString(3, parentSessionId)
            bindString(4, nowIso)
            bindString(5, nowIso)
            bindString(6, nowIso)
        }
    }

    private fun seedRun(
        driver: JdbcSqliteDriver,
        projectId: String,
        runId: String,
        state: String = "PENDING",
        sessionId: String = "session-1",
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
            bindString(1, sessionId)
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
        driver: JdbcSqliteDriver,
        scope: String,
        scopeId: String,
        modelCalls: Long = 0,
        inputTokens: Long = 0,
        outputTokens: Long = 0,
        costUnits: Long = 0,
        steps: Long = 0,
        toolInvocations: Long = 0,
        limitJson: String? = null,
    ) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO budget_ledger (id, scope, scope_id, model_calls, input_tokens, " +
                "output_tokens, cost_units, steps, tool_invocations, limit_json, updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            11
        ) {
            bindString(0, "${scope}-${scopeId}")
            bindString(1, scope)
            bindString(2, scopeId)
            bindLong(3, modelCalls)
            bindLong(4, inputTokens)
            bindLong(5, outputTokens)
            bindLong(6, costUnits)
            bindLong(7, steps)
            bindLong(8, toolInvocations)
            bindString(9, limitJson)
            bindString(10, nowIso)
        }
    }

    private fun seedAudit(
        driver: JdbcSqliteDriver,
        projectId: String,
        auditId: String = "audit-$projectId",
        sequence: Long = 1L,
    ) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO audit_log (" +
                "id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id, detail_json" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9
        ) {
            bindString(0, auditId)
            bindString(1, projectId)
            bindLong(2, sequence)
            bindString(3, nowIso)
            bindString(4, "Test")
            bindString(5, "USER")
            bindString(6, "user-1")
            bindString(7, "device-1")
            bindString(8, "{}")
        }
    }

    private fun seedAttemptForReservation(
        driver: JdbcSqliteDriver,
        projectId: String,
        sessionId: String = "session-1",
        runId: String = "run-1",
        taskId: String = "task-1",
        attemptId: String = "attempt-1",
        auditId: String = "audit-1",
        capabilityId: String? = null,
    ) {
        seedSession(driver, projectId, sessionId, "SLEEPING")
        seedRun(driver, projectId, runId, "RUNNING", sessionId = sessionId)
        seedAudit(driver, projectId, auditId = auditId)
        driver.execute(
            null,
            "INSERT OR IGNORE INTO tasks (" +
                "id, run_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            9
        ) {
            bindString(0, taskId)
            bindString(1, runId)
            bindString(2, sessionId)
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
                "id, task_id, attempt_number, started_at, state, capability_id, audit_ref" +
                ") VALUES (?, ?, ?, ?, ?, ?, ?)",
            7
        ) {
            bindString(0, attemptId)
            bindString(1, taskId)
            bindLong(2, 1)
            bindString(3, nowIso)
            bindString(4, "RUNNING")
            bindString(5, capabilityId)
            bindString(6, auditId)
        }
    }

    private fun countRows(driver: JdbcSqliteDriver, sql: String, vararg params: String): Long {
        var count = 0L
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) count = cursor.getLong(0) ?: 0L
                QueryResult.Value(Unit)
            },
            parameters = params.size,
            binders = {
                params.forEachIndexed { idx, value -> bindString(idx, value) }
            },
        )
        return count
    }

    // ========== Budget Ledger Tests ==========

    @Test
    fun `reserve succeeds within budget using distinct attempt id`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedAttemptForReservation(driver, projectId, runId = "run-1", attemptId = "attempt-1")
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 0, steps = 0,
            limitJson = """{"modelCalls":8,"steps":24}"""
        )

        val estimate = Budget(modelCalls = 2, steps = 5)
        val result = ledger.reserve(BudgetScope.RUN, "run-1", estimate)

        assertTrue(result.isSuccess, "reserve failed: ${result.exceptionOrNull()?.message}")
        val reservationId = result.getOrNull()
        assertNotNull(reservationId)

        var recordedAttemptId: String? = null
        driver.executeQuery(
            identifier = null,
            sql = "SELECT attempt_id FROM budget_reservations WHERE id = ?",
            mapper = { cursor ->
                if (cursor.next().value) recordedAttemptId = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, reservationId.value) },
        )
        assertEquals("attempt-1", recordedAttemptId)
    }

    @Test
    fun `reserve works for PROJECT and SESSION scopes without binding scopeId to attempt_id`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedAttemptForReservation(
            driver,
            projectId = projectId,
            sessionId = "session-1",
            runId = "run-1",
            attemptId = "attempt-real-1",
        )
        seedBudgetLedger(
            driver, "PROJECT", projectId,
            modelCalls = 0, costUnits = 0,
            limitJson = """{"model_calls":10,"cost_units":5000}"""
        )
        seedBudgetLedger(
            driver, "SESSION", "session-1",
            modelCalls = 0, costUnits = 0,
            limitJson = """{"model_calls":4,"cost_units":2000}"""
        )

        val projRes = ledger.reserve(BudgetScope.PROJECT, projectId, Budget(modelCalls = 2, costUnits = 1000))
        val sessRes = ledger.reserve(BudgetScope.SESSION, "session-1", Budget(modelCalls = 1, costUnits = 500))

        assertTrue(projRes.isSuccess, "project reserve failed: ${projRes.exceptionOrNull()?.message}")
        assertTrue(sessRes.isSuccess, "session reserve failed: ${sessRes.exceptionOrNull()?.message}")

        assertEquals(8, ledger.remaining(BudgetScope.PROJECT, projectId)?.modelCalls)
        assertEquals(4000L, ledger.remaining(BudgetScope.PROJECT, projectId)?.costUnits)
        assertEquals(3, ledger.remaining(BudgetScope.SESSION, "session-1")?.modelCalls)
        assertEquals(1500L, ledger.remaining(BudgetScope.SESSION, "session-1")?.costUnits)
    }

    @Test
    fun `reserve rejects invalid or missing attempt cleanly under FK enforcement`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedBudgetLedger(
            driver, "PROJECT", projectId,
            limitJson = """{"model_calls":10}"""
        )

        val noAttemptRes = ledger.reserve(BudgetScope.PROJECT, projectId, Budget(modelCalls = 1))
        assertTrue(noAttemptRes.isFailure)

        seedAttemptForReservation(driver, projectId, attemptId = "attempt-1")
        val badAttemptRes = ledger.reserve(
            BudgetScope.PROJECT,
            projectId,
            Budget(modelCalls = 1),
            attemptId = "nonexistent-attempt",
        )
        assertTrue(badAttemptRes.isFailure)
    }

    @Test
    fun `reserve fails when budget exhausted`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedAttemptForReservation(driver, projectId, runId = "run-1", attemptId = "attempt-1")
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
    fun `remaining subtracts active uncommitted reservations and blocks overspend`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedAttemptForReservation(driver, projectId, runId = "run-1", attemptId = "attempt-1")
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 1, steps = 2, costUnits = 100,
            limitJson = """{"model_calls":8,"steps":24,"cost_units":1000}"""
        )

        val r1 = ledger.reserve(
            BudgetScope.RUN,
            "run-1",
            Budget(modelCalls = 3, steps = 8, costUnits = 400),
            attemptId = "attempt-1",
        )
        assertTrue(r1.isSuccess)

        val remAfterReserve = ledger.remaining(BudgetScope.RUN, "run-1")
        assertNotNull(remAfterReserve)
        assertEquals(4, remAfterReserve.modelCalls)     // 8 - 1 - 3
        assertEquals(14, remAfterReserve.steps)         // 24 - 2 - 8
        assertEquals(500L, remAfterReserve.costUnits)   // 1000 - 100 - 400

        val overspend = ledger.reserve(
            BudgetScope.RUN,
            "run-1",
            Budget(modelCalls = 5),
            attemptId = "attempt-1",
        )
        assertTrue(overspend.isFailure, "Reservation exceeding remaining uncommitted budget must fail")
    }

    @Test
    fun `release cancels reservation and restores remaining budget`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedAttemptForReservation(driver, projectId, runId = "run-1", attemptId = "attempt-1")
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 0, steps = 0,
            limitJson = """{"modelCalls":8,"steps":24}"""
        )

        val estimate = Budget(modelCalls = 2, steps = 5)
        val reserveResult = ledger.reserve(BudgetScope.RUN, "run-1", estimate)
        assertTrue(reserveResult.isSuccess, "reserve failed: ${reserveResult.exceptionOrNull()?.message}")

        assertEquals(6, ledger.remaining(BudgetScope.RUN, "run-1")?.modelCalls)
        assertEquals(19, ledger.remaining(BudgetScope.RUN, "run-1")?.steps)

        val reservationId = reserveResult.getOrNull()!!
        ledger.release(reservationId)

        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM budget_reservations WHERE id = ?", reservationId.value))
        assertEquals(8, ledger.remaining(BudgetScope.RUN, "run-1")?.modelCalls)
        assertEquals(24, ledger.remaining(BudgetScope.RUN, "run-1")?.steps)
    }

    @Test
    fun `settle records actual cost in budget_ledger, releases unused reserve, and is idempotent`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedAttemptForReservation(driver, projectId, runId = "run-1", attemptId = "attempt-1")
        seedBudgetLedger(
            driver, "RUN", "run-1",
            modelCalls = 0, steps = 0,
            limitJson = """{"model_calls":8,"steps":24,"input_tokens":5000,"output_tokens":2000,"cost_units":1000}"""
        )

        val estimate = Budget(modelCalls = 3, steps = 10, inputTokens = 1000, outputTokens = 500, costUnits = 400)
        val reserveResult = ledger.reserve(BudgetScope.RUN, "run-1", estimate)
        assertTrue(reserveResult.isSuccess, "reserve failed: ${reserveResult.exceptionOrNull()?.message}")

        val reservationId = reserveResult.getOrNull()!!
        val remDuringReserve = ledger.remaining(BudgetScope.RUN, "run-1")
        assertNotNull(remDuringReserve)
        assertEquals(5, remDuringReserve.modelCalls)
        assertEquals(14, remDuringReserve.steps)

        val actual = Budget(modelCalls = 1, steps = 3, inputTokens = 800, outputTokens = 200, costUnits = 150)
        ledger.settle(reservationId, actual)

        var reservedJson: String? = null
        driver.executeQuery(
            identifier = null,
            sql = "SELECT reserved_json FROM budget_reservations WHERE id = ?",
            mapper = { cursor ->
                if (cursor.next().value) reservedJson = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, reservationId.value) },
        )
        val settledJson = assertNotNull(reservedJson)
        val obj = json.parseToJsonElement(settledJson).jsonObject
        assertEquals("SETTLED", obj["status"]?.jsonPrimitive?.contentOrNull)
        assertEquals(800, obj["actual"]?.jsonObject?.get("input_tokens")?.jsonPrimitive?.intOrNull)

        // Verify unused reserved budget was released and actual spend persisted to budget_ledger
        val remAfterSettle = ledger.remaining(BudgetScope.RUN, "run-1")
        assertNotNull(remAfterSettle)
        assertEquals(7, remAfterSettle.modelCalls)     // 8 - 1 (not 8 - 3)
        assertEquals(21, remAfterSettle.steps)         // 24 - 3 (not 24 - 10)
        assertEquals(4200L, remAfterSettle.inputTokens)
        assertEquals(1800L, remAfterSettle.outputTokens)
        assertEquals(850L, remAfterSettle.costUnits)

        // Idempotent repeat call must not double-charge
        ledger.settle(reservationId, actual)
        val remAfterSecondSettle = ledger.remaining(BudgetScope.RUN, "run-1")
        assertEquals(remAfterSettle, remAfterSecondSettle)
    }

    @Test
    fun `remaining supports both snake_case and camelCase limit_json`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)
        seedBudgetLedger(
            driver, "RUN", "run-camel",
            modelCalls = 3, steps = 15,
            limitJson = """{"modelCalls":8,"steps":24}"""
        )
        seedBudgetLedger(
            driver, "RUN", "run-snake",
            modelCalls = 2, inputTokens = 400, outputTokens = 100, costUnits = 50, steps = 4, toolInvocations = 1,
            limitJson = """{"model_calls":8,"input_tokens":1000,"output_tokens":500,"cost_units":200,"steps":24,"tool_invocations":5}"""
        )

        val camel = ledger.remaining(BudgetScope.RUN, "run-camel")
        assertNotNull(camel)
        assertEquals(5, camel.modelCalls)
        assertEquals(9, camel.steps)

        val snake = ledger.remaining(BudgetScope.RUN, "run-snake")
        assertNotNull(snake)
        assertEquals(6, snake.modelCalls)
        assertEquals(600L, snake.inputTokens)
        assertEquals(400L, snake.outputTokens)
        assertEquals(150L, snake.costUnits)
        assertEquals(20, snake.steps)
        assertEquals(4, snake.toolInvocations)
    }

    @Test
    fun `remaining returns unlimited when no ledger entry`() = runBlocking {
        val driver = openDriver()
        val projectId = "proj-1"
        val ledger = SqlBudgetLedger(driver, { idGen.next() }, { nowIso })

        seedProject(driver, projectId)

        val remaining = ledger.remaining(BudgetScope.SESSION, "session-1")
        assertNull(remaining)
    }

    // ========== Project Lifecycle Tests ==========

    @Test
    fun `archiveProject succeeds with no active sessions and sets schema-valid CLOSED state`() = runBlocking {
        val driver = openDriver()
        val projectId = ProjectId("proj-1")
        val lifecycle = ProjectLifecycle(driver, { nowIso })

        seedProject(driver, projectId.value)

        val result = lifecycle.archiveProject(projectId)

        assertTrue(result.isSuccess, "archive failed: ${result.exceptionOrNull()?.message}")

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
        assertEquals("CLOSED", state)
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
    fun `deleteProject removes all project data with FK constraints enabled`() = runBlocking {
        val driver = openDriver()
        val projectId = ProjectId("proj-1")
        val lifecycle = ProjectLifecycle(driver, { nowIso })

        seedProject(driver, projectId.value)
        seedSession(driver, projectId.value, "session-1", "SLEEPING")
        seedRun(driver, projectId.value, "run-1", "COMPLETED")

        val result = lifecycle.deleteProject(projectId)

        assertTrue(result.isSuccess, "delete failed: ${result.exceptionOrNull()?.message}")
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM projects WHERE id = ?", projectId.value))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM sessions WHERE project_id = ?", projectId.value))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM runs WHERE project_id = ?", projectId.value))
    }

    @Test
    fun `deleteProject deletes populated child and grandchild FK tables and scoped budget_ledger rows cleanly`() = runBlocking {
        val driver = openDriver()
        val pid = "proj-1"
        val otherPid = "proj-2"
        val lifecycle = ProjectLifecycle(driver, { nowIso })

        // Seed target project and a separate untouched project
        seedProject(driver, pid)
        seedProject(driver, otherPid)
        seedSession(driver, otherPid, "other-session", "SLEEPING")
        seedRun(driver, otherPid, "other-run", "COMPLETED", sessionId = "other-session")
        seedBudgetLedger(driver, "PROJECT", otherPid, costUnits = 42, limitJson = """{"cost_units":100}""")

        // Target project sessions (including self-FK hierarchy), runs, plans, tasks, attempts, audit, capabilities
        seedSession(driver, pid, "session-parent", "ARCHIVED")
        seedSession(driver, pid, "session-child", "SLEEPING", parentSessionId = "session-parent")
        seedAudit(driver, pid, auditId = "audit-1", sequence = 1L)

        driver.execute(
            null,
            "INSERT INTO session_subscriptions (id, session_id, topic_patterns, created_at) VALUES (?, ?, ?, ?)",
            4
        ) {
            bindString(0, "sub-1")
            bindString(1, "session-child")
            bindString(2, """["run.*"]""")
            bindString(3, nowIso)
        }

        driver.execute(
            null,
            """
            INSERT INTO capabilities (
                id, project_id, permission, subject_id, subject_kind,
                scope_json, constraints_json, issued_at, issued_by_kind, issued_by_id,
                parent_capability_id, revocation_epoch, audit_ref
            ) VALUES (?, ?, 'MODEL_CALL', 'session-child', 'SESSION', '{}', '{}', ?, 'USER', 'user-1', ?, 0, ?)
            """.trimIndent(),
            5
        ) {
            bindString(0, "cap-parent")
            bindString(1, pid)
            bindString(2, nowIso)
            bindString(3, null)
            bindString(4, "audit-1")
        }
        driver.execute(
            null,
            """
            INSERT INTO capabilities (
                id, project_id, permission, subject_id, subject_kind,
                scope_json, constraints_json, issued_at, issued_by_kind, issued_by_id,
                parent_capability_id, revocation_epoch, audit_ref
            ) VALUES (?, ?, 'MODEL_CALL', 'session-child', 'SESSION', '{}', '{}', ?, 'SESSION', 'session-parent', ?, 0, ?)
            """.trimIndent(),
            5
        ) {
            bindString(0, "cap-child")
            bindString(1, pid)
            bindString(2, nowIso)
            bindString(3, "cap-parent")
            bindString(4, "audit-1")
        }
        driver.execute(
            null,
            "INSERT INTO capability_usage (capability_id, exercised_count) VALUES (?, 1)",
            1
        ) {
            bindString(0, "cap-child")
        }

        seedRun(driver, pid, "run-1", "COMPLETED", sessionId = "session-parent")
        seedRun(driver, pid, "run-2", "COMPLETED", sessionId = "session-child")

        driver.execute(
            null,
            "INSERT INTO execution_plans (id, run_id, proposed_by_task_id, proposed_at, state, task_count) VALUES (?, ?, ?, ?, 'APPROVED', 1)",
            4
        ) {
            bindString(0, "plan-1")
            bindString(1, "run-1")
            bindString(2, "task-model")
            bindString(3, nowIso)
        }

        driver.execute(
            null,
            "INSERT INTO tasks (id, run_id, plan_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json, awaiting_run_id) VALUES (?, ?, ?, ?, ?, 0, 'MODEL_CALL', 'model', 'COMPLETED', '{}', ?)",
            6
        ) {
            bindString(0, "task-model")
            bindString(1, "run-1")
            bindString(2, "plan-1")
            bindString(3, "session-parent")
            bindString(4, pid)
            bindString(5, "run-2")
        }
        driver.execute(
            null,
            "INSERT INTO tasks (id, run_id, plan_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json) VALUES (?, ?, ?, ?, ?, 1, 'TOOL_CALL', 'tool', 'COMPLETED', '{}')",
            5
        ) {
            bindString(0, "task-tool")
            bindString(1, "run-1")
            bindString(2, "plan-1")
            bindString(3, "session-parent")
            bindString(4, pid)
        }

        driver.execute(
            null,
            "INSERT INTO attempts (id, task_id, attempt_number, started_at, state, capability_id, audit_ref) VALUES (?, ?, 1, ?, 'COMPLETED', ?, ?)",
            5
        ) {
            bindString(0, "attempt-1")
            bindString(1, "task-model")
            bindString(2, nowIso)
            bindString(3, "cap-child")
            bindString(4, "audit-1")
        }

        driver.execute(
            null,
            "INSERT INTO tool_calls (call_id, run_id, model_task_id, tool_task_id, tool_name, arguments_json, schema_valid, outcome, step_index) VALUES (?, ?, ?, ?, 'read', '{}', 1, 'OK', 0)",
            4
        ) {
            bindString(0, "call-1")
            bindString(1, "run-1")
            bindString(2, "task-model")
            bindString(3, "task-tool")
        }
        driver.execute(
            null,
            "INSERT INTO worker_spawn_requests (task_id, brief, capabilities_json, worker_session_id, child_run_id, created_at) VALUES (?, 'work', '[]', ?, ?, ?)",
            4
        ) {
            bindString(0, "task-model")
            bindString(1, "session-child")
            bindString(2, "run-2")
            bindString(3, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO continuations (run_id, task_id, suspended_operation, operation_detail_json, created_at) VALUES (?, ?, 'USER_PROMPT', '{}', ?)",
            3
        ) {
            bindString(0, "run-1")
            bindString(1, "task-model")
            bindString(2, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO execution_edges (id, project_id, from_node_id, from_node_kind, to_node_id, to_node_kind, edge_kind) VALUES (?, ?, 'run-1', 'RUN', 'task-model', 'TASK', 'CONSUMED')",
            2
        ) {
            bindString(0, "edge-1")
            bindString(1, pid)
        }

        // Content graph + prompt_provenance + redaction_events
        driver.execute(
            null,
            """
            INSERT INTO content_nodes (
                id, project_id, kind, name, mutability_policy, sensitivity_level,
                egress_eligibility, storage_location_json, content_hash, content_type,
                size_bytes, created_at, created_by_kind, created_by_id, updated_at
            ) VALUES (?, ?, 'ARTIFACT', 'n1', 'IMMUTABLE', 'PUBLIC', 'ALLOW', '{}', 'h1', 'text/plain', 1, ?, 'RUNTIME', 'rt', ?)
            """.trimIndent(),
            4
        ) {
            bindString(0, "cn-1")
            bindString(1, pid)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        driver.execute(
            null,
            """
            INSERT INTO content_nodes (
                id, project_id, kind, name, mutability_policy, sensitivity_level,
                egress_eligibility, storage_location_json, content_hash, content_type,
                size_bytes, created_at, created_by_kind, created_by_id, updated_at
            ) VALUES (?, ?, 'ARTIFACT', 'n2', 'IMMUTABLE', 'PUBLIC', 'ALLOW', '{}', 'h2', 'text/plain', 1, ?, 'RUNTIME', 'rt', ?)
            """.trimIndent(),
            4
        ) {
            bindString(0, "cn-2")
            bindString(1, pid)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO provenance_edges (id, from_node_id, to_node_id, edge_kind, created_at) VALUES (?, 'cn-1', 'cn-2', 'DERIVED_FROM', ?)",
            2
        ) {
            bindString(0, "pe-1")
            bindString(1, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO prompt_provenance (id, attempt_id, content_node_id, included, role, token_count) VALUES (?, 'attempt-1', 'cn-1', 1, 'context', 5)",
            1
        ) {
            bindString(0, "pp-1")
        }
        driver.execute(
            null,
            "INSERT INTO redaction_events (id, project_id, boundary, detection_kind, content_node_id, occurred_at) VALUES (?, ?, 'prompt', 'pattern:jwt', 'cn-1', ?)",
            3
        ) {
            bindString(0, "red-1")
            bindString(1, pid)
            bindString(2, nowIso)
        }

        // Intent graph (self-FK + edges + proposals referencing run and audit_log)
        driver.execute(
            null,
            """
            INSERT INTO intent_nodes (
                id, project_id, type, title, created_at, created_by_kind, created_by_id,
                modified_at, modified_by_kind, modified_by_id
            ) VALUES (?, ?, 'GOAL', 'Root Goal', ?, 'USER', 'user-1', ?, 'USER', 'user-1')
            """.trimIndent(),
            4
        ) {
            bindString(0, "in-parent")
            bindString(1, pid)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        driver.execute(
            null,
            """
            INSERT INTO intent_nodes (
                id, project_id, type, title, parent_id, created_at, created_by_kind, created_by_id,
                modified_at, modified_by_kind, modified_by_id
            ) VALUES (?, ?, 'SUB_GOAL', 'Child Goal', 'in-parent', ?, 'USER', 'user-1', ?, 'USER', 'user-1')
            """.trimIndent(),
            4
        ) {
            bindString(0, "in-child")
            bindString(1, pid)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO intent_edges (id, project_id, from_node_id, to_node_id, edge_kind, created_at) VALUES (?, ?, 'in-child', 'in-parent', 'DEPENDS_ON', ?)",
            3
        ) {
            bindString(0, "ie-1")
            bindString(1, pid)
            bindString(2, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO intent_proposals (id, project_id, operations_json, rationale, proposed_by_run_id, proposed_at, run_taint, state, expires_at, audit_ref) VALUES (?, ?, '[]', 'reason', 'run-1', ?, 'TRUSTED', 'ACCEPTED', ?, 'audit-1')",
            4
        ) {
            bindString(0, "ip-1")
            bindString(1, pid)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }

        // Memory (self-FK), jobs, notifications, events, fingerprints, reconciliations, lock_breaks, egress, secrets, metrics, degradation, adoptions
        driver.execute(
            null,
            "INSERT INTO memory_entries (id, session_id, project_id, kind, content, source_refs_json, created_by_kind, created_by_id, confidence, trust_level, scope, created_at) VALUES (?, 'session-child', ?, 'FACT', 'fact2', '[\"r1\"]', 'USER', 'user-1', 'USER_STATED', 'TRUSTED', 'SESSION', ?)",
            3
        ) {
            bindString(0, "mem-2")
            bindString(1, pid)
            bindString(2, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO memory_entries (id, session_id, project_id, kind, content, source_refs_json, created_by_kind, created_by_id, confidence, trust_level, scope, created_at, superseded_by) VALUES (?, 'session-child', ?, 'FACT', 'fact1', '[\"r1\"]', 'USER', 'user-1', 'USER_STATED', 'TRUSTED', 'SESSION', ?, 'mem-2')",
            3
        ) {
            bindString(0, "mem-1")
            bindString(1, pid)
            bindString(2, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO scheduled_jobs (id, project_id, session_id, name, trigger_json, guarantee_class, work_class, created_at) VALUES (?, ?, 'session-child', 'job', '{}', 'EVENTUAL', 'SCHEDULED', ?)",
            3
        ) {
            bindString(0, "job-1")
            bindString(1, pid)
            bindString(2, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO notifications (id, project_id, category, title, body) VALUES (?, ?, 'COMPLETION', 'Done', 'Body')",
            2
        ) {
            bindString(0, "notif-1")
            bindString(1, pid)
        }
        driver.execute(
            null,
            "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, source, payload) VALUES (?, ?, 1, 'RunCompleted', 1, 'FACT', 'PUBLIC', ?, 'rt', '{}')",
            3
        ) {
            bindString(0, "ev-1")
            bindString(1, pid)
            bindString(2, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO repo_fingerprints (project_id, head_ref, head_commit, index_checksum, dirty_path_count, observed_at) VALUES (?, 'main', 'abc', 'chk', 0, ?)",
            2
        ) {
            bindString(0, pid)
            bindString(1, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO reconciliations (id, project_id, classification, nodes_invalidated, nodes_dangling, runs_terminated, intent_conflicted, performed_at, audit_ref) VALUES (?, ?, 'HEAD_MOVED', 0, 0, 0, 0, ?, 'audit-1')",
            3
        ) {
            bindString(0, "rec-1")
            bindString(1, pid)
            bindString(2, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO lock_breaks (id, project_id, previous_instance_id, broken_by_instance_id, broken_at, audit_ref) VALUES (?, ?, 'inst-0', 'inst-1', ?, 'audit-1')",
            3
        ) {
            bindString(0, "lb-1")
            bindString(1, pid)
            bindString(2, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO egress_records (id, project_id, attempt_id, capability_id, destination_host, resolved_address_class, method, request_bytes, response_bytes, payload_hash, content_sensitivity, run_taint, duration_ms, outcome, occurred_at) VALUES (?, ?, 'attempt-1', 'cap-child', 'api.example.com', 'PUBLIC', 'POST', 10, 20, 'ph', 'LOW', 'TRUSTED', 5, 'OK', ?)",
            3
        ) {
            bindString(0, "eg-1")
            bindString(1, pid)
            bindString(2, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO secret_accesses (id, secret_id, consumer_kind, consumer_id, attempt_id, capability_id, occurred_at) VALUES (?, 'sec-1', 'PROVIDER', 'anthropic', 'attempt-1', 'cap-child', ?)",
            2
        ) {
            bindString(0, "sa-1")
            bindString(1, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO metric_samples (id, project_id, name, value, unit, bucket_start) VALUES (?, ?, 'lat', 1.0, 'ms', ?)",
            3
        ) {
            bindString(0, "ms-1")
            bindString(1, pid)
            bindString(2, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO degradation_events (id, rung, trigger, entered_at, project_id) VALUES (?, 1, 'offline', ?, ?)",
            3
        ) {
            bindString(0, "deg-1")
            bindString(1, nowIso)
            bindString(2, pid)
        }
        driver.execute(
            null,
            "INSERT INTO instruction_adoptions (project_id, set_hash, adopted_at, adopted_by, source_manifest) VALUES (?, 'sh-1', ?, 'user', '[]')",
            2
        ) {
            bindString(0, pid)
            bindString(1, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO mcp_operation_adoptions (project_id, server_name, operation_name, descriptor_hash, description, input_schema_json, adopted_at) VALUES (?, 'srv', 'op', 'dh', 'desc', '{}', ?)",
            2
        ) {
            bindString(0, pid)
            bindString(1, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) VALUES ('project', ?, 'k', '1', ?, 'USER')",
            2
        ) {
            bindString(0, pid)
            bindString(1, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) VALUES ('session', 'session-child', 'k', '1', ?, 'USER')",
            1
        ) {
            bindString(0, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO retention_policy (scope, scope_id, object_class, retention_days) VALUES ('project', ?, 'events', 30)",
            1
        ) {
            bindString(0, pid)
        }

        // Scoped budget_ledger rows across PROJECT, SESSION, RUN, CAPABILITY, and attempt + budget_reservations
        seedBudgetLedger(driver, "PROJECT", pid, limitJson = """{"cost_units":100}""")
        seedBudgetLedger(driver, "SESSION", "session-child", limitJson = """{"cost_units":50}""")
        seedBudgetLedger(driver, "RUN", "run-1", limitJson = """{"cost_units":25}""")
        seedBudgetLedger(driver, "CAPABILITY", "cap-child", limitJson = """{"cost_units":10}""")
        seedBudgetLedger(driver, "RUN", "attempt-1", limitJson = """{"cost_units":5}""")
        driver.execute(
            null,
            "INSERT INTO budget_reservations (id, attempt_id, reserved_json, created_at) VALUES (?, 'attempt-1', '{}', ?)",
            2
        ) {
            bindString(0, "res-1")
            bindString(1, nowIso)
        }

        val result = lifecycle.deleteProject(ProjectId(pid))
        assertTrue(result.isSuccess, "delete failed: ${result.exceptionOrNull()?.message}")

        // Every project row & scoped budget row must be removed
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM projects WHERE id = ?", pid))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM audit_log WHERE project_id = ?", pid))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM runs WHERE project_id = ?", pid))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM tasks WHERE project_id = ?", pid))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM attempts WHERE id = ?", "attempt-1"))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM budget_reservations WHERE id = ?", "res-1"))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM capabilities WHERE project_id = ?", pid))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM intent_nodes WHERE project_id = ?", pid))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM content_nodes WHERE project_id = ?", pid))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM memory_entries WHERE project_id = ?", pid))
        assertEquals(0L, countRows(driver, "SELECT COUNT(*) FROM budget_ledger WHERE scope_id IN (?, ?, ?, ?, ?)", pid, "session-child", "run-1", "cap-child", "attempt-1"))
        assertFalse(countRows(driver, "SELECT COUNT(*) FROM settings WHERE scope = 'project' AND scope_id = ?", pid) > 0)

        // Separate project must remain untouched
        assertEquals(1L, countRows(driver, "SELECT COUNT(*) FROM projects WHERE id = ?", otherPid))
        assertEquals(1L, countRows(driver, "SELECT COUNT(*) FROM runs WHERE project_id = ?", otherPid))
        assertEquals(1L, countRows(driver, "SELECT COUNT(*) FROM budget_ledger WHERE scope_id = ?", otherPid))
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
        assertEquals(1L, countRows(driver, "SELECT COUNT(*) FROM sessions WHERE project_id = ?", projectId.value))
    }
}
