package dev.aidos.api

import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

class RuntimeUsageSummaryTest {

    @Test
    fun getUsageSummary_aggregatesActiveRunBudgetLedgerAndLocalVsRemoteCalls() = runBlocking {
        val home = Files.createTempDirectory("runtime-usage-summary").toFile()
        try {
            val nowIso = { "2026-11-20T09:00:00Z" }
            val userDb = AidosStorage.openUser(DesktopPaths.userDb(home.absolutePath), "test", nowIso)
            val driver = AidosStorage.openProject(
                DesktopPaths.stateDb(home.resolve("projects/proj-usage").apply { mkdirs() }.absolutePath),
                "test",
                nowIso,
            ).driver

            val client = RealRuntimeClient().apply {
                userDriver = userDb.driver
                projectDbFactory = { driver }
                projectLocker = JvmProjectLocker()
                runtimeManagedProjectsRoot = home.resolve("projects").absolutePath
            }

            val created = client.projects.create(
                CreateProjectRequest("UsageTest", "desc", ProjectLocation.RuntimeManaged("proj-usage"))
            )
            assertIs<ProjectResult.Success>(created)
            val projectId = created.project.id

            val session = client.sessions.create(CreateSessionRequest(projectId, "Main"))
            assertIs<SessionResult.Success>(session)
            val sessionId = session.session.id
            val runId = "run-1"
            val taskId = "task-1"
            driver.execute(
                null,
                "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, source, payload, causal_depth) VALUES ('evt-1', ?, 1, 'UserMessage', 1, 'SIGNAL', 'SESSION', '2026-11-20T09:00:00Z', 'test', '{}', 0)",
                1,
            ) {
                bindString(0, projectId)
            }
            driver.execute(
                null,
                "INSERT INTO runs (id, project_id, session_id, trigger_event_id, state, step_index, started_at, retry_policy_json, platform_profile, device_id) VALUES (?, ?, ?, 'evt-1', 'RUNNING', 3, '2026-11-20T09:01:00Z', '{}', 'DESKTOP', 'dev-1')",
                3,
            ) {
                bindString(0, runId)
                bindString(1, projectId)
                bindString(2, sessionId)
            }
            driver.execute(
                null,
                "INSERT INTO tasks (id, run_id, project_id, session_id, ordinal, kind, description, state, retry_policy_json) VALUES (?, ?, ?, ?, 0, 'MODEL_CALL', 'step', 'RUNNING', '{}')",
                4,
            ) {
                bindString(0, taskId)
                bindString(1, runId)
                bindString(2, projectId)
                bindString(3, sessionId)
            }
            driver.execute(
                null,
                "INSERT INTO budget_ledger (id, scope, scope_id, period_start, model_calls, input_tokens, output_tokens, cost_units, steps, tool_invocations, limit_json, updated_at) VALUES ('bl-run', 'RUN', ?, '2026-11-20T00:00:00Z', 2, 300, 150, 12, 3, 1, '{\"steps\":10}', '2026-11-20T09:02:00Z')",
                1,
            ) {
                bindString(0, runId)
            }
            driver.execute(
                null,
                "INSERT INTO budget_ledger (id, scope, scope_id, period_start, model_calls, input_tokens, output_tokens, cost_units, steps, tool_invocations, limit_json, updated_at) VALUES ('bl-proj', 'PROJECT', ?, '2026-11-20T00:00:00Z', 5, 1000, 500, 40, 8, 2, NULL, '2026-11-20T09:02:00Z')",
                1,
            ) {
                bindString(0, projectId)
            }
            driver.execute(
                null,
                "INSERT INTO audit_log (id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id, detail_json) VALUES ('audit-1', ?, 1, '2026-11-20T09:01:10Z', 'MODEL_CALL', 'RUNTIME', 'daemon', 'dev-1', '{}')",
                1,
            ) {
                bindString(0, projectId)
            }
            driver.execute(
                null,
                "INSERT INTO audit_log (id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id, detail_json) VALUES ('audit-2', ?, 2, '2026-11-20T09:02:10Z', 'MODEL_CALL', 'RUNTIME', 'daemon', 'dev-1', '{}')",
                1,
            ) {
                bindString(0, projectId)
            }
            driver.execute(
                null,
                "INSERT INTO attempts (id, task_id, attempt_number, started_at, ended_at, state, model_provider, tokens_input, tokens_output, cost_units, recovery_class, audit_ref) VALUES ('att-1', ?, 0, '2026-11-20T09:01:10Z', '2026-11-20T09:01:12Z', 'COMPLETED', 'local', 200, 100, 0, 'PURE', 'audit-1')",
                1,
            ) {
                bindString(0, taskId)
            }
            driver.execute(
                null,
                "INSERT INTO attempts (id, task_id, attempt_number, started_at, ended_at, state, model_provider, tokens_input, tokens_output, cost_units, recovery_class, audit_ref) VALUES ('att-2', ?, 1, '2026-11-20T09:02:10Z', '2026-11-20T09:02:12Z', 'COMPLETED', 'anthropic', 400, 200, 12, 'PURE', 'audit-2')",
                1,
            ) {
                bindString(0, taskId)
            }

            val summary = client.runtime.getUsageSummary(projectId)
            assertEquals(runId, summary.activeRunId)
            assertEquals(3, summary.activeRunSteps)
            assertEquals(10, summary.activeRunMaxSteps)
            assertEquals(450L, summary.activeRunTokens)
            assertEquals(12L, summary.activeRunCostUnits)
            assertEquals(1500L, summary.projectTokens)
            assertEquals(40L, summary.projectCostUnits)
            assertEquals(1L, summary.localCalls)
            assertEquals(1L, summary.remoteCalls)
            assertEquals("600 tok", summary.lastUsageLabel)
            assertNotNull(summary.lastUsageAtMs)

            driver.close()
            userDb.driver.close()
        } finally {
            home.deleteRecursively()
        }
    }
}
