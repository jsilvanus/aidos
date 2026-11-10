package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.api.RunResult
import dev.aidos.api.UserMessage
import dev.aidos.executor.RunCreator
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.EventId
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SessionId
import dev.aidos.retention.MaintenanceReport
import dev.aidos.retention.RetentionPolicyResolver
import dev.aidos.settings.SettingsStore
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RFC-0056 wired into the running daemon: compaction is scheduled by `drive()` and reads its
 * windows from `aidos.toml` through the settings layer; Stage 3 refuses to start a Run.
 */
class RetentionWiringTest {

    private val nowIso = Instant.now().toString()
    private fun nextId() = UuidV7Generator().next()
    private var seq = 100

    private fun openDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("retention-wiring").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver as JdbcSqliteDriver
    }

    private fun openUserDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("retention-wiring-user").toFile()
        return AidosStorage.openUser(DesktopPaths.userDb(root.path), "test-1.0") { nowIso }.driver as JdbcSqliteDriver
    }

    private fun exec(d: JdbcSqliteDriver, sql: String, vararg args: String?) {
        d.execute(null, sql, args.size) { args.forEachIndexed { i, a -> bindString(i, a) } }
    }

    private fun seedProject(d: JdbcSqliteDriver, pid: String, sid: String, root: String) {
        exec(d, "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) VALUES (?, 'test', ?, 'generic', ?, ?, ?)", pid, root, nowIso, nowIso, nowIso)
        exec(d, "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) VALUES (?, ?, 'test', 'DRIVER', 'RUNNING', ?, ?, ?)", sid, pid, nowIso, nowIso, nowIso)
    }

    private fun newRun(d: JdbcSqliteDriver, pid: String, sid: String) = run {
        val eid = nextId()
        exec(d, "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, source, payload, causal_depth) VALUES (?, ?, 0, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", eid, pid, nowIso)
        RunCreator(d, idGen = { nextId() }, nowIso = { nowIso }).createForUserMessage(
            sessionId = SessionId(sid), projectId = ProjectId(pid), triggerEventId = EventId(eid),
            userMessageSummary = "hello", platformProfile = PlatformProfile.DESKTOP,
            deviceId = "dev-1", networkAvailable = false,
        )
    }

    /** A finished Run with an Attempt [daysOld] days old holding a 20KB output. Returns the attempt id. */
    private fun seedOldAttempt(d: JdbcSqliteDriver, pid: String, daysOld: Long): String {
        val ts = Instant.now().minus(daysOld, ChronoUnit.DAYS).toString()
        val evt = nextId(); val run = nextId(); val task = nextId(); val audit = nextId(); val att = nextId()
        exec(d, "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, source, payload, causal_depth) VALUES (?, ?, ${++seq}, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", evt, pid, ts)
        exec(d, "INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, ended_at, state, retry_policy_json, platform_profile, device_id) VALUES (?, (SELECT id FROM sessions LIMIT 1), ?, ?, ?, ?, 'COMPLETED', '{}', 'DESKTOP', 'dev-1')", run, pid, evt, ts, ts)
        exec(d, "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json) VALUES (?, ?, (SELECT id FROM sessions LIMIT 1), ?, 1, 'MODEL_CALL', 'old step', 'COMPLETED', '{}')", task, run, pid)
        exec(d, "INSERT INTO audit_log (id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id) VALUES (?, ?, ${++seq}, ?, 'ModelCallCompleted', 'SESSION', 's', 'dev-1')", audit, pid, ts)
        exec(d, "INSERT INTO attempts (id, task_id, attempt_number, started_at, ended_at, state, output_snapshot, recovery_class, audit_ref) VALUES (?, ?, 1, ?, ?, 'COMPLETED', ?, 'PURE', ?)", att, task, ts, ts, "line\n".repeat(4000), audit)
        return att
    }

    private fun outputOf(d: JdbcSqliteDriver, id: String): String =
        d.executeQuery(null, "SELECT output_snapshot FROM attempts WHERE id = ?", { c ->
            QueryResult.Value(if (c.next().value) c.getString(0)!! else "")
        }, 1) { bindString(0, id) }.value

    @Test
    fun `drive schedules compaction, and aidos toml's retention aged_days is what decides what is old`() = runTest {
        val d = openDriver(); val u = openUserDriver()
        val root = Files.createTempDirectory("retention-wiring-root").toFile()
        // 12 days: inside the 30-day default, outside the project's own window.
        java.io.File(root, "aidos.toml").writeText("[retention]\naged_days = 12\n")
        val pid = nextId(); val sid = nextId()
        seedProject(d, pid, sid, root.path)
        val twenty = seedOldAttempt(d, pid, 20)
        val five = seedOldAttempt(d, pid, 5)
        val runId = newRun(d, pid, sid)

        val report = CompletableFuture<MaintenanceReport>()
        val maintenance = RetentionMaintenance(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onReport = { _, r -> report.complete(r) },
        )
        RuntimeCompositionRoot(anthropicApiKey = { null }, userDriver = u, retention = maintenance).drive(
            projectDriver = d, runId = runId, projectId = ProjectId(pid), sessionId = sid,
            deviceId = "dev-1", platformProfile = PlatformProfile.DESKTOP, networkAvailable = false,
            idGen = { nextId() }, nowIso = { nowIso },
        )

        val r = report.get(30, TimeUnit.SECONDS)
        assertEquals(1, r.compacted)
        assertTrue(outputOf(d, twenty).startsWith("{\"compacted_at\""), "20 days old is past the project's 12-day window")
        assertTrue(!outputOf(d, five).startsWith("{\"compacted_at\""), "5 days old is not")
        assertEquals(12, RetentionPolicyResolver(SettingsStore(u, d), d).resolve(pid).agedDays)
    }

    @Test
    fun `a second drive inside the interval does not start another pass`() = runTest {
        val d = openDriver()
        val root = Files.createTempDirectory("retention-wiring-root").toFile()
        val pid = nextId(); val sid = nextId()
        seedProject(d, pid, sid, root.path)
        val passes = java.util.concurrent.atomic.AtomicInteger()
        val first = CompletableFuture<Unit>()
        val maintenance = RetentionMaintenance(
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
            onReport = { _, _ -> passes.incrementAndGet(); first.complete(Unit) },
        )
        val job1 = maintenance.schedule(d, pid, root.path, null, { nextId() }, { nowIso })
        first.get(30, TimeUnit.SECONDS)
        val job2 = maintenance.schedule(d, pid, root.path, null, { nextId() }, { nowIso })

        assertTrue(job1 != null)
        assertEquals(null, job2)
        assertEquals(1, passes.get())
    }

    @Test
    fun `Stage 3 refuses to start a Run, tells the user, and creates nothing`() = runTest {
        val d = openDriver()
        val root = Files.createTempDirectory("retention-wiring-root").toFile()
        val pid = nextId(); val sid = nextId()
        seedProject(d, pid, sid, root.path)
        val executor = SqliteRunExecutor(
            nowIso = { nowIso },
            admission = StoragePressureAdmission(freeBytesOf = { { 100L * 1024 * 1024 } }),
        )

        val result = executor.send(d, pid, sid, UserMessage(content = "hi"), PlatformProfile.DESKTOP, "dev-1", false)

        val err = assertIs<RunResult.Error>(result)
        assertEquals("run.storage_critical", err.code)
        assertTrue(err.message.contains("100 MB free"))
        assertEquals(0L, count(d, "runs"))
        assertEquals(0L, count(d, "events"))
    }

    @Test
    fun `with enough free space a Run is admitted`() = runTest {
        val d = openDriver()
        val root = Files.createTempDirectory("retention-wiring-root").toFile()
        val pid = nextId(); val sid = nextId()
        seedProject(d, pid, sid, root.path)
        val executor = SqliteRunExecutor(
            nowIso = { nowIso },
            admission = StoragePressureAdmission(freeBytesOf = { { 10L * 1024 * 1024 * 1024 } }),
        )

        assertIs<RunResult.Accepted>(executor.send(d, pid, sid, UserMessage(content = "hi"), PlatformProfile.DESKTOP, "dev-1", false))
        assertEquals(1L, count(d, "runs"))
    }

    private fun count(d: JdbcSqliteDriver, table: String): Long =
        d.executeQuery(null, "SELECT COUNT(*) FROM $table", { c -> QueryResult.Value(if (c.next().value) c.getLong(0)!! else 0L) }, 0).value
}
