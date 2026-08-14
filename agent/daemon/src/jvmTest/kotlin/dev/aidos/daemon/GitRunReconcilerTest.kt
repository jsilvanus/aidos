package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RunId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.eclipse.jgit.api.Git
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M13 (RFC-0053): [GitRunReconciler]'s real reconciliation logic against a real repository and a
 * real SQLite project database — fingerprint storage, classification, content-node
 * re-hashing/`DANGLING` marking, parked-Run termination, and the `reconciliations` audit row.
 * `SqliteExecutor`'s own contract with the [dev.aidos.executor.RunReconciler] seam is covered
 * separately in `executor`'s `RunReconcilerGateTest`, with a test double — this file is the real
 * implementation.
 */
class GitRunReconcilerTest {

    private var clock = 0
    private fun nowIso(): String = "2026-08-10T00:00:${(clock++).toString().padStart(2, '0')}Z"
    private fun nextId() = UuidV7Generator().next()
    private var eventSequence = 0
    private val testJson = Json { encodeDefaults = true }

    private fun openDriver() = run {
        val root = Files.createTempDirectory("git-run-reconciler-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso() }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun initRepo(): File {
        val dir = Files.createTempDirectory("git-run-reconciler-repo").toFile()
        Git.init().setDirectory(dir).call().use { git ->
            git.repository.config.apply {
                setString("user", null, "name", "Test")
                setString("user", null, "email", "test@test.com")
                setBoolean("commit", null, "gpgsign", false)
                save()
            }
            val readme = File(dir, "README.md")
            readme.writeText("# Test\n")
            git.add().addFilepattern("README.md").call()
            git.commit().setMessage("init").setAuthor("Test", "test@test.com").call()
        }
        return dir
    }

    private fun seedProject(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String, rootPath: String,
    ) {
        driver.execute(null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', ?, 'generic', ?, ?, ?)", 5
        ) { bindString(0, projectId); bindString(1, rootPath); bindString(2, nowIso()); bindString(3, nowIso()); bindString(4, nowIso()) }
    }

    private fun seedRun(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        runId: String, projectId: String, state: String,
    ) {
        val sid = nextId(); val eid = nextId()
        driver.execute(null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'test', 'DRIVER', 'RUNNING', ?, ?, ?)", 5
        ) { bindString(0, sid); bindString(1, projectId); bindString(2, nowIso()); bindString(3, nowIso()); bindString(4, nowIso()) }
        driver.execute(null,
            "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, " +
                "timestamp, source, payload, causal_depth) VALUES (?, ?, ?, 'UserMessage', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)", 4
        ) { bindString(0, eid); bindString(1, projectId); bindLong(2, (eventSequence++).toLong()); bindString(3, nowIso()) }
        driver.execute(null,
            "INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, state, " +
                "retry_policy_json, platform_profile, device_id) VALUES (?, ?, ?, ?, ?, ?, '{}', 'DESKTOP', 'device-1')", 6
        ) { bindString(0, runId); bindString(1, sid); bindString(2, projectId); bindString(3, eid); bindString(4, nowIso()); bindString(5, state) }
    }

    private fun runRow(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, runId: String): Pair<String, String?>? =
        driver.executeQuery(null, "SELECT state, error_code FROM runs WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0)!! to c.getString(1) else null) }, 1
        ) { bindString(0, runId) }.value

    private fun reconciliationRows(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, projectId: String): List<Triple<String, Int, Int>> =
        driver.executeQuery(null,
            "SELECT classification, nodes_invalidated, nodes_dangling FROM reconciliations WHERE project_id = ?",
            mapper = { c ->
                val out = mutableListOf<Triple<String, Int, Int>>()
                while (c.next().value) out.add(Triple(c.getString(0)!!, c.getLong(1)!!.toInt(), c.getLong(2)!!.toInt()))
                QueryResult.Value(out)
            }, 1
        ) { bindString(0, projectId) }.value

    private fun fingerprintRow(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, projectId: String): String? =
        driver.executeQuery(null, "SELECT head_commit FROM repo_fingerprints WHERE project_id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) }, 1
        ) { bindString(0, projectId) }.value

    @Test
    fun `first observation establishes the baseline and reconciles nothing`() = runBlocking {
        val driver = openDriver()
        val repo = initRepo()
        val pid = nextId()
        seedProject(driver, pid, repo.path)

        val reconciler = GitRunReconciler(idGen = { nextId() }, nowIso = { nowIso() })
        val terminated = reconciler.reconcileBeforeRun(driver, ProjectId(pid), RunId(nextId()))

        assertTrue(terminated.isEmpty())
        assertNotNull(fingerprintRow(driver, pid), "baseline fingerprint must be recorded")
        assertTrue(reconciliationRows(driver, pid).isEmpty(), "no mismatch on first observation -- nothing to reconcile")
    }

    @Test
    fun `no mutation between two checks reconciles nothing`() = runBlocking {
        val driver = openDriver()
        val repo = initRepo()
        val pid = nextId()
        seedProject(driver, pid, repo.path)
        val reconciler = GitRunReconciler(idGen = { nextId() }, nowIso = { nowIso() })

        reconciler.reconcileBeforeRun(driver, ProjectId(pid), RunId(nextId()))
        val terminated = reconciler.reconcileBeforeRun(driver, ProjectId(pid), RunId(nextId()))

        assertTrue(terminated.isEmpty())
        assertTrue(reconciliationRows(driver, pid).isEmpty())
    }

    @Test
    fun `a new commit made outside Aidos is recorded as HEAD_MOVED`() = runBlocking {
        val driver = openDriver()
        val repo = initRepo()
        val pid = nextId()
        seedProject(driver, pid, repo.path)
        val reconciler = GitRunReconciler(idGen = { nextId() }, nowIso = { nowIso() })
        reconciler.reconcileBeforeRun(driver, ProjectId(pid), RunId(nextId())) // baseline

        Git.open(repo).use { git ->
            File(repo, "second.txt").writeText("more\n")
            git.add().addFilepattern("second.txt").call()
            git.commit().setMessage("second").setAuthor("Test", "test@test.com").call()
        }
        val newHead = Git.open(repo).use { it.repository.resolve("HEAD").name }

        val terminated = reconciler.reconcileBeforeRun(driver, ProjectId(pid), RunId(nextId()))

        assertTrue(terminated.isEmpty(), "no parked Runs exist -- nothing to terminate")
        val rows = reconciliationRows(driver, pid)
        assertEquals(1, rows.size)
        assertEquals("HEAD_MOVED", rows[0].first)
        assertEquals(newHead, fingerprintRow(driver, pid), "fingerprint must be updated after reconciling")
    }

    @Test
    fun `a parked Run is terminated with FAILED(repo_mutated) when the repository mutates underneath it`() = runBlocking {
        val driver = openDriver()
        val repo = initRepo()
        val pid = nextId()
        seedProject(driver, pid, repo.path)
        val parkedRunId = nextId()
        seedRun(driver, parkedRunId, pid, "INTERRUPTED")

        val reconciler = GitRunReconciler(idGen = { nextId() }, nowIso = { nowIso() })
        reconciler.reconcileBeforeRun(driver, ProjectId(pid), RunId(nextId())) // baseline

        Git.open(repo).use { git ->
            File(repo, "second.txt").writeText("more\n")
            git.add().addFilepattern("second.txt").call()
            git.commit().setMessage("second").setAuthor("Test", "test@test.com").call()
        }

        val triggeringRunId = nextId()
        seedRun(driver, triggeringRunId, pid, "PENDING")
        val terminated = reconciler.reconcileBeforeRun(driver, ProjectId(pid), RunId(triggeringRunId))

        assertEquals(setOf(RunId(parkedRunId)), terminated, "the parked Run must be terminated, not the fresh PENDING one that triggered the check")
        val (state, errorCode) = runRow(driver, parkedRunId)!!
        assertEquals("FAILED", state)
        assertEquals("run.repo_mutated", errorCode)
        assertEquals(1, reconciliationRows(driver, pid).size, "one reconciliation row for the mismatch")
    }

    @Test
    fun `an IMMUTABLE git-tracked content node dangles when its file changes outside Aidos`() = runBlocking {
        val driver = openDriver()
        val repo = initRepo()
        val pid = nextId()
        seedProject(driver, pid, repo.path)

        val originalBytes = File(repo, "README.md").readBytes()
        val originalHash = java.security.MessageDigest.getInstance("SHA-256").digest(originalBytes)
            .joinToString("") { "%02x".format(it) }
        val nodeId = nextId()
        driver.execute(null,
            "INSERT INTO content_nodes (id, project_id, kind, name, mutability_policy, sensitivity_level, " +
                "egress_eligibility, storage_location_json, content_hash, content_type, size_bytes, created_at, " +
                "created_by_kind, created_by_id, updated_at, content_version, state, tags) VALUES " +
                "(?, ?, 'CODE_FILE', 'README.md', 'IMMUTABLE', 'INTERNAL', 'ALLOWED', ?, ?, 'text/markdown', ?, ?, " +
                "'RUNTIME', 'test', ?, 1, 'ACTIVE', '[]')", 10
        ) {
            bindString(0, nodeId)
            bindString(1, pid)
            bindString(2, testJson.encodeToString(
                dev.aidos.kernel.StorageLocation.serializer(),
                dev.aidos.kernel.StorageLocation.FilesystemPath(relativePath = "README.md", gitTracked = true),
            ))
            bindString(3, originalHash)
            bindLong(4, originalBytes.size.toLong())
            bindString(5, nowIso())
            bindString(6, nowIso())
        }

        val reconciler = GitRunReconciler(idGen = { nextId() }, nowIso = { nowIso() })
        reconciler.reconcileBeforeRun(driver, ProjectId(pid), RunId(nextId())) // baseline

        File(repo, "README.md").writeText("# Test\nedited outside Aidos\n")

        reconciler.reconcileBeforeRun(driver, ProjectId(pid), RunId(nextId()))

        val (state, _) = driver.executeQuery(null, "SELECT state, content_hash FROM content_nodes WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0)!! to c.getString(1) else null) }, 1
        ) { bindString(0, nodeId) }.value!!
        assertEquals("DANGLING", state)
        val rows = reconciliationRows(driver, pid)
        assertEquals(1, rows.size)
        assertEquals(1, rows[0].third, "nodes_dangling must count the README node")
    }
}
