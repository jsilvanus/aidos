package dev.aidos.intent

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

class DerivedStatusComputerTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val tempDirs = mutableListOf<File>()
    private var idSeq = 0

    private val nowIso = "2026-08-05T00:00:00Z"

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        drivers.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun nextId(prefix: String): String = "$prefix-${idSeq++}"

    private fun setupDb(): Pair<JdbcSqliteDriver, DerivedStatusComputer> {
        val root = Files.createTempDirectory("intent-derived-status-test").toFile()
        tempDirs.add(root)
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as JdbcSqliteDriver
        drivers.add(driver)
        assertForeignKeysEnabled(driver)
        seedProjectAndSession(driver, projectId = "proj1", sessionId = "sess1")
        return driver to DerivedStatusComputer(driver)
    }

    private fun assertForeignKeysEnabled(driver: JdbcSqliteDriver) {
        var fkEnabled = 0L
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA foreign_keys",
            parameters = 0,
            mapper = { cursor ->
                if (cursor.next().value) {
                    fkEnabled = cursor.getLong(0) ?: 0L
                }
                QueryResult.Value(Unit)
            },
        )
        assertEquals(1L, fkEnabled, "PRAGMA foreign_keys must be enabled")
    }

    private fun seedProjectAndSession(driver: JdbcSqliteDriver, projectId: String, sessionId: String) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO projects (id, name, root_path, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'Test Project', '/tmp/test', ?, ?, ?)",
            parameters = 4,
        ) {
            bindString(0, projectId)
            bindString(1, nowIso)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'Driver', 'DRIVER', 'RUNNING', ?, ?, ?)",
            parameters = 5,
        ) {
            bindString(0, sessionId)
            bindString(1, projectId)
            bindString(2, nowIso)
            bindString(3, nowIso)
            bindString(4, nowIso)
        }
    }

    private fun insertNode(
        driver: JdbcSqliteDriver,
        id: String,
        projectId: String = "proj1",
        type: String = "GOAL",
        parentId: String? = null,
        assertedStatus: String? = null,
        verificationMet: Long? = null,
        verifiedByKind: String? = null,
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_nodes " +
                "(id, project_id, type, title, parent_id, asserted_status, verification_met, verified_by_kind, " +
                "created_at, created_by_kind, created_by_id, modified_at, modified_by_kind, modified_by_id, tags) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 'USER', 'user1', ?, 'USER', 'user1', '[]')",
            parameters = 10,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindString(2, type)
            bindString(3, "Node $id")
            bindString(4, parentId)
            bindString(5, assertedStatus)
            bindLong(6, verificationMet)
            bindString(7, verifiedByKind)
            bindString(8, nowIso)
            bindString(9, nowIso)
        }
    }

    private fun insertRun(
        driver: JdbcSqliteDriver,
        id: String,
        state: String,
        startedAt: String = nowIso,
        projectId: String = "proj1",
        sessionId: String = "sess1",
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO runs " +
                "(id, session_id, project_id, trigger_event_id, started_at, state, " +
                "retry_policy_json, platform_profile, device_id) " +
                "VALUES (?, ?, ?, 'evt-1', ?, ?, '{}', 'DESKTOP', 'dev-1')",
            parameters = 5,
        ) {
            bindString(0, id)
            bindString(1, sessionId)
            bindString(2, projectId)
            bindString(3, startedAt)
            bindString(4, state)
        }
    }

    private fun insertRunIntentEdge(
        driver: JdbcSqliteDriver,
        runId: String,
        intentNodeId: String,
        edgeKind: String,
        confirmed: Long = 0L,
        confirmedByKind: String? = null,
        projectId: String = "proj1",
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO execution_edges " +
                "(id, project_id, from_node_id, from_node_kind, to_node_id, to_node_kind, " +
                "edge_kind, confirmed, confirmed_by_kind, confirmed_by_id, confirmed_at) " +
                "VALUES (?, ?, ?, 'RUN', ?, 'INTENT_NODE', ?, ?, ?, ?, ?)",
            parameters = 9,
        ) {
            bindString(0, nextId("ee"))
            bindString(1, projectId)
            bindString(2, runId)
            bindString(3, intentNodeId)
            bindString(4, edgeKind)
            bindLong(5, confirmed)
            bindString(6, confirmedByKind)
            bindString(7, if (confirmedByKind != null) "verifier-1" else null)
            bindString(8, if (confirmed == 1L) nowIso else null)
        }
    }

    private fun insertIntentEdge(
        driver: JdbcSqliteDriver,
        fromNodeId: String,
        toNodeId: String,
        edgeKind: String = "DEPENDS_ON",
        projectId: String = "proj1",
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_edges (id, project_id, from_node_id, to_node_id, edge_kind, created_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            parameters = 6,
        ) {
            bindString(0, nextId("ie"))
            bindString(1, projectId)
            bindString(2, fromNodeId)
            bindString(3, toNodeId)
            bindString(4, edgeKind)
            bindString(5, nowIso)
        }
    }

    @Test
    fun testNotStartedStatusWithRealSchema() {
        val (driver, computer) = setupDb()
        insertNode(driver, "goal1")
        assertEquals("NOT_STARTED", computer.derive("goal1", "proj1"))
    }

    @Test
    fun testNonTerminalTargetedRunStatesDeriveInProgress() {
        val activeStates = listOf("PENDING", "RUNNING", "YIELDED", "INTERRUPTED")
        for ((index, state) in activeStates.withIndex()) {
            val (driver, computer) = setupDb()
            val nodeId = "goal-$index"
            val runId = "run-$index"
            insertNode(driver, nodeId)
            insertRun(driver, runId, state)
            insertRunIntentEdge(driver, runId, nodeId, "TARGETED")

            assertEquals("IN_PROGRESS", computer.derive(nodeId, "proj1"), "Expected $state to derive IN_PROGRESS")
        }
    }

    @Test
    fun testCompletedTargetedRunWithoutImplementsDerivesNotStarted() {
        val (driver, computer) = setupDb()
        insertNode(driver, "goal1")
        insertRun(driver, "run1", "COMPLETED")
        insertRunIntentEdge(driver, "run1", "goal1", "TARGETED")

        assertEquals("NOT_STARTED", computer.derive("goal1", "proj1"))
    }

    @Test
    fun testLatestFailedTargetedRunDerivesBlockedUntilActiveOrImplemented() {
        val (driver, computer) = setupDb()
        insertNode(driver, "goal1")
        insertRun(driver, "run-old", "COMPLETED", startedAt = "2026-08-05T00:00:00Z")
        insertRunIntentEdge(driver, "run-old", "goal1", "TARGETED")
        insertRun(driver, "run-fail", "FAILED", startedAt = "2026-08-05T01:00:00Z")
        insertRunIntentEdge(driver, "run-fail", "goal1", "TARGETED")

        assertEquals("BLOCKED", computer.derive("goal1", "proj1"))

        // Subsequent active run puts the node back IN_PROGRESS.
        insertRun(driver, "run-retry", "RUNNING", startedAt = "2026-08-05T02:00:00Z")
        insertRunIntentEdge(driver, "run-retry", "goal1", "TARGETED")
        assertEquals("IN_PROGRESS", computer.derive("goal1", "proj1"))
    }

    @Test
    fun testImplementsConfirmationRequiresUserOrAcceptanceCriteria() {
        val (driver, computer) = setupDb()
        insertNode(driver, "g-unconfirmed")
        insertRun(driver, "run1", "COMPLETED")
        insertRunIntentEdge(driver, "run1", "g-unconfirmed", "IMPLEMENTS", confirmed = 0L)
        assertEquals("NEEDS_REVIEW", computer.derive("g-unconfirmed", "proj1"))

        // Confirmed=1 by SESSION does not satisfy confirmation gate.
        insertNode(driver, "g-session")
        insertRun(driver, "run2", "COMPLETED")
        insertRunIntentEdge(
            driver,
            runId = "run2",
            intentNodeId = "g-session",
            edgeKind = "IMPLEMENTS",
            confirmed = 1L,
            confirmedByKind = "SESSION",
        )
        assertEquals("NEEDS_REVIEW", computer.derive("g-session", "proj1"))

        insertNode(driver, "g-user")
        insertRun(driver, "run3", "COMPLETED")
        insertRunIntentEdge(
            driver,
            runId = "run3",
            intentNodeId = "g-user",
            edgeKind = "IMPLEMENTS",
            confirmed = 1L,
            confirmedByKind = "USER",
        )
        assertEquals("DONE", computer.derive("g-user", "proj1"))

        insertNode(driver, "g-criteria")
        insertRun(driver, "run4", "COMPLETED")
        insertRunIntentEdge(
            driver,
            runId = "run4",
            intentNodeId = "g-criteria",
            edgeKind = "IMPLEMENTS",
            confirmed = 1L,
            confirmedByKind = "ACCEPTANCE_CRITERIA",
        )
        assertEquals("DONE", computer.derive("g-criteria", "proj1"))
    }

    @Test
    fun testAcceptanceCriteriaViaEdgesAndParentHierarchyGateDone() {
        val (driver, computer) = setupDb()
        insertNode(driver, "goal1")
        insertRun(driver, "run1", "COMPLETED")
        insertRunIntentEdge(driver, "run1", "goal1", "IMPLEMENTS", confirmed = 1L, confirmedByKind = "USER")

        // Unmet child criterion keeps parent goal in NEEDS_REVIEW.
        insertNode(driver, "ac-child", type = "ACCEPTANCE_CRITERION", parentId = "goal1", verificationMet = 0L)
        assertEquals("NOT_STARTED", computer.derive("ac-child", "proj1"))
        assertEquals("NEEDS_REVIEW", computer.derive("goal1", "proj1"))

        // Verify child criterion by CHECK, then add an unmet ACCEPTS-linked criterion.
        driver.execute(
            identifier = null,
            sql = "UPDATE intent_nodes SET verification_met = 1, verified_by_kind = 'CHECK' WHERE id = 'ac-child'",
            parameters = 0,
        ) {}
        assertEquals("DONE", computer.derive("ac-child", "proj1"))
        assertEquals("DONE", computer.derive("goal1", "proj1"))

        insertNode(driver, "ac-edge", type = "ACCEPTANCE_CRITERION", verificationMet = null, verifiedByKind = null)
        insertIntentEdge(driver, fromNodeId = "goal1", toNodeId = "ac-edge", edgeKind = "ACCEPTS")
        assertEquals("NEEDS_REVIEW", computer.derive("goal1", "proj1"))

        driver.execute(
            identifier = null,
            sql = "UPDATE intent_nodes SET verification_met = 1, verified_by_kind = 'USER' WHERE id = 'ac-edge'",
            parameters = 0,
        ) {}
        assertEquals("DONE", computer.derive("goal1", "proj1"))
    }

    @Test
    fun testAssertedStatusOverridesDerivedStatus() {
        val (driver, computer) = setupDb()
        insertNode(driver, "g-done", assertedStatus = "DONE")
        insertNode(driver, "g-dep")
        insertIntentEdge(driver, "g-done", "g-dep", "DEPENDS_ON")
        assertEquals("DONE", computer.derive("g-done", "proj1"))

        insertNode(driver, "g-abandoned", assertedStatus = "ABANDONED")
        insertRun(driver, "run1", "RUNNING")
        insertRunIntentEdge(driver, "run1", "g-abandoned", "TARGETED")
        assertEquals("ABANDONED", computer.derive("g-abandoned", "proj1"))
    }

    @Test
    fun testParentInheritanceWithoutSiblingPollution() {
        val (driver, computer) = setupDb()
        insertNode(driver, "root", type = "GOAL")
        insertNode(driver, "sub-blocked", type = "SUB_GOAL", parentId = "root")
        insertNode(driver, "sub-done", type = "SUB_GOAL", parentId = "root")
        insertNode(driver, "external-blocker", type = "GOAL")

        // sub-blocked depends on unmet external-blocker; sub-done is confirmed DONE.
        insertIntentEdge(driver, "sub-blocked", "external-blocker", "DEPENDS_ON")
        insertRun(driver, "run1", "COMPLETED")
        insertRunIntentEdge(driver, "run1", "sub-done", "IMPLEMENTS", confirmed = 1L, confirmedByKind = "USER")

        // Rolling up root must mark root BLOCKED without polluting unrelated sibling sub-done.
        assertEquals("BLOCKED", computer.derive("sub-blocked", "proj1"))
        assertEquals("BLOCKED", computer.derive("root", "proj1"))
        assertEquals("DONE", computer.derive("sub-done", "proj1"))

        // Parent asserted ABANDONED propagates downward to children.
        insertNode(driver, "abandoned-parent", type = "GOAL", assertedStatus = "ABANDONED")
        insertNode(driver, "abandoned-child", type = "SUB_GOAL", parentId = "abandoned-parent")
        assertEquals("ABANDONED", computer.derive("abandoned-child", "proj1"))
    }

    @Test
    fun testParentChildRollupProgressStates() {
        val (driver, computer) = setupDb()
        insertNode(driver, "parent", type = "GOAL")
        insertNode(driver, "c1", type = "SUB_GOAL", parentId = "parent")
        insertNode(driver, "c2", type = "SUB_GOAL", parentId = "parent")

        assertEquals("NOT_STARTED", computer.derive("parent", "proj1"))

        // One child DONE, one child NOT_STARTED -> parent IN_PROGRESS.
        insertRun(driver, "run1", "COMPLETED")
        insertRunIntentEdge(driver, "run1", "c1", "IMPLEMENTS", confirmed = 1L, confirmedByKind = "USER")
        assertEquals("IN_PROGRESS", computer.derive("parent", "proj1"))

        // Second child unconfirmed IMPLEMENTS -> parent NEEDS_REVIEW.
        insertRun(driver, "run2", "COMPLETED")
        insertRunIntentEdge(driver, "run2", "c2", "IMPLEMENTS", confirmed = 0L)
        assertEquals("NEEDS_REVIEW", computer.derive("parent", "proj1"))

        // Confirm second child -> parent DONE.
        driver.execute(
            identifier = null,
            sql = "UPDATE execution_edges SET confirmed = 1, confirmed_by_kind = 'USER' " +
                "WHERE from_node_id = 'run2' AND to_node_id = 'c2'",
            parameters = 0,
        ) {}
        assertEquals("DONE", computer.derive("parent", "proj1"))
    }

    @Test
    fun testRecursiveDependsOnAndDiamondDag() {
        val (driver, computer) = setupDb()
        insertNode(driver, "top")
        insertNode(driver, "mid-left")
        insertNode(driver, "mid-right")
        insertNode(driver, "leaf")

        insertIntentEdge(driver, "top", "mid-left", "DEPENDS_ON")
        insertIntentEdge(driver, "top", "mid-right", "DEPENDS_ON")
        insertIntentEdge(driver, "mid-left", "leaf", "DEPENDS_ON")
        insertIntentEdge(driver, "mid-right", "leaf", "DEPENDS_ON")

        // leaf is NOT_STARTED -> mid-left, mid-right, top are all BLOCKED.
        assertEquals("BLOCKED", computer.derive("mid-left", "proj1"))
        assertEquals("BLOCKED", computer.derive("top", "proj1"))

        // Complete leaf + mid-left + mid-right; top unblocks and diamond traversal succeeds.
        insertRun(driver, "r-leaf", "COMPLETED")
        insertRunIntentEdge(driver, "r-leaf", "leaf", "IMPLEMENTS", confirmed = 1L, confirmedByKind = "USER")
        assertEquals("NOT_STARTED", computer.derive("mid-left", "proj1"))

        insertRun(driver, "r-mids", "COMPLETED")
        insertRunIntentEdge(driver, "r-mids", "mid-left", "IMPLEMENTS", confirmed = 1L, confirmedByKind = "USER")
        insertRunIntentEdge(driver, "r-mids", "mid-right", "IMPLEMENTS", confirmed = 1L, confirmedByKind = "USER")
        assertEquals("NOT_STARTED", computer.derive("top", "proj1"))
    }

    @Test
    fun testCyclicDependenciesAndParentCyclesTerminateSafelyAsBlocked() {
        val (driver, computer) = setupDb()
        insertNode(driver, "a")
        insertNode(driver, "b")
        insertIntentEdge(driver, "a", "b", "DEPENDS_ON")
        insertIntentEdge(driver, "b", "a", "DEPENDS_ON")
        assertEquals("BLOCKED", computer.derive("a", "proj1"))
        assertEquals("BLOCKED", computer.derive("b", "proj1"))

        insertNode(driver, "p1", parentId = null)
        insertNode(driver, "p2", parentId = "p1")
        driver.execute(
            identifier = null,
            sql = "UPDATE intent_nodes SET parent_id = 'p2' WHERE id = 'p1'",
            parameters = 0,
        ) {}
        assertEquals("BLOCKED", computer.derive("p1", "proj1"))
    }

    @Test
    fun testDanglingProducedContentDerivesStaleAndPropagatesToChildren() {
        val (driver, computer) = setupDb()
        val audit = AuditLog(driver, "test-device")
        val auditId = audit.write(
            id = nextId("aud"),
            projectId = "proj1",
            kind = "AttemptRecorded",
            actor = ActorRef(ActorKind.SESSION, "sess1"),
            subjectRef = "att1",
            detailJson = "{}",
            nowIso = nowIso,
        )

        insertNode(driver, "parent", type = "GOAL")
        insertNode(driver, "child", type = "SUB_GOAL", parentId = "parent")
        insertRun(driver, "run1", "COMPLETED")
        insertRunIntentEdge(driver, "run1", "parent", "IMPLEMENTS", confirmed = 1L, confirmedByKind = "USER")

        driver.execute(
            identifier = null,
            sql = "INSERT INTO tasks (id, run_id, session_id, project_id, ordinal, kind, description, state, retry_policy_json) " +
                "VALUES ('task1', 'run1', 'sess1', 'proj1', 1, 'TOOL_CALL', 'write artifact', 'COMPLETED', '{}')",
            parameters = 0,
        ) {}
        driver.execute(
            identifier = null,
            sql = "INSERT INTO attempts (id, task_id, attempt_number, started_at, state, audit_ref) " +
                "VALUES ('att1', 'task1', 1, ?, 'COMPLETED', ?)",
            parameters = 2,
        ) {
            bindString(0, nowIso)
            bindString(1, auditId)
        }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO content_nodes " +
                "(id, project_id, kind, name, mutability_policy, sensitivity_level, egress_eligibility, " +
                "storage_location_json, content_hash, content_type, size_bytes, created_at, " +
                "created_by_kind, created_by_id, updated_at, state) " +
                "VALUES ('cn1', 'proj1', 'ARTIFACT', 'out.txt', 'IMMUTABLE', 'INTERNAL', 'LOCAL_ONLY', " +
                "'{}', 'sha256:0', 'text/plain', 10, ?, 'SESSION', 'sess1', ?, 'DANGLING')",
            parameters = 2,
        ) {
            bindString(0, nowIso)
            bindString(1, nowIso)
        }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO execution_edges " +
                "(id, project_id, from_node_id, from_node_kind, to_node_id, to_node_kind, edge_kind) " +
                "VALUES ('ee-prod', 'proj1', 'att1', 'ATTEMPT', 'cn1', 'CONTENT_NODE', 'PRODUCED')",
            parameters = 0,
        ) {}

        assertEquals("STALE", computer.derive("parent", "proj1"))
        assertEquals("STALE", computer.derive("child", "proj1"))
    }
}
