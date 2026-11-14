package dev.aidos.intent

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.broker.AuditLog
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentProposalEngineTest {

    private fun setupDb(): Pair<JdbcSqliteDriver, IntentProposalEngine> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val statements = listOf(
            "PRAGMA foreign_keys = ON",
            "CREATE TABLE projects (id TEXT PRIMARY KEY)",
            "CREATE TABLE runs (id TEXT PRIMARY KEY)",
            """
            CREATE TABLE audit_log (
                id TEXT PRIMARY KEY, project_id TEXT, sequence INTEGER,
                occurred_at TEXT, kind TEXT, actor_kind TEXT, actor_id TEXT,
                device_id TEXT, subject_ref TEXT, capability_id TEXT, detail_json TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE intent_nodes (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL, type TEXT NOT NULL,
                title TEXT NOT NULL, description TEXT, priority INTEGER NOT NULL DEFAULT 100,
                lifecycle TEXT NOT NULL DEFAULT 'ACTIVE', parent_id TEXT,
                actor_kind TEXT NOT NULL DEFAULT 'AGENT',
                dispatch_policy TEXT NOT NULL DEFAULT 'INHERIT',
                granularity TEXT NOT NULL DEFAULT 'GOAL',
                target_profile TEXT,
                min_locality TEXT,
                estimated_steps INTEGER,
                scope_paths_json TEXT NOT NULL DEFAULT '[]',
                due_at TEXT,
                recurrence_json TEXT,
                asserted_status TEXT, asserted_at TEXT, asserted_by_user_id TEXT,
                assertion_note TEXT, check_kind TEXT, check_spec TEXT,
                verification_met INTEGER, verified_by_kind TEXT, verified_by_id TEXT,
                verified_at TEXT, created_at TEXT NOT NULL, created_by_kind TEXT NOT NULL, created_by_id TEXT NOT NULL,
                modified_at TEXT NOT NULL, modified_by_kind TEXT NOT NULL, modified_by_id TEXT NOT NULL,
                tags TEXT NOT NULL DEFAULT '[]',
                row_version INTEGER NOT NULL DEFAULT 1,
                FOREIGN KEY (project_id) REFERENCES projects(id),
                FOREIGN KEY (parent_id) REFERENCES intent_nodes(id),
                CHECK (verified_by_kind IS NULL OR verified_by_kind IN ('USER', 'CHECK')),
                CHECK (actor_kind IN ('HUMAN', 'AGENT', 'HYBRID')),
                CHECK (dispatch_policy IN ('INHERIT', 'MANUAL_START', 'AUTO_WHEN_READY')),
                CHECK (granularity IN ('GROUP', 'GOAL', 'SUB_GOAL', 'ATOMIC'))
            )
            """.trimIndent(),
            """
            CREATE TABLE intent_edges (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL, from_node_id TEXT NOT NULL,
                to_node_id TEXT NOT NULL, edge_kind TEXT NOT NULL, created_at TEXT NOT NULL,
                FOREIGN KEY (project_id) REFERENCES projects(id),
                FOREIGN KEY (from_node_id) REFERENCES intent_nodes(id),
                FOREIGN KEY (to_node_id) REFERENCES intent_nodes(id),
                UNIQUE (from_node_id, to_node_id, edge_kind)
            )
            """.trimIndent(),
            """
            CREATE TABLE intent_proposals (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL, operations_json TEXT NOT NULL,
                rationale TEXT NOT NULL, proposed_by_run_id TEXT NOT NULL, proposed_at TEXT NOT NULL,
                run_taint TEXT NOT NULL, state TEXT NOT NULL, resolved_by_user_id TEXT, resolved_at TEXT,
                expires_at TEXT NOT NULL, audit_ref TEXT NOT NULL,
                FOREIGN KEY (project_id) REFERENCES projects(id),
                FOREIGN KEY (proposed_by_run_id) REFERENCES runs(id),
                FOREIGN KEY (audit_ref) REFERENCES audit_log(id)
            )
            """.trimIndent(),
            "INSERT INTO projects VALUES ('proj1')",
            "INSERT INTO runs VALUES ('run1')",
        )
        statements.forEach { statement ->
            driver.execute(identifier = null, sql = statement, parameters = 0) {}
        }

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

        var count = 0
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM intent_proposals WHERE id = ?",
            mapper = { resultSet ->
                if (resultSet.next().value) count = resultSet.getLong(0)?.toInt() ?: 0
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, proposalId) },
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
            assertEquals(true, e.message?.contains("intent.node_not_found") == true)
        }
    }

    @Test
    fun testProposalSelfDependency() {
        val (driver, engine) = setupDb()
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
            assertEquals(true, e.message?.contains("intent.self_dependency") == true)
        }
    }

    @Test
    fun testProposalMultipleOperations() {
        val (driver, engine) = setupDb()
        val actor = ActorRef(ActorKind.SESSION, "sess1")

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

        var state = ""
        driver.executeQuery(
            identifier = null,
            sql = "SELECT state FROM intent_proposals WHERE id = ?",
            mapper = { resultSet ->
                if (resultSet.next().value) state = resultSet.getString(0) ?: ""
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, proposalId) },
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

        var resolvedBy: String? = null
        driver.executeQuery(
            identifier = null,
            sql = "SELECT resolved_by_user_id FROM intent_proposals WHERE id = ?",
            mapper = { resultSet ->
                if (resultSet.next().value) {
                    resolvedBy = resultSet.getString(0)
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, proposalId) },
        )
        assertEquals(null, resolvedBy, "resolved_by_user_id should be NULL for new proposal")
    }

    @Test
    fun testAtomicCodingDecompositionBatchAndAcceptanceMaterializesOnAccept() {
        val (driver, engine) = setupDb()
        val actor = ActorRef(ActorKind.SESSION, "desktop-planner")

        val ops = listOf(
            IntentOperation.AddNode(
                parentId = null,
                id = "epic-auth",
                type = "GOAL",
                title = "Harden token refresh",
                description = "Split into mobile-safe atomic edits",
                priority = 10,
                actorKind = "AGENT",
                dispatchPolicy = "MANUAL_START",
                granularity = "GOAL",
            ),
            IntentOperation.AddNode(
                parentId = "epic-auth",
                id = "leaf-1",
                type = "ATOMIC_TASK",
                title = "Add null guard to refreshToken",
                description = "Small scoped change",
                priority = 10,
                actorKind = "AGENT",
                dispatchPolicy = "AUTO_WHEN_READY",
                granularity = "ATOMIC",
                targetProfile = "MOBILE",
                minLocality = "LOCAL_ONLY",
                estimatedSteps = 5,
                scopePaths = listOf("src/Auth.kt"),
            ),
            IntentOperation.AddNode(
                parentId = "epic-auth",
                id = "leaf-2",
                type = "ATOMIC_TASK",
                title = "Update session expiry caller",
                description = "Depends on leaf-1",
                priority = 20,
                actorKind = "AGENT",
                granularity = "ATOMIC",
                targetProfile = "MOBILE",
                minLocality = "LOCAL_ONLY",
                estimatedSteps = 6,
                scopePaths = listOf("src/Session.kt"),
            ),
            IntentOperation.AddNode(
                parentId = "epic-auth",
                id = "crit-1",
                type = "ACCEPTANCE_CRITERION",
                title = "Auth tests pass",
                description = null,
                priority = 10,
                checkKind = "SHELL_COMMAND",
                checkSpec = "./gradlew :auth:jvmTest",
            ),
            IntentOperation.AddDependency(fromNodeId = "leaf-2", toNodeId = "leaf-1"),
            IntentOperation.AddEdge(fromNodeId = "leaf-1", toNodeId = "crit-1", edgeKind = "ACCEPTS"),
        )

        val proposalId = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = ops,
            rationale = "Decompose auth hardening into atomic mobile tasks",
            runTaint = "TRUSTED",
            actor = actor,
            expiresAtIso = "2025-02-01T00:00:00Z",
        )

        val repo = IntentGraphRepository(driver, idGen = { "repo-id" }, nowIso = { "2025-01-01T00:00:00Z" })
        assertNull(repo.getNode("proj1", "leaf-1"), "Proposal must not materialize before user resolution")

        val resolved = engine.resolve(
            projectId = "proj1",
            proposalId = proposalId,
            decision = ProposalResolutionDecision.ACCEPTED,
            resolvedByUserId = "user1",
        )
        assertEquals("ACCEPTED", resolved.state)
        assertEquals("user1", resolved.resolvedByUserId)

        val leaf1 = repo.getNode("proj1", "leaf-1")!!
        assertEquals(IntentGranularity.ATOMIC, leaf1.granularity)
        assertEquals(IntentTargetProfile.MOBILE, leaf1.targetProfile)
        assertEquals(IntentMinLocality.LOCAL_ONLY, leaf1.minLocality)
        assertEquals(5, leaf1.estimatedSteps)
        assertEquals(listOf("src/Auth.kt"), leaf1.scopePaths)

        assertEquals(1, repo.listEdges("proj1", fromNodeId = "leaf-2", edgeKind = IntentEdgeKind.DEPENDS_ON).size)
        assertEquals(1, repo.listEdges("proj1", fromNodeId = "leaf-1", edgeKind = IntentEdgeKind.ACCEPTS).size)

        // Verify IntentProposalResolved was written to audit_log
        var auditCount = 0
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM audit_log WHERE kind = 'IntentProposalResolved' AND subject_ref = ?",
            mapper = { cursor ->
                if (cursor.next().value) auditCount = cursor.getLong(0)?.toInt() ?: 0
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) {
            bindString(0, proposalId)
        }
        assertEquals(1, auditCount)
    }

    @Test
    fun testIntraBatchCycleIsRejected() {
        val (_, engine) = setupDb()
        val actor = ActorRef(ActorKind.SESSION, "sess1")

        val ops = listOf(
            IntentOperation.AddNode(null, "a", "ATOMIC_TASK", "Task A", null, 1),
            IntentOperation.AddNode(null, "b", "ATOMIC_TASK", "Task B", null, 2),
            IntentOperation.AddDependency("a", "b"),
            IntentOperation.AddDependency("b", "a"),
        )

        var thrown: Exception? = null
        try {
            engine.propose(
                projectId = "proj1",
                runId = "run1",
                operations = ops,
                rationale = "Cyclic batch",
                runTaint = "TRUSTED",
                actor = actor,
                expiresAtIso = "2025-02-01T00:00:00Z",
            )
        } catch (e: Exception) {
            thrown = e
        }
        assertNotNull(thrown)
        assertTrue(thrown.message?.contains("intent.cycle_rejected") == true)
    }

    @Test
    fun testProposalRejectedLeavesGraphUntouched() {
        val (driver, engine) = setupDb()
        val actor = ActorRef(ActorKind.SESSION, "sess1")

        val proposalId = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = listOf(IntentOperation.AddNode(null, "unwanted", "GOAL", "Unwanted", null, 1)),
            rationale = "Suggest extra goal",
            runTaint = "TRUSTED",
            actor = actor,
            expiresAtIso = "2025-02-01T00:00:00Z",
        )

        val rejected = engine.resolve(
            projectId = "proj1",
            proposalId = proposalId,
            decision = ProposalResolutionDecision.REJECTED,
            resolvedByUserId = "user1",
        )
        assertEquals("REJECTED", rejected.state)

        val repo = IntentGraphRepository(driver, idGen = { "x" }, nowIso = { "2025-01-01T00:00:00Z" })
        assertNull(repo.getNode("proj1", "unwanted"))
    }
}
