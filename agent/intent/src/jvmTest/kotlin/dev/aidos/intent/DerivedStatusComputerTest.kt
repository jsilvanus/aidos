package dev.aidos.intent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.PlatformProfile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DerivedStatusComputerTest {

    private fun setupDb(): Triple<JdbcSqliteDriver, DerivedStatusComputer, IntentGraphRepository> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val schemaStatements = listOf(
            "PRAGMA foreign_keys = ON",
            "CREATE TABLE projects (id TEXT PRIMARY KEY)",
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
                UNIQUE (from_node_id, to_node_id, edge_kind)
            )
            """.trimIndent(),
            """
            CREATE TABLE execution_edges (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL,
                from_node_id TEXT NOT NULL, from_node_kind TEXT NOT NULL,
                to_node_id TEXT NOT NULL, to_node_kind TEXT NOT NULL,
                edge_kind TEXT NOT NULL,
                confirmed INTEGER NOT NULL DEFAULT 0,
                confirmed_by_kind TEXT, confirmed_by_id TEXT, confirmed_at TEXT,
                UNIQUE (from_node_id, to_node_id, edge_kind)
            )
            """.trimIndent(),
            """
            CREATE TABLE content_nodes (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL, kind TEXT NOT NULL,
                uri TEXT, content_hash TEXT, byte_size INTEGER, media_type TEXT,
                state TEXT NOT NULL, created_at TEXT NOT NULL, updated_at TEXT NOT NULL
            )
            """.trimIndent(),
            "CREATE TABLE runs (id TEXT PRIMARY KEY, state TEXT NOT NULL)",
            "CREATE TABLE tasks (id TEXT PRIMARY KEY, run_id TEXT NOT NULL)",
            "CREATE TABLE attempts (id TEXT PRIMARY KEY, task_id TEXT NOT NULL)",
            "INSERT INTO projects VALUES ('proj1')",
        )
        schemaStatements.forEach { statement ->
            driver.execute(identifier = null, sql = statement, parameters = 0) {}
        }

        var seq = 1L
        val computer = DerivedStatusComputer(driver)
        val repo = IntentGraphRepository(
            driver = driver,
            idGen = { "id-${seq++}" },
            nowIso = { "2025-01-01T00:00:00Z" },
        )
        return Triple(driver, computer, repo)
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
                "VALUES (?, ?, ?, ?, '2025-01-01T00:00:00Z', 'USER', 'user1', " +
                "'2025-01-01T00:00:00Z', 'USER', 'user1', '[]')",
            parameters = 4,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindString(2, type)
            bindString(3, "Node $id")
        }
    }

    private fun insertRun(driver: JdbcSqliteDriver, id: String, state: String) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO runs (id, state) VALUES (?, ?)",
            parameters = 2,
        ) {
            bindString(0, id)
            bindString(1, state)
        }
    }

    @Test
    fun testNotStartedStatus() {
        val (driver, computer, _) = setupDb()
        insertNode(driver, "goal1")

        val status = computer.derive("goal1", "proj1")
        assertEquals("NOT_STARTED", status)
    }

    @Test
    fun testBlockedStatusUnmetDependency() {
        val (driver, computer, repo) = setupDb()
        insertNode(driver, "goal1")
        insertNode(driver, "goal2")
        repo.addEdge("proj1", "goal1", "goal2", IntentEdgeKind.DEPENDS_ON)

        val status = computer.derive("goal1", "proj1")
        assertEquals("BLOCKED", status, "goal1 should be BLOCKED because goal2 is not DONE")
    }

    @Test
    fun testBlockedStatusUnmetMetDependency() {
        val (driver, computer, repo) = setupDb()
        insertNode(driver, "goal1")
        insertNode(driver, "goal2")
        repo.addEdge("proj1", "goal1", "goal2", IntentEdgeKind.DEPENDS_ON)

        driver.execute(
            identifier = null,
            sql = "UPDATE intent_nodes SET asserted_status = 'DONE' WHERE id = ?",
            parameters = 1,
        ) {
            bindString(0, "goal2")
        }

        val status = computer.derive("goal1", "proj1")
        assertEquals("NOT_STARTED", status, "goal1 should be NOT_STARTED since dependency is satisfied")
    }

    @Test
    fun testDoneStatus() {
        val (driver, computer, repo) = setupDb()
        insertNode(driver, "goal1", type = "GOAL")
        insertRun(driver, "run1", "COMPLETED")
        repo.recordRunImplements("proj1", "run1", "goal1", confirmed = false)

        driver.execute(
            identifier = null,
            sql = "UPDATE intent_nodes SET verified_by_kind = 'USER', verification_met = 1 WHERE id = ?",
            parameters = 1,
        ) {
            bindString(0, "goal1")
        }

        val status = computer.derive("goal1", "proj1")
        assertEquals("DONE", status, "goal1 should be DONE with verified IMPLEMENTS")
    }

    @Test
    fun testInProgressStatus() {
        val (driver, computer, repo) = setupDb()
        insertNode(driver, "goal1")
        insertRun(driver, "run1", "RUNNING")
        repo.recordRunTargeting("proj1", "run1", "goal1")

        val status = computer.derive("goal1", "proj1")
        assertEquals("IN_PROGRESS", status, "goal1 should be IN_PROGRESS with active TARGETED run")
    }

    @Test
    fun testNeedsReviewStatus() {
        val (driver, computer, repo) = setupDb()
        insertNode(driver, "goal1")
        insertRun(driver, "run1", "COMPLETED")
        repo.recordRunImplements("proj1", "run1", "goal1", confirmed = false)

        val status = computer.derive("goal1", "proj1")
        assertEquals("NEEDS_REVIEW", status, "goal1 should be NEEDS_REVIEW with unconfirmed IMPLEMENTS")
    }

    @Test
    fun testLifeManagementAgentToHumanAndHumanToAgentHandoffs() {
        val (driver, computer, repo) = setupDb()

        val group = repo.createNode(
            projectId = "proj1",
            id = "groceries-group",
            type = IntentNodeType.GROUP,
            title = "Weekly Groceries",
            actorKind = IntentActorKind.HYBRID,
            dispatchPolicy = NodeDispatchPolicy.AUTO_WHEN_READY,
        )
        val addMilk = repo.createNode(
            projectId = "proj1",
            id = "add-milk",
            type = IntentNodeType.ATOMIC_TASK,
            title = "Add milk to shopping list",
            parentId = group.id,
            actorKind = IntentActorKind.AGENT,
        )
        val buyMilk = repo.createNode(
            projectId = "proj1",
            id = "buy-milk",
            type = IntentNodeType.ATOMIC_TASK,
            title = "Buy milk / send shopping list as order",
            parentId = group.id,
            actorKind = IntentActorKind.HUMAN,
            dueAt = "2025-01-02T18:00:00Z",
        )
        val confirmBudget = repo.createNode(
            projectId = "proj1",
            id = "confirm-budget",
            type = IntentNodeType.ATOMIC_TASK,
            title = "Confirm grocery budget",
            parentId = group.id,
            actorKind = IntentActorKind.HUMAN,
        )
        val generateOrderDraft = repo.createNode(
            projectId = "proj1",
            id = "draft-order",
            type = IntentNodeType.ATOMIC_TASK,
            title = "Generate order draft",
            parentId = group.id,
            actorKind = IntentActorKind.AGENT,
        )

        // Human buys milk only after agent adds milk; agent drafts order only after human confirms budget.
        repo.addEdge("proj1", buyMilk.id, addMilk.id, IntentEdgeKind.DEPENDS_ON)
        repo.addEdge("proj1", generateOrderDraft.id, confirmBudget.id, IntentEdgeKind.DEPENDS_ON)

        assertEquals("BLOCKED", computer.derive(buyMilk.id, "proj1"))
        assertEquals("BLOCKED", computer.derive(generateOrderDraft.id, "proj1"))

        val initialHumanTasks = computer.listActionableForHuman("proj1").map { it.node.id }
        assertFalse(buyMilk.id in initialHumanTasks, "buy-milk must be excluded while BLOCKED")
        assertTrue(confirmBudget.id in initialHumanTasks, "confirm-budget is immediately actionable")

        // Agent completes add-milk -> buy-milk becomes actionable for human.
        insertRun(driver, "run-milk", "COMPLETED")
        repo.recordRunImplements(
            projectId = "proj1",
            runId = "run-milk",
            nodeId = addMilk.id,
            confirmed = true,
            confirmedByKind = "CHECK",
            confirmedById = "list-check",
        )
        assertEquals("DONE", computer.derive(addMilk.id, "proj1"))
        assertEquals("NOT_STARTED", computer.derive(buyMilk.id, "proj1"))
        assertTrue(buyMilk.id in computer.listActionableForHuman("proj1").map { it.node.id })

        // Human completes buy-milk and confirm-budget -> draft-order unblocks and inherits AUTO_WHEN_READY.
        repo.completeHumanNode("proj1", buyMilk.id, "user1", "Ordered milk")
        assertEquals("DONE", computer.derive(buyMilk.id, "proj1"))

        val initialAutoReady = computer.listReadyForAgent("proj1", onlyAutoDispatch = true).map { it.node.id }
        assertFalse(generateOrderDraft.id in initialAutoReady)

        repo.completeHumanNode("proj1", confirmBudget.id, "user1")
        assertEquals("NOT_STARTED", computer.derive(generateOrderDraft.id, "proj1"))

        val readyAuto = computer.listReadyForAgent("proj1", onlyAutoDispatch = true)
        val draftView = readyAuto.single { it.node.id == generateOrderDraft.id }
        assertEquals(EffectiveDispatchPolicy.AUTO_WHEN_READY, draftView.effectiveDispatchPolicy)
    }

    @Test
    fun testCodingManagementMobileExecutionNeedsReviewThenDesktopCheckDone() {
        val (driver, computer, repo) = setupDb()

        val desktopTask = repo.createNode(
            projectId = "proj1",
            id = "refactor-core",
            type = IntentNodeType.ATOMIC_TASK,
            title = "Refactor desktop indexing pipeline",
            actorKind = IntentActorKind.AGENT,
            targetProfile = IntentTargetProfile.DESKTOP,
            minLocality = IntentMinLocality.ANY,
            estimatedSteps = 12,
        )
        val mobileLeaf = repo.createNode(
            projectId = "proj1",
            id = "mobile-fix",
            type = IntentNodeType.ATOMIC_TASK,
            title = "Fix null check in Auth.kt",
            actorKind = IntentActorKind.AGENT,
            targetProfile = IntentTargetProfile.MOBILE,
            minLocality = IntentMinLocality.LOCAL_ONLY,
            estimatedSteps = 6,
            scopePaths = listOf("src/Auth.kt"),
        )
        val shellCheck = repo.createNode(
            projectId = "proj1",
            id = "crit-shell",
            type = IntentNodeType.ACCEPTANCE_CRITERION,
            title = "Run gradle auth unit tests",
            actorKind = IntentActorKind.AGENT,
            checkKind = IntentCheckKind.SHELL_COMMAND,
            checkSpec = "./gradlew :auth:jvmTest",
        )
        val followupTask = repo.createNode(
            projectId = "proj1",
            id = "followup-ui",
            type = IntentNodeType.ATOMIC_TASK,
            title = "Wire Auth error banner",
            actorKind = IntentActorKind.AGENT,
            targetProfile = IntentTargetProfile.MOBILE,
            minLocality = IntentMinLocality.LOCAL_ONLY,
        )

        repo.addEdge("proj1", mobileLeaf.id, shellCheck.id, IntentEdgeKind.ACCEPTS)
        repo.addEdge("proj1", followupTask.id, mobileLeaf.id, IntentEdgeKind.DEPENDS_ON)

        // Mobile local agent sees only mobile-compatible tasks, not DESKTOP-only tasks.
        val mobileReadyIds = computer.listReadyForAgent(
            projectId = "proj1",
            profile = PlatformProfile.MOBILE,
            locality = IntentMinLocality.LOCAL_ONLY,
        ).map { it.node.id }
        assertTrue(mobileLeaf.id in mobileReadyIds)
        assertFalse(desktopTask.id in mobileReadyIds)
        assertFalse(followupTask.id in mobileReadyIds)
        assertTrue(computer.hasPendingPlatformChecks(mobileLeaf.id, "proj1"))

        // Mobile run implements unconfirmed -> parks in NEEDS_REVIEW; followup remains BLOCKED.
        insertRun(driver, "run-mobile", "COMPLETED")
        repo.recordRunImplements("proj1", "run-mobile", mobileLeaf.id, confirmed = false)
        assertEquals("NEEDS_REVIEW", computer.derive(mobileLeaf.id, "proj1"))
        assertEquals("BLOCKED", computer.derive(followupTask.id, "proj1"))

        // Desktop mechanical test runner confirms implementation + SHELL_COMMAND criterion -> DONE.
        repo.confirmPendingImplementation(
            projectId = "proj1",
            nodeId = mobileLeaf.id,
            confirmedByKind = "CHECK",
            confirmedById = "desktop-test-runner",
        )
        assertEquals("DONE", computer.derive(mobileLeaf.id, "proj1"))
        assertFalse(computer.hasPendingPlatformChecks(mobileLeaf.id, "proj1"))
        assertEquals("NOT_STARTED", computer.derive(followupTask.id, "proj1"))
    }

    @Test
    fun testStaleStatusWhenProducedContentDangling() {
        val (driver, computer, repo) = setupDb()
        val task = repo.createNode("proj1", "Fix README", id = "doc-task")
        insertRun(driver, "run-doc", "COMPLETED")
        repo.recordRunImplements("proj1", "run-doc", task.id, confirmed = true, confirmedByKind = "USER", confirmedById = "u1")

        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO content_nodes (id, project_id, kind, uri, state, created_at, updated_at)
                VALUES ('cn-1', 'proj1', 'FILE', 'README.md', 'DANGLING', '2025-01-01T00:00:00Z', '2025-01-01T00:00:00Z')
            """.trimIndent(),
            parameters = 0,
        )
        driver.execute(
            identifier = null,
            sql = """
                INSERT INTO execution_edges (id, project_id, from_node_id, from_node_kind, to_node_id, to_node_kind, edge_kind)
                VALUES ('prod-1', 'proj1', 'run-doc', 'RUN', 'cn-1', 'CONTENT_NODE', 'PRODUCED')
            """.trimIndent(),
            parameters = 0,
        )

        assertEquals("STALE", computer.derive(task.id, "proj1"))
    }

    @Test
    fun testSnapshotCodecFastForwardAndConflictFlagging() {
        val (driver1, computer1, repo1) = setupDb()
        val (driver2, computer2, repo2) = setupDb()
        val snap1 = IntentSnapshotCodec(driver1, { "2025-01-01T01:00:00Z" })
        val snap2 = IntentSnapshotCodec(driver2, { "2025-01-01T02:00:00Z" })

        repo1.createNode("proj1", "Parent Group", id = "g1", type = IntentNodeType.GROUP)
        repo1.createNode("proj1", "Child Task", id = "a1", type = IntentNodeType.ATOMIC_TASK, parentId = "g1")
        val initialExport = snap1.exportJson("proj1")
        val initialResult = snap2.importAndReconcile("proj1", initialExport)
        assertEquals(2, initialResult.addedNodes)
        assertEquals(0, initialResult.conflictedNodeIds.size)

        // Deterministic export stability
        assertEquals(initialExport, snap1.exportJson("proj1"))

        // Side 1 fast-forwards a1 to rowVersion=2; Side 2 hasn't edited it.
        repo1.updateNode("proj1", "a1", title = "Child Task v2", modifiedBy = ActorRef(ActorKind.USER, "desktop"))
        val ffResult = snap2.importAndReconcile("proj1", snap1.exportJson("proj1"))
        assertEquals(1, ffResult.updatedNodes)
        assertEquals("Child Task v2", repo2.getNode("proj1", "a1")!!.title)

        // Both sides edit g1 independently from rowVersion=1 -> rowVersion=2 (divergent equal version).
        repo1.updateNode("proj1", "g1", title = "Desktop Group Edit")
        repo2.updateNode("proj1", "g1", title = "Phone Group Edit")
        val conflictResult = snap2.importAndReconcile("proj1", snap1.exportJson("proj1"))
        assertEquals(listOf("g1"), conflictResult.conflictedNodeIds)
        assertEquals("CONFLICTED", computer2.derive("g1", "proj1"))
        assertEquals("NOT_STARTED", computer1.derive("a1", "proj1"))
    }
}
