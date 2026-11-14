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
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IntentCycleDetectorTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val tempDirs = mutableListOf<File>()
    private var idSeq = 0L

    private val nowIso = "2026-08-05T00:00:00Z"

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        drivers.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private fun setupDb(): Triple<JdbcSqliteDriver, IntentCycleDetector, AuditLog> {
        val root = Files.createTempDirectory("intent-cycle-detector-test").toFile()
        tempDirs.add(root)
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as JdbcSqliteDriver
        drivers.add(driver)
        assertForeignKeysEnabled(driver)

        insertProject(driver, "proj1")
        val audit = AuditLog(driver, "test-device")
        val detector = IntentCycleDetector(
            driver = driver,
            audit = audit,
            idGen = { "id-${idSeq++}" },
            nowIso = { nowIso },
        )
        return Triple(driver, detector, audit)
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

    private fun insertProject(driver: JdbcSqliteDriver, projectId: String) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO projects (id, name, root_path, created_at, updated_at, state_updated_at) " +
                "VALUES (?, ?, '/tmp/test', ?, ?, ?)",
            parameters = 5,
        ) {
            bindString(0, projectId)
            bindString(1, "Project $projectId")
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
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_nodes " +
                "(id, project_id, type, title, created_at, created_by_kind, created_by_id, " +
                "modified_at, modified_by_kind, modified_by_id, tags) " +
                "VALUES (?, ?, ?, ?, ?, 'USER', 'user1', ?, 'USER', 'user1', '[]')",
            parameters = 6,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindString(2, type)
            bindString(3, "Node $id")
            bindString(4, nowIso)
            bindString(5, nowIso)
        }
    }

    private fun insertEdge(
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
            bindString(0, "edge-${idSeq++}")
            bindString(1, projectId)
            bindString(2, fromNodeId)
            bindString(3, toNodeId)
            bindString(4, edgeKind)
            bindString(5, nowIso)
        }
    }

    @Test
    fun testNoCycleAndValidDiamondDag() {
        val (driver, detector, audit) = setupDb()
        insertNode(driver, "a")
        insertNode(driver, "b")
        insertNode(driver, "c")
        insertNode(driver, "d")

        val actor = ActorRef(ActorKind.USER, "user1")
        assertNull(detector.checkEdgeForCycle("proj1", "a", "b", actor), "a -> b should be safe")
        insertEdge(driver, "a", "b")
        assertNull(detector.checkEdgeForCycle("proj1", "a", "c", actor), "a -> c should be safe")
        insertEdge(driver, "a", "c")
        assertNull(detector.checkEdgeForCycle("proj1", "b", "d", actor), "b -> d should be safe")
        insertEdge(driver, "b", "d")
        assertNull(detector.checkEdgeForCycle("proj1", "c", "d", actor), "c -> d diamond should be safe")
        assertEquals(0L, audit.countForProject("proj1"))
    }

    @Test
    fun testSimpleCycleWritesAuditLog() {
        val (driver, detector, audit) = setupDb()
        insertNode(driver, "a")
        insertNode(driver, "b")

        val actor = ActorRef(ActorKind.USER, "user1")
        assertNull(detector.checkEdgeForCycle("proj1", "a", "b", actor))
        insertEdge(driver, "a", "b")

        val err = detector.checkEdgeForCycle("proj1", "b", "a", actor)
        assertNotNull(err, "b -> a should be rejected")
        assertEquals("intent.cycle_rejected", err.code)

        val rows = audit.rowsForProject("proj1")
        assertEquals(1, rows.size)
        assertEquals("IntentCycleDetected", rows.single().kind)
        assertEquals("b → a", rows.single().subjectRef)
    }

    @Test
    fun testTransitiveCycleRejected() {
        val (driver, detector, _) = setupDb()
        insertNode(driver, "a")
        insertNode(driver, "b")
        insertNode(driver, "c")
        insertEdge(driver, "a", "b")
        insertEdge(driver, "b", "c")

        val actor = ActorRef(ActorKind.USER, "user1")
        val err = detector.checkEdgeForCycle("proj1", "c", "a", actor)
        assertNotNull(err, "c -> a should be rejected (a -> b -> c -> a)")
        assertEquals("intent.cycle_rejected", err.code)
    }

    @Test
    fun testSelfLoopRejectedAndAudited() {
        val (driver, detector, audit) = setupDb()
        insertNode(driver, "a")

        val actor = ActorRef(ActorKind.USER, "user1")
        val err = detector.checkEdgeForCycle("proj1", "a", "a", actor)
        assertNotNull(err, "a -> a self-edge must be rejected")
        assertEquals("intent.cycle_rejected", err.code)
        assertEquals("IntentCycleDetected", audit.rowsForProject("proj1").single().kind)
    }

    @Test
    fun testIntraBatchPendingEdgesCycleDetection() {
        val (driver, detector, _) = setupDb()
        insertNode(driver, "a")
        insertNode(driver, "b")
        insertNode(driver, "c")

        val actor = ActorRef(ActorKind.SESSION, "sess1")

        // Purely intra-batch cycle: pending a -> b, b -> c; proposing c -> a closes a 3-cycle.
        val errPureBatch = detector.checkEdgeForCycle(
            projectId = "proj1",
            fromNodeId = "c",
            toNodeId = "a",
            actor = actor,
            pendingEdges = listOf("a" to "b", "b" to "c"),
        )
        assertNotNull(errPureBatch)
        assertEquals("intent.cycle_rejected", errPureBatch.code)

        // Mixed persisted (b -> c) + pending (a -> b); proposing c -> a closes a cycle.
        insertEdge(driver, "b", "c")
        val errMixed = detector.checkEdgeForCycle(
            projectId = "proj1",
            fromNodeId = "c",
            toNodeId = "a",
            actor = actor,
            pendingEdges = listOf("a" to "b"),
        )
        assertNotNull(errMixed)
        assertEquals("intent.cycle_rejected", errMixed.code)

        // Valid transitive diamond via pending edges: a -> b (pending), b -> c (DB), check a -> c.
        val errSafe = detector.checkEdgeForCycle(
            projectId = "proj1",
            fromNodeId = "a",
            toNodeId = "c",
            actor = actor,
            pendingEdges = listOf("a" to "b"),
        )
        assertNull(errSafe)
    }

    @Test
    fun testPreExistingCycleInDbDoesNotLoopInfinitely() {
        val (driver, detector, _) = setupDb()
        insertNode(driver, "x")
        insertNode(driver, "y")
        insertNode(driver, "z")
        insertEdge(driver, "x", "y")
        insertEdge(driver, "y", "x")

        val actor = ActorRef(ActorKind.USER, "user1")
        // z -> x explores x <-> y reachable component without stack overflow or infinite loop.
        assertNull(detector.checkEdgeForCycle("proj1", "z", "x", actor))
    }

    @Test
    fun testNonDependsOnEdgesAndOtherProjectsIgnored() {
        val (driver, detector, _) = setupDb()
        insertProject(driver, "proj2")
        insertNode(driver, "a", projectId = "proj1")
        insertNode(driver, "b", projectId = "proj1")
        insertNode(driver, "ac", projectId = "proj1", type = "ACCEPTANCE_CRITERION")
        insertNode(driver, "p2a", projectId = "proj2")
        insertNode(driver, "p2b", projectId = "proj2")

        // ACCEPTS / CONSTRAINS edges are not dependency edges.
        insertEdge(driver, "b", "a", edgeKind = "CONSTRAINS", projectId = "proj1")
        insertEdge(driver, "a", "ac", edgeKind = "ACCEPTS", projectId = "proj1")
        // Cross-project cycle-shaped ids do not affect proj1.
        insertEdge(driver, "p2b", "p2a", edgeKind = "DEPENDS_ON", projectId = "proj2")

        val actor = ActorRef(ActorKind.USER, "user1")
        assertNull(detector.checkEdgeForCycle("proj1", "a", "b", actor))
    }
}
