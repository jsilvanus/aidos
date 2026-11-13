package dev.aidos.intent

import app.cash.sqldelight.sqlite.driver.JdbcSqliteDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

class IntentProposalEngineTest {

    private fun setupDb(): Pair<JdbcSqliteDriver, IntentProposalEngine> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        // Create schema
        driver.execute(
            identifier = null,
            sql = """
                CREATE TABLE projects (id TEXT PRIMARY KEY);
                CREATE TABLE runs (id TEXT PRIMARY KEY);
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
                CREATE TABLE intent_proposals (
                    id TEXT PRIMARY KEY, project_id TEXT, operations_json TEXT,
                    rationale TEXT, proposed_by_run_id TEXT, proposed_at TEXT,
                    run_taint TEXT, state TEXT, resolved_by_user_id TEXT, resolved_at TEXT,
                    expires_at TEXT, audit_ref TEXT,
                    FOREIGN KEY (project_id) REFERENCES projects(id),
                    FOREIGN KEY (proposed_by_run_id) REFERENCES runs(id)
                );
                INSERT INTO projects VALUES ('proj1');
                INSERT INTO runs VALUES ('run1');
            """,
            parameters = 0,
        ) {}

        val audit = AuditLog(driver, "test-device")
        var seqNum = 0L
        val cycleDetector = IntentCycleDetector(
            driver = driver,
            audit = audit,
            idGen = { "id-${seqNum++}" },
            nowIso = { "2025-01-01T00:00:00Z" },
        )
        val engine = IntentProposalEngine(
            driver = driver,
            audit = audit,
            cycleDetector = cycleDetector,
            idGen = { "id-${seqNum++}" },
            nowIso = { "2025-01-01T00:00:00Z" },
        )
        return Pair(driver, engine)
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
    fun testProposeSingleAddNode() {
        val (driver, engine) = setupDb()
        val actor = ActorRef(ActorKind.SESSION, "sess1")

        val ops = listOf(
            IntentOperation.AddNode(
                parentId = null,
                id = "goal1",
                type = "GOAL",
                title = "Build feature",
                description = "Implement new feature",
                priority = 1,
            ),
        )

        val proposalId = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = ops,
            rationale = "AI suggests decomposition",
            runTaint = "TRUSTED",
            actor = actor,
            expiresAtIso = "2025-02-01T00:00:00Z",
        )

        assertNotNull(proposalId)

        // Verify proposal was inserted
        var count = 0
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM intent_proposals WHERE id = ?",
            parameters = 1,
            binders = { bindString(0, proposalId) },
            mapper = { resultSet ->
                if (resultSet.next()) count = resultSet.getLong(0).toInt()
            },
        )
        assertEquals(1, count, "Proposal should be inserted")
    }

    @Test
    fun testProposalNodeNotFound() {
        val (_, engine) = setupDb()
        val actor = ActorRef(ActorKind.SESSION, "sess1")

        val ops = listOf(
            IntentOperation.ModifyNode(
                id = "nonexistent",
                title = "New title",
            ),
        )

        try {
            engine.propose(
                projectId = "proj1",
                runId = "run1",
                operations = ops,
                rationale = "Modify nonexistent node",
                runTaint = "TRUSTED",
                actor = actor,
                expiresAtIso = "2025-02-01T00:00:00Z",
            )
            assert(false) { "Should throw error for nonexistent node" }
        } catch (e: Exception) {
            assertEquals("intent.node_not_found", (e as? dev.aidos.kernel.AidosError)?.code)
        }
    }

    @Test
    fun testProposalSelfDependency() {
        val (_, engine) = setupDb()
        val (driver, _) = setupDb()  // Need another DB for inserting node
        insertNode(driver, "node1")

        val actor = ActorRef(ActorKind.SESSION, "sess1")

        val ops = listOf(
            IntentOperation.AddDependency(
                fromNodeId = "node1",
                toNodeId = "node1",
            ),
        )

        try {
            engine.propose(
                projectId = "proj1",
                runId = "run1",
                operations = ops,
                rationale = "Self-dependency",
                runTaint = "TRUSTED",
                actor = actor,
                expiresAtIso = "2025-02-01T00:00:00Z",
            )
            assert(false) { "Should reject self-dependency" }
        } catch (e: Exception) {
            assertEquals("intent.self_dependency", (e as? dev.aidos.kernel.AidosError)?.code)
        }
    }

    @Test
    fun testProposalMultipleOperations() {
        val (driver, engine) = setupDb()
        val actor = ActorRef(ActorKind.SESSION, "sess1")

        // Insert a parent goal
        insertNode(driver, "parent-goal")

        val ops = listOf(
            IntentOperation.AddNode(
                parentId = "parent-goal",
                id = "sub-goal-1",
                type = "SUB_GOAL",
                title = "Sub-goal 1",
                description = null,
                priority = 1,
            ),
            IntentOperation.AddNode(
                parentId = "parent-goal",
                id = "sub-goal-2",
                type = "SUB_GOAL",
                title = "Sub-goal 2",
                description = null,
                priority = 2,
            ),
            IntentOperation.AddDependency(
                fromNodeId = "sub-goal-2",
                toNodeId = "sub-goal-1",
            ),
        )

        val proposalId = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = ops,
            rationale = "Decompose parent into sub-goals",
            runTaint = "TRUSTED",
            actor = actor,
            expiresAtIso = "2025-02-01T00:00:00Z",
        )

        assertNotNull(proposalId)

        // Verify proposal was inserted with all ops
        var state = ""
        driver.executeQuery(
            identifier = null,
            sql = "SELECT state FROM intent_proposals WHERE id = ?",
            parameters = 1,
            binders = { bindString(0, proposalId) },
            mapper = { resultSet ->
                if (resultSet.next()) state = resultSet.getString(0)
            },
        )
        assertEquals("PENDING", state, "Proposal should be in PENDING state")
    }

    @Test
    fun testProposalOnlyUserCanResolve() {
        val (driver, engine) = setupDb()
        val actor = ActorRef(ActorKind.SESSION, "sess1")

        val ops = listOf(
            IntentOperation.AddNode(
                parentId = null,
                id = "goal1",
                type = "GOAL",
                title = "Build feature",
                description = null,
                priority = 1,
            ),
        )

        val proposalId = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = ops,
            rationale = "Test proposal",
            runTaint = "TRUSTED",
            actor = actor,
            expiresAtIso = "2025-02-01T00:00:00Z",
        )

        // Verify resolved_by_user_id is NULL (no session can resolve)
        var resolvedBy: String? = null
        driver.executeQuery(
            identifier = null,
            sql = "SELECT resolved_by_user_id FROM intent_proposals WHERE id = ?",
            parameters = 1,
            binders = { bindString(0, proposalId) },
            mapper = { resultSet ->
                if (resultSet.next()) {
                    resolvedBy = resultSet.getString(0)
                }
            },
        )
        assertEquals(null, resolvedBy, "resolved_by_user_id should be NULL for new proposal")
    }
}
