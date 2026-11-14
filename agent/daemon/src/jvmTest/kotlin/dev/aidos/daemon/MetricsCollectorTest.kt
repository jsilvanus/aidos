package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.io.File
import java.nio.file.Files
import kotlin.math.abs
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for MetricsCollector (RFC-0037) backed by real project SQLite storage
 * with `PRAGMA foreign_keys = ON`.
 */
class MetricsCollectorTest {

    private lateinit var tempDir: File
    private lateinit var driver: SqlDriver
    private lateinit var collector: MetricsCollector

    private data class RecordedSample(
        val projectId: String?,
        val name: String,
        val value: Double,
        val unit: String,
        val labelsJson: String,
    )

    @BeforeTest
    fun setUp() {
        tempDir = Files.createTempDirectory("metrics-collector-test").toFile()
        val projectDir = File(tempDir, "project").apply { mkdirs() }
        val projectDb = AidosStorage.openProject(
            DesktopPaths.stateDb(projectDir.path),
            "0.1.0-alpha",
            { "2026-11-14T08:00:00Z" },
        )
        driver = projectDb.driver
        collector = MetricsCollector(driver)
    }

    @AfterTest
    fun tearDown() {
        runCatching { driver.close() }
        tempDir.deleteRecursively()
    }

    @Test
    fun testEmptyProjectOmitsFakeZeroMetricsAndSkipsUnknownProject() {
        var fkEnabled = 0L
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA foreign_keys",
            mapper = { c ->
                if (c.next().value) fkEnabled = c.getLong(0) ?: 0L
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        assertEquals(1L, fkEnabled, "PRAGMA foreign_keys must be enabled on project DB")

        seedProject("proj-empty")

        // Collect for a valid project that has no runs/tasks/storage/attempts/calls yet
        collector.collect("proj-empty")
        // Also ensure unknown project_id skips cleanly without violating FK
        collector.collect("nonexistent-project")

        val samples = readSamples()
        assertTrue(samples.isEmpty(), "Empty project must not emit fake 0.0 metrics, got: $samples")
    }

    @Test
    fun testCollectComputesRealScopedMetricsAndPreservesForeignKeys() {
        seedProject("proj-1")
        seedProject("proj-other")

        seedSession("sess-1", "proj-1")
        seedEvent("evt-1", "proj-1", 1L)
        seedRun("run-1", "sess-1", "proj-1", "evt-1", state = "COMPLETED", errorCode = null)
        seedRun("run-2", "sess-1", "proj-1", "evt-1", state = "COMPLETED", errorCode = null)
        seedRun("run-3", "sess-1", "proj-1", "evt-1", state = "FAILED", errorCode = "runtime.timeout")

        // Other project run should not leak when collecting proj-1
        seedSession("sess-other", "proj-other")
        seedEvent("evt-other", "proj-other", 1L)
        seedRun("run-other", "sess-other", "proj-other", "evt-other", state = "FAILED", errorCode = "other")

        // Tasks: 1000ms and 2000ms
        seedTask("task-1", "run-1", "sess-1", "proj-1", 1L, "2026-11-14T08:00:00.000Z", "2026-11-14T08:00:01.000Z")
        seedTask("task-2", "run-2", "sess-1", "proj-1", 1L, "2026-11-14T08:00:00.000Z", "2026-11-14T08:00:02.000Z")

        // Audit log: ModelLoaded event + generic audit entries
        seedAudit(
            id = "aud-model",
            projectId = "proj-1",
            sequence = 1L,
            kind = "ModelLoaded",
            detailJson = """{"model_id":"qwen2.5-7b","duration_ms":450.0}""",
        )

        // Content nodes: 4096 active bytes + deleted 9999 bytes (excluded)
        seedContentNode("cn-1", "proj-1", 1024L, "ACTIVE")
        seedContentNode("cn-2", "proj-1", 3072L, "ACTIVE")
        seedContentNode("cn-deleted", "proj-1", 9999L, "DELETED")

        // Attempts: 1 local (unmeasured cost = NULL), 1 remote anthropic (cost = 25)
        seedAttempt(
            id = "att-1",
            taskId = "task-1",
            attemptNumber = 1L,
            provider = "local",
            tokensIn = 100L,
            tokensOut = 200L,
            costUnits = null,
            auditRef = "aud-model",
        )
        seedAttempt(
            id = "att-2",
            taskId = "task-2",
            attemptNumber = 1L,
            provider = "anthropic",
            tokensIn = 350L,
            tokensOut = 150L,
            costUnits = 25L,
            auditRef = "aud-model",
        )

        // Tool calls: 2 calls on run-1
        seedToolCall("call-1", "run-1", "task-1", "read_file", 1L)
        seedToolCall("call-2", "run-1", "task-1", "write_file", 2L)

        collector.collect("proj-1")

        val samples = readSamples()
        assertTrue(samples.isNotEmpty())
        assertTrue(samples.all { it.projectId == "proj-1" }, "Every sample must reference real proj-1")

        val completedRuns = samples.find { it.name == "run.outcomes" && it.labelsJson.contains("COMPLETED") }
        assertEquals(2.0, completedRuns?.value)

        val failedRuns = samples.find { it.name == "run.outcomes" && it.labelsJson.contains("runtime.timeout") }
        assertEquals(1.0, failedRuns?.value)

        val avgStep = samples.find { it.name == "step.duration.avg" }
        val minStep = samples.find { it.name == "step.duration.min" }
        val maxStep = samples.find { it.name == "step.duration.max" }
        assertApprox(1500.0, avgStep?.value, tolerance = 5.0)
        assertApprox(1000.0, minStep?.value, tolerance = 5.0)
        assertApprox(2000.0, maxStep?.value, tolerance = 5.0)

        val loadTime = samples.find { it.name == "model.load_time" }
        assertApprox(450.0, loadTime?.value, tolerance = 0.01)
        assertTrue(loadTime?.labelsJson?.contains("qwen2.5-7b") == true)

        val storage = samples.find { it.name == "storage.total" }
        assertEquals(4096.0, storage?.value)

        val localTokens = samples.find { it.name == "tokens.used" && it.labelsJson.contains("\"local\"") }
        val remoteTokens = samples.find { it.name == "tokens.used" && it.labelsJson.contains("\"anthropic\"") }
        val localCost = samples.find { it.name == "cost.units" && it.labelsJson.contains("\"local\"") }
        val remoteCost = samples.find { it.name == "cost.units" && it.labelsJson.contains("\"anthropic\"") }
        assertEquals(300.0, localTokens?.value)
        assertEquals(500.0, remoteTokens?.value)
        assertNull(localCost, "Unmeasured cost_units should not emit fake 0.0")
        assertEquals(25.0, remoteCost?.value)

        val totalCalls = samples.find { it.name == "calls.total" }
        val localRatio = samples.find { it.name == "calls.local_ratio" }
        assertEquals(2.0, totalCalls?.value)
        assertApprox(0.5, localRatio?.value, tolerance = 0.001)
    }

    @Test
    fun testNoArgCollectResolvesRealProjects() {
        // Empty DB with no projects writes 0 samples without error
        collector.collect()
        assertTrue(readSamples().isEmpty())

        seedProject("proj-auto")
        seedSession("sess-a", "proj-auto")
        seedEvent("evt-a", "proj-auto", 1L)
        seedRun("run-a", "sess-a", "proj-auto", "evt-a", state = "COMPLETED", errorCode = null)

        collector.collect()
        val samples = readSamples()
        assertEquals(1, samples.size)
        assertEquals("proj-auto", samples[0].projectId)
        assertEquals("run.outcomes", samples[0].name)
        assertEquals(1.0, samples[0].value)
    }

    private fun assertApprox(expected: Double, actual: Double?, tolerance: Double) {
        assertTrue(actual != null && abs(expected - actual) <= tolerance, "Expected ~$expected (+/-$tolerance), got $actual")
    }

    private fun readSamples(): List<RecordedSample> {
        val list = mutableListOf<RecordedSample>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT project_id, name, value, unit, labels_json FROM metric_samples",
            mapper = { c ->
                while (c.next().value) {
                    list.add(
                        RecordedSample(
                            projectId = c.getString(0),
                            name = c.getString(1)!!,
                            value = c.getDouble(2)!!,
                            unit = c.getString(3)!!,
                            labelsJson = c.getString(4)!!,
                        ),
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return list
    }

    private fun seedProject(id: String) {
        val now = "2026-11-14T08:00:00Z"
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO projects (id, name, root_path, project_type, state, created_at, updated_at, state_updated_at)
                VALUES (?, ?, ?, 'generic', 'OPEN', ?, ?, ?)
            """,
            parameters = 6,
        ) {
            bindString(0, id)
            bindString(1, "Project $id")
            bindString(2, File(tempDir, id).absolutePath)
            bindString(3, now)
            bindString(4, now)
            bindString(5, now)
        }
    }

    private fun seedSession(id: String, projectId: String) {
        val now = "2026-11-14T08:00:00Z"
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at)
                VALUES (?, ?, 'Driver', 'DRIVER', 'RUNNING', ?, ?, ?)
            """,
            parameters = 5,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindString(2, now)
            bindString(3, now)
            bindString(4, now)
        }
    }

    private fun seedEvent(id: String, projectId: String, sequence: Long) {
        val now = "2026-11-14T08:00:00Z"
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, source, payload)
                VALUES (?, ?, ?, 'UserTurn', 1, 'COMMAND', 'PUBLIC', ?, 'test', '{}')
            """,
            parameters = 4,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindLong(2, sequence)
            bindString(3, now)
        }
    }

    private fun seedRun(
        id: String,
        sessionId: String,
        projectId: String,
        eventId: String,
        state: String,
        errorCode: String?,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, state, error_code, retry_policy_json, platform_profile, device_id)
                VALUES (?, ?, ?, ?, '2026-11-14T08:00:00Z', ?, ?, '{}', 'DESKTOP', 'dev-1')
            """,
            parameters = 6,
        ) {
            bindString(0, id)
            bindString(1, sessionId)
            bindString(2, projectId)
            bindString(3, eventId)
            bindString(4, state)
            bindString(5, errorCode)
        }
    }

    private fun seedTask(
        id: String,
        runId: String,
        sessionId: String,
        projectId: String,
        ordinal: Long,
        startedAt: String,
        endedAt: String,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, state, started_at, ended_at, retry_policy_json)
                VALUES (?, ?, ?, ?, ?, 'MODEL_CALL', 'step', 'COMPLETED', ?, ?, '{}')
            """,
            parameters = 7,
        ) {
            bindString(0, id)
            bindString(1, runId)
            bindString(2, sessionId)
            bindString(3, projectId)
            bindLong(4, ordinal)
            bindString(5, startedAt)
            bindString(6, endedAt)
        }
    }

    private fun seedAudit(
        id: String,
        projectId: String,
        sequence: Long,
        kind: String,
        detailJson: String,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO audit_log (id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id, detail_json)
                VALUES (?, ?, ?, '2026-11-14T08:00:00Z', ?, 'RUNTIME', 'daemon', 'dev-1', ?)
            """,
            parameters = 5,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindLong(2, sequence)
            bindString(3, kind)
            bindString(4, detailJson)
        }
    }

    private fun seedContentNode(id: String, projectId: String, sizeBytes: Long, state: String) {
        val now = "2026-11-14T08:00:00Z"
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO content_nodes (
                    id, project_id, kind, name, mutability_policy, sensitivity_level, egress_eligibility,
                    storage_location_json, content_hash, content_type, size_bytes, created_at,
                    created_by_kind, created_by_id, updated_at, state
                ) VALUES (?, ?, 'TEXT', ?, 'IMMUTABLE', 'PUBLIC', 'ALLOW', '{}', ?, 'text/plain', ?, ?, 'RUNTIME', 'daemon', ?, ?)
            """,
            parameters = 8,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindString(2, id)
            bindString(3, "hash-$id")
            bindLong(4, sizeBytes)
            bindString(5, now)
            bindString(6, now)
            bindString(7, state)
        }
    }

    private fun seedAttempt(
        id: String,
        taskId: String,
        attemptNumber: Long,
        provider: String,
        tokensIn: Long?,
        tokensOut: Long?,
        costUnits: Long?,
        auditRef: String,
    ) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO attempts (
                    id, task_id, attempt_number, started_at, ended_at, state,
                    model_provider, tokens_input, tokens_output, cost_units, audit_ref
                ) VALUES (?, ?, ?, '2026-11-14T08:00:00Z', '2026-11-14T08:00:01Z', 'COMPLETED', ?, ?, ?, ?, ?)
            """,
            parameters = 8,
        ) {
            bindString(0, id)
            bindString(1, taskId)
            bindLong(2, attemptNumber)
            bindString(3, provider)
            bindLong(4, tokensIn)
            bindLong(5, tokensOut)
            bindLong(6, costUnits)
            bindString(7, auditRef)
        }
    }

    private fun seedToolCall(callId: String, runId: String, modelTaskId: String, toolName: String, stepIndex: Long) {
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO tool_calls (call_id, run_id, model_task_id, tool_name, arguments_json, schema_valid, outcome, step_index)
                VALUES (?, ?, ?, ?, '{}', 1, 'SUCCESS', ?)
            """,
            parameters = 5,
        ) {
            bindString(0, callId)
            bindString(1, runId)
            bindString(2, modelTaskId)
            bindString(3, toolName)
            bindLong(4, stepIndex)
        }
    }
}
