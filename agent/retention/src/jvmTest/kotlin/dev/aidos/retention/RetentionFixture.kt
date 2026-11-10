package dev.aidos.retention

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.time.temporal.ChronoUnit

/** A real project database (the canonical `schema/project.sql`) plus helpers to seed history. */
class RetentionFixture {
    val root: File = Files.createTempDirectory("retention-test").toFile()
    val driver: JdbcSqliteDriver = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { NOW.toString() }.driver as JdbcSqliteDriver
    val blobDir = File(root, ".aidos/blobs")
    val blobs = BlobStore(driver, blobDir, { NOW.toString() })
    val projectId = "p1"
    private var seq = 0
    private var ids = 0
    fun id(prefix: String = "id") = "$prefix-%05d".format(++ids)

    init {
        exec("INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) VALUES (?, 'test', ?, 'generic', ?, ?, ?)",
            projectId, root.path, NOW.toString(), NOW.toString(), NOW.toString())
        exec("INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) VALUES ('s1', ?, 'test', 'DRIVER', 'RUNNING', ?, ?, ?)",
            projectId, NOW.toString(), NOW.toString(), NOW.toString())
    }

    fun exec(sql: String, vararg args: Any?) {
        driver.execute(null, sql, args.size) {
            args.forEachIndexed { i, a ->
                when (a) {
                    null -> bindString(i, null)
                    is Long -> bindLong(i, a)
                    is Int -> bindLong(i, a.toLong())
                    else -> bindString(i, a.toString())
                }
            }
        }
    }

    fun <T> query(sql: String, vararg args: Any?, read: (app.cash.sqldelight.db.SqlCursor) -> T): List<T> =
        driver.executeQuery(null, sql, { c ->
            val out = ArrayList<T>()
            while (c.next().value) out.add(read(c))
            QueryResult.Value(out)
        }, args.size) { args.forEachIndexed { i, a -> bindString(i, a.toString()) } }.value

    fun count(table: String, where: String = "1=1"): Long =
        query("SELECT COUNT(*) FROM $table WHERE $where") { it.getLong(0)!! }.single()

    fun daysAgo(days: Int): String = NOW.minus(days.toLong(), ChronoUnit.DAYS).toString()

    /** An event [daysOld] days old with [payload]. Returns its id. */
    fun event(daysOld: Int, payload: String): String {
        val id = id("evt")
        exec("INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, timestamp, source, topic, payload, causal_depth) " +
            "VALUES (?, ?, ?, 'ToolResult', 1, 'FACT', 'SESSION', ?, 'session:s1', 'topic-x', ?, 0)",
            id, projectId, ++seq, daysAgo(daysOld), payload)
        return id
    }

    /** A Run (default COMPLETED) with one MODEL_CALL task; returns (runId, taskId). */
    fun run(state: String = "COMPLETED", triggerEventId: String? = null, daysOld: Int = 100): Pair<String, String> {
        val trigger = triggerEventId ?: event(daysOld, "{}")
        val runId = id("run"); val taskId = id("task")
        exec("INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, ended_at, state, retry_policy_json, platform_profile, device_id) " +
            "VALUES (?, 's1', ?, ?, ?, ?, ?, '{}', 'DESKTOP', 'dev-1')", runId, projectId, trigger, daysAgo(daysOld), daysAgo(daysOld), state)
        exec("INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json) " +
            "VALUES (?, ?, 's1', ?, 1, 'MODEL_CALL', 'step', 'COMPLETED', '{}')", taskId, runId, projectId)
        return runId to taskId
    }

    /** An Attempt of [taskId], [daysOld] days old. Returns its id. */
    fun attempt(
        taskId: String, daysOld: Int, output: String? = null, input: String? = null, prompt: String? = null,
        number: Int = 1, state: String = "COMPLETED",
    ): String {
        val audit = id("aud")
        exec("INSERT INTO audit_log (id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id) VALUES (?, ?, ?, ?, 'ModelCallCompleted', 'SESSION', 's1', 'dev-1')",
            audit, projectId, ++seq, daysAgo(daysOld))
        val id = id("att")
        exec("INSERT INTO attempts (id, task_id, attempt_number, started_at, ended_at, state, input_snapshot, output_snapshot, prompt_package_json, recovery_class, audit_ref) " +
            "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'PURE', ?)", id, taskId, number, daysAgo(daysOld), daysAgo(daysOld), state, input, output, prompt, audit)
        return id
    }

    fun column(table: String, col: String, id: String): String? =
        query("SELECT $col FROM $table WHERE id = ?", id) { it.getString(0) }.single()

    fun compactor() = Compactor(driver, blobs, { id("cmp") })

    companion object {
        val NOW: Instant = Instant.parse("2026-11-10T00:00:00Z")
        fun big(chars: Int, marker: String = "x"): String = buildString {
            append("HEAD-").append(marker).append('\n')
            while (length < chars - 20) append("some log line ").append(length).append('\n')
            append("-TAIL-").append(marker)
        }
    }
}
