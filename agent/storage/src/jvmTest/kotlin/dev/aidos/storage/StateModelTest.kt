package dev.aidos.storage

import dev.aidos.kernel.ContentNodeState
import dev.aidos.kernel.SessionState
import java.nio.file.Files
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * RFC-0017 State Model tests:
 * - pending_operations lifecycle (record, list, incrementRetry, complete)
 * - row_version optimistic concurrency (CAS)
 * - open-time recovery pass (idempotent, resolves COMPLETE / retries / marks UNRECOVERABLE)
 * - lifecycle state transitions (ProjectState, SessionState, ContentNodeState)
 */
class StateModelTest {

    private val nowIso = "2026-11-14T00:00:00Z"

    private fun openProjectDb(): Pair<String, OpenDatabase> {
        val dir = Files.createTempDirectory("state-model-test").toFile()
        val dbPath = DesktopPaths.stateDb(dir.path)
        return dbPath to AidosStorage.openProject(dbPath, "1.0") { nowIso }
    }

    private fun seedProject(driver: app.cash.sqldelight.db.SqlDriver, projectId: String, state: String = "OPEN") {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO projects (id, name, root_path, project_type, state, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?, ?)",
            parameters = 5,
        ) {
            bindString(0, projectId)
            bindString(1, state)
            bindString(2, nowIso)
            bindString(3, nowIso)
            bindString(4, nowIso)
        }
    }

    private fun seedContentNode(
        driver: app.cash.sqldelight.db.SqlDriver,
        nodeId: String,
        projectId: String,
        state: String = "CREATING",
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO content_nodes (id, project_id, kind, name, mutability_policy, sensitivity_level, " +
                "egress_eligibility, trust_level, storage_location_json, content_hash, content_type, size_bytes, " +
                "created_at, created_by_kind, created_by_id, updated_at, state) " +
                "VALUES (?, ?, 'REPORT', 'test.md', 'IMMUTABLE', 'INTERNAL', 'ALLOWED', 'TRUSTED', '{}', " +
                "'abc', 'text/markdown', 10, ?, 'SESSION', 's1', ?, ?)",
            parameters = 5,
        ) {
            bindString(0, nodeId)
            bindString(1, projectId)
            bindString(2, nowIso)
            bindString(3, nowIso)
            bindString(4, state)
        }
    }

    @Test
    fun `pending_operations record, retry, and complete round-trip`() {
        val (_, db) = openProjectDb()
        val store = PendingOperationsStore(db.driver)

        store.record("op-1", "resource", "node-1", "git_commit", nowIso, """{"path":"a.md"}""")
        assertEquals(1, store.listAll().size)
        assertEquals(1, store.forObject("resource", "node-1").size)
        assertEquals(0, store.listAll().single().retryCount)

        assertEquals(1, store.incrementRetry("op-1"))
        assertEquals(1, store.listAll().single().retryCount)

        store.complete("op-1")
        assertTrue(store.listAll().isEmpty())
    }

    @Test
    fun `row_version CAS succeeds on matching version and rejects stale writer`() {
        val (_, db) = openProjectDb()
        seedProject(db.driver, "p1")

        val v1 = RowVersionCas.readRowVersion(db.driver, "projects", "p1")
        assertEquals(1L, v1)

        val firstWon = RowVersionCas.updateWithCas(
            driver = db.driver,
            table = "projects",
            id = "p1",
            expectedRowVersion = 1L,
            setClause = "name = ?",
            setParameterCount = 1,
        ) { bindString(0, "updated-1") }
        assertTrue(firstWon)
        assertEquals(2L, RowVersionCas.readRowVersion(db.driver, "projects", "p1"))

        val staleLost = RowVersionCas.updateWithCas(
            driver = db.driver,
            table = "projects",
            id = "p1",
            expectedRowVersion = 1L,
            setClause = "name = ?",
            setParameterCount = 1,
        ) { bindString(0, "stale-write") }
        assertFalse(staleLost)

        assertFailsWith<OptimisticConcurrencyException> {
            RowVersionCas.withRetry(maxAttempts = 2) { false }
        }
    }

    @Test
    fun `open-time recovery pass resolves complete pending operation and is idempotent`() {
        val (dbPath, db) = openProjectDb()
        seedProject(db.driver, "p1")
        seedContentNode(db.driver, "cn-1", "p1", state = "CREATING")
        PendingOperationsStore(db.driver).record("op-1", "artifact", "cn-1", "filesystem_write", nowIso)

        // Simulate reopening the project with a verifier that confirms the file exists.
        val report1 = StateModelRecovery.recoverOnOpen(
            driver = db.driver,
            nowIso = { nowIso },
            idGen = { UUID.randomUUID().toString() },
            verifier = { PendingOperationOutcome.COMPLETE },
        )
        assertEquals(1, report1.pendingExamined)
        assertEquals(1, report1.resolved)
        assertTrue(PendingOperationsStore(db.driver).listAll().isEmpty())

        val stateAfter = db.driver.executeQuery(
            identifier = null,
            sql = "SELECT state, row_version FROM content_nodes WHERE id = 'cn-1'",
            mapper = { c ->
                c.next()
                app.cash.sqldelight.db.QueryResult.Value(c.getString(0)!! to c.getLong(1)!!)
            },
            parameters = 0,
        ).value
        assertEquals("ACTIVE", stateAfter.first)
        assertEquals(2L, stateAfter.second)

        // Running recovery a second time (and reopening via AidosStorage.openProject) is idempotent.
        val report2 = StateModelRecovery.recoverOnOpen(db.driver, { nowIso }, { UUID.randomUUID().toString() })
        assertEquals(0, report2.pendingExamined)
        db.driver.close()

        val reopened = AidosStorage.openProject(dbPath, "1.0") { nowIso }
        assertTrue(reopened.result is OpenResult.Ready)
        reopened.driver.close()
    }

    @Test
    fun `open-time recovery marks unrecoverable pending operations DANGLING and audits`() {
        val (_, db) = openProjectDb()
        seedProject(db.driver, "p1")
        seedContentNode(db.driver, "cn-2", "p1", state = "CREATING")
        PendingOperationsStore(db.driver).record("op-2", "resource", "cn-2", "git_commit", nowIso)

        val report = StateModelRecovery.recoverOnOpen(
            driver = db.driver,
            nowIso = { nowIso },
            idGen = { UUID.randomUUID().toString() },
            verifier = { PendingOperationOutcome.UNRECOVERABLE },
        )
        assertEquals(1, report.unrecoverable)
        assertTrue(PendingOperationsStore(db.driver).listAll().isEmpty())

        val nodeState = db.driver.executeQuery(
            identifier = null,
            sql = "SELECT state FROM content_nodes WHERE id = 'cn-2'",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 0,
        ).value
        assertEquals("DANGLING", nodeState)

        val auditCount = db.driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM audit_log WHERE kind = 'StateRecoveryFailed'",
            mapper = { c -> app.cash.sqldelight.db.QueryResult.Value(if (c.next().value) c.getLong(0) else 0L) },
            parameters = 0,
        ).value
        assertEquals(1L, auditCount)
    }

    @Test
    fun `lifecycle state transition tables enforce valid transitions`() {
        assertTrue(ProjectState.CREATING.canTransitionTo(ProjectState.OPEN))
        assertTrue(ProjectState.OPEN.canTransitionTo(ProjectState.CLOSING))
        assertTrue(ProjectState.CLOSED.canTransitionTo(ProjectState.OPEN))
        assertFalse(ProjectState.DELETED.canTransitionTo(ProjectState.OPEN))

        assertTrue(LifecycleTransitions.isValidSessionTransition(SessionState.CREATED, SessionState.SLEEPING))
        assertTrue(LifecycleTransitions.isValidSessionTransition(SessionState.SLEEPING, SessionState.RUNNING))
        assertFalse(LifecycleTransitions.isValidSessionTransition(SessionState.ARCHIVED, SessionState.RUNNING))

        assertTrue(LifecycleTransitions.isValidContentNodeTransition(ContentNodeState.CREATING, ContentNodeState.ACTIVE))
        assertTrue(LifecycleTransitions.isValidContentNodeTransition(ContentNodeState.ACTIVE, ContentNodeState.DANGLING))
        assertFalse(LifecycleTransitions.isValidContentNodeTransition(ContentNodeState.DELETED, ContentNodeState.ACTIVE))
    }
}
