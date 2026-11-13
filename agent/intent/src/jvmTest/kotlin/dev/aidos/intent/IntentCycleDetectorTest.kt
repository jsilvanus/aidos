package dev.aidos.intent

import app.cash.sqldelight.sqlite.driver.JdbcSqliteDriver
import dev.aidos.broker.AuditLog
import dev.aidos.identity.DeviceIdentity
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class IntentCycleDetectorTest {

    private fun setupDb(init: String = ""): Pair<JdbcSqliteDriver, IntentCycleDetector> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        // Create schema
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE projects (id TEXT PRIMARY KEY);
                CREATE TABLE audit_log (
                    id TEXT PRIMARY KEY, project_id TEXT, sequence INTEGER,
                    occurred_at TEXT, kind TEXT, actor_kind TEXT, actor_id TEXT,
                    device_id TEXT, subject_ref TEXT, capability_id TEXT, detail_json TEXT
                );
                CREATE TABLE intent_nodes (
                    id TEXT PRIMARY KEY, project_id TEXT NOT NULL, type TEXT NOT NULL,
                    title TEXT NOT NULL, description TEXT, priority INTEGER DEFAULT 100,
                    lifecycle TEXT DEFAULT 'ACTIVE', parent_id TEXT,
                    asserted_status TEXT, asserted_at TEXT, asserted_by_user_id TEXT,
                    assertion_note TEXT, check_kind TEXT, check_spec TEXT,
                    verification_met INTEGER, verified_by_kind TEXT, verified_by_id TEXT,
                    verified_at TEXT, created_at TEXT, created_by_kind TEXT, created_by_id TEXT,
                    modified_at TEXT, modified_by_kind TEXT, modified_by_id TEXT, tags TEXT,
                    row_version INTEGER,
                    FOREIGN KEY (project_id) REFERENCES projects(id),
                    FOREIGN KEY (parent_id) REFERENCES intent_nodes(id),
                    CHECK (verified_by_kind IS NULL OR verified_by_kind IN ('USER', 'CHECK'))
                );
                CREATE TABLE intent_edges (
                    id TEXT PRIMARY KEY, project_id TEXT NOT NULL, from_node_id TEXT,
                    to_node_id TEXT, edge_kind TEXT NOT NULL, created_at TEXT,
                    FOREIGN KEY (project_id) REFERENCES projects(id),
                    FOREIGN KEY (from_node_id) REFERENCES intent_nodes(id),
                    FOREIGN KEY (to_node_id) REFERENCES intent_nodes(id)
                );
                INSERT INTO projects VALUES ('proj1');
            """,
            parameters = 0,
        ) {}

        // Execute init if provided
        if (init.isNotEmpty()) {
            driver.execute(identifier = null, sql = init, parameters = 0) {}
        }

        val audit = AuditLog(driver, "test-device")
        var seqNum = 0L
        val cycleDetector = IntentCycleDetector(
            driver = driver,
            audit = audit,
            idGen = { "id-${seqNum++}" },
            nowIso = { "2025-01-01T00:00:00Z" },
        )
        return Pair(driver, cycleDetector)
    }

    private fun insertNode(driver: JdbcSqliteDriver, id: String, projectId: String = "proj1") {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_nodes " +
                "(id, project_id, type, title, created_at, created_by_kind, created_by_id, " +
                "modified_at, modified_by_kind, modified_by_id, tags) " +
                "VALUES (?, ?, 'GOAL', ?, '2025-01-01T00:00:00Z', 'USER', 'user1', " +
                "'2025-01-01T00:00:00Z', 'USER', 'user1', '[]')",
            parameters = 3,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindString(2, "Node $id")
        }
    }

    @Test
    fun testNoCycle() {
        val (driver, detector) = setupDb()
        insertNode(driver, "a")
        insertNode(driver, "b")
        insertNode(driver, "c")

        val actor = ActorRef(ActorKind.USER, "user1")

        // A → B (no cycle)
        val err1 = detector.checkEdgeForCycle("proj1", "a", "b", actor)
        assertNull(err1, "A → B should be safe")

        // B → C (no cycle)
        val err2 = detector.checkEdgeForCycle("proj1", "b", "c", actor)
        assertNull(err2, "B → C should be safe")
    }

    @Test
    fun testSimpleCycle() {
        val (driver, detector) = setupDb()
        insertNode(driver, "a")
        insertNode(driver, "b")

        val actor = ActorRef(ActorKind.USER, "user1")

        // A → B
        detector.checkEdgeForCycle("proj1", "a", "b", actor)

        // Manually insert A → B edge for the reachability check to work
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_edges (id, project_id, from_node_id, to_node_id, edge_kind, created_at) " +
                "VALUES (?, ?, ?, ?, 'DEPENDS_ON', '2025-01-01T00:00:00Z')",
            parameters = 4,
        ) {
            bindString(0, "edge1")
            bindString(1, "proj1")
            bindString(2, "a")
            bindString(3, "b")
        }

        // B → A (would create cycle A → B → A)
        val err = detector.checkEdgeForCycle("proj1", "b", "a", actor)
        assertNotNull(err, "B → A should be rejected (cycle)")
        assertEquals("intent.cycle_rejected", err.code)
    }

    @Test
    fun testComplexCycle() {
        val (driver, detector) = setupDb()
        insertNode(driver, "a")
        insertNode(driver, "b")
        insertNode(driver, "c")

        val actor = ActorRef(ActorKind.USER, "user1")

        // A → B
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_edges (id, project_id, from_node_id, to_node_id, edge_kind, created_at) " +
                "VALUES (?, ?, ?, ?, 'DEPENDS_ON', '2025-01-01T00:00:00Z')",
            parameters = 4,
        ) {
            bindString(0, "edge1")
            bindString(1, "proj1")
            bindString(2, "a")
            bindString(3, "b")
        }

        // B → C
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_edges (id, project_id, from_node_id, to_node_id, edge_kind, created_at) " +
                "VALUES (?, ?, ?, ?, 'DEPENDS_ON', '2025-01-01T00:00:00Z')",
            parameters = 4,
        ) {
            bindString(0, "edge2")
            bindString(1, "proj1")
            bindString(2, "b")
            bindString(3, "c")
        }

        // C → A (would create cycle A → B → C → A)
        val err = detector.checkEdgeForCycle("proj1", "c", "a", actor)
        assertNotNull(err, "C → A should be rejected (cycle)")
        assertEquals("intent.cycle_rejected", err.code)
    }

    @Test
    fun testSelfEdge() {
        val (driver, detector) = setupDb()
        insertNode(driver, "a")

        val actor = ActorRef(ActorKind.USER, "user1")

        // A → A (self-edge, would create cycle immediately)
        val err = detector.checkEdgeForCycle("proj1", "a", "a", actor)
        assertNotNull(err, "A → A should be rejected (self-cycle)")
        assertEquals("intent.cycle_rejected", err.code)
    }
}
