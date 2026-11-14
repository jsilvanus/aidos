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
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class IntentProposalEngineTest {

    private val drivers = mutableListOf<JdbcSqliteDriver>()
    private val tempDirs = mutableListOf<File>()
    private var idSeq = 0L

    private val nowIso = "2026-08-05T12:00:00Z"

    @AfterTest
    fun tearDown() {
        drivers.forEach { it.close() }
        drivers.clear()
        tempDirs.forEach { it.deleteRecursively() }
        tempDirs.clear()
    }

    private data class Harness(
        val driver: JdbcSqliteDriver,
        val engine: IntentProposalEngine,
        val audit: AuditLog,
        val statusComputer: DerivedStatusComputer,
    )

    private fun setupDb(): Harness {
        val root = Files.createTempDirectory("intent-proposal-engine-test").toFile()
        tempDirs.add(root)
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as JdbcSqliteDriver
        drivers.add(driver)
        assertForeignKeysEnabled(driver)

        driver.execute(
            identifier = null,
            sql = "INSERT INTO projects (id, name, root_path, created_at, updated_at, state_updated_at) " +
                "VALUES ('proj1', 'Test Project', '/tmp/test', ?, ?, ?)",
            parameters = 3,
        ) {
            bindString(0, nowIso)
            bindString(1, nowIso)
            bindString(2, nowIso)
        }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES ('sess1', 'proj1', 'Driver', 'DRIVER', 'RUNNING', ?, ?, ?)",
            parameters = 3,
        ) {
            bindString(0, nowIso)
            bindString(1, nowIso)
            bindString(2, nowIso)
        }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, state, " +
                "retry_policy_json, platform_profile, device_id) " +
                "VALUES ('run1', 'sess1', 'proj1', 'evt-1', ?, 'RUNNING', '{}', 'DESKTOP', 'dev-1')",
            parameters = 1,
        ) {
            bindString(0, nowIso)
        }

        val audit = AuditLog(driver, "test-device")
        val cycleDetector = IntentCycleDetector(
            driver = driver,
            audit = audit,
            idGen = { "cyc-${idSeq++}" },
            nowIso = { nowIso },
        )
        val engine = IntentProposalEngine(
            driver = driver,
            audit = audit,
            cycleDetector = cycleDetector,
            idGen = { "id-${idSeq++}" },
            nowIso = { nowIso },
        )
        return Harness(
            driver = driver,
            engine = engine,
            audit = audit,
            statusComputer = DerivedStatusComputer(driver),
        )
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

    private fun insertNode(driver: JdbcSqliteDriver, id: String, projectId: String = "proj1") {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_nodes " +
                "(id, project_id, type, title, created_at, created_by_kind, created_by_id, " +
                "modified_at, modified_by_kind, modified_by_id, tags) " +
                "VALUES (?, ?, 'GOAL', ?, ?, 'USER', 'user1', ?, 'USER', 'user1', '[]')",
            parameters = 5,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindString(2, "Node $id")
            bindString(3, nowIso)
            bindString(4, nowIso)
        }
    }

    private fun countRows(driver: JdbcSqliteDriver, sql: String, vararg args: String): Int {
        var count = 0
        driver.executeQuery(
            identifier = null,
            sql = sql,
            parameters = args.size,
            binders = {
                args.forEachIndexed { index, value ->
                    bindString(index, value)
                }
            },
            mapper = { cursor ->
                if (cursor.next().value) {
                    count = cursor.getLong(0)?.toInt() ?: 0
                }
                QueryResult.Value(Unit)
            },
        )
        return count
    }

    @Test
    fun testProposeSatisfiesAuditRefForeignKeyAndPersistsPendingProposal() {
        val (driver, engine, audit, _) = setupDb()
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
            rationale = "AI suggests goal",
            runTaint = "TRUSTED",
            actor = actor,
            expiresAtIso = "2026-08-06T00:00:00Z",
        )

        // Check audit_ref FK points to real IntentProposalCreated audit_log entry.
        var auditRef: String? = null
        driver.executeQuery(
            identifier = null,
            sql = "SELECT audit_ref FROM intent_proposals WHERE id = ?",
            parameters = 1,
            binders = { bindString(0, proposalId) },
            mapper = { cursor ->
                if (cursor.next().value) auditRef = cursor.getString(0)
                QueryResult.Value(Unit)
            },
        )
        assertNotNull(auditRef)
        val auditRows = audit.rowsForProject("proj1")
        assertEquals(1, auditRows.size)
        assertEquals(auditRef, auditRows.single().id)
        assertEquals("IntentProposalCreated", auditRows.single().kind)
        assertEquals(proposalId, auditRows.single().subjectRef)

        val loaded = engine.getProposal(proposalId, "proj1")
        assertNotNull(loaded)
        assertEquals("PENDING", loaded.state)
        assertNull(loaded.resolvedByUserId)
        assertEquals(1, loaded.operations.size)
        assertEquals(listOf(loaded), engine.listPendingProposals("proj1"))
    }

    @Test
    fun testProposalValidationRejectsMissingNodeInvalidParentAndSelfDependency() {
        val (driver, engine, _, _) = setupDb()
        insertNode(driver, "node1")
        val actor = ActorRef(ActorKind.SESSION, "sess1")

        val missingErr = assertFailsWith<NoSuchElementException> {
            engine.propose(
                projectId = "proj1",
                runId = "run1",
                operations = listOf(IntentOperation.ModifyNode(id = "nonexistent", title = "Updated")),
                rationale = "Missing target",
                runTaint = "TRUSTED",
                actor = actor,
                expiresAtIso = "2026-08-06T00:00:00Z",
            )
        }
        assertTrue(missingErr.message?.contains("intent.node_not_found") == true)

        val parentErr = assertFailsWith<IllegalArgumentException> {
            engine.propose(
                projectId = "proj1",
                runId = "run1",
                operations = listOf(
                    IntentOperation.AddNode(
                        parentId = "missing-parent",
                        id = "child1",
                        type = "SUB_GOAL",
                        title = "Orphan",
                        description = null,
                        priority = 10,
                    ),
                ),
                rationale = "Invalid parent",
                runTaint = "TRUSTED",
                actor = actor,
                expiresAtIso = "2026-08-06T00:00:00Z",
            )
        }
        assertTrue(parentErr.message?.contains("intent.invalid_parent") == true)

        val selfDepErr = assertFailsWith<IllegalArgumentException> {
            engine.propose(
                projectId = "proj1",
                runId = "run1",
                operations = listOf(IntentOperation.AddDependency(fromNodeId = "node1", toNodeId = "node1")),
                rationale = "Self loop",
                runTaint = "TRUSTED",
                actor = actor,
                expiresAtIso = "2026-08-06T00:00:00Z",
            )
        }
        assertTrue(selfDepErr.message?.contains("intent.self_dependency") == true)
    }

    @Test
    fun testIntraBatchAddNodeAndAddDependencyApplyTopologicallyOnAccept() {
        val (driver, engine, audit, statusComputer) = setupDb()
        val sessionActor = ActorRef(ActorKind.SESSION, "sess1")
        val userActor = ActorRef(ActorKind.USER, "user1")

        // Intentionally order dependency and child BEFORE parent/target nodes inside the batch.
        val ops = listOf(
            IntentOperation.AddDependency(fromNodeId = "sub-b", toNodeId = "sub-a"),
            IntentOperation.AddNode(
                parentId = "root-goal",
                id = "sub-b",
                type = "SUB_GOAL",
                title = "Step B",
                description = null,
                priority = 2,
            ),
            IntentOperation.AddNode(
                parentId = "root-goal",
                id = "sub-a",
                type = "SUB_GOAL",
                title = "Step A",
                description = null,
                priority = 1,
            ),
            IntentOperation.AddNode(
                parentId = null,
                id = "root-goal",
                type = "GOAL",
                title = "Root Goal",
                description = "Parent created in same batch",
                priority = 1,
            ),
        )

        val proposalId = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = ops,
            rationale = "Create hierarchy and dependency in one proposal",
            runTaint = "TRUSTED",
            actor = sessionActor,
            expiresAtIso = "2026-08-06T00:00:00Z",
        )

        // Before user approval, graph is untouched.
        assertEquals(0, countRows(driver, "SELECT COUNT(*) FROM intent_nodes WHERE project_id = ?", "proj1"))
        assertEquals(0, countRows(driver, "SELECT COUNT(*) FROM intent_edges WHERE project_id = ?", "proj1"))

        val resolvedState = engine.resolveProposal(
            proposalId = proposalId,
            projectId = "proj1",
            resolution = ProposalResolution.Accept,
            actor = userActor,
        )

        assertEquals(ProposalState.ACCEPTED, resolvedState)
        assertEquals(3, countRows(driver, "SELECT COUNT(*) FROM intent_nodes WHERE project_id = ?", "proj1"))
        assertEquals(
            1,
            countRows(
                driver,
                "SELECT COUNT(*) FROM intent_edges WHERE project_id = ? AND from_node_id = ? AND to_node_id = ? AND edge_kind = 'DEPENDS_ON'",
                "proj1",
                "sub-b",
                "sub-a",
            ),
        )
        assertTrue(engine.listPendingProposals("proj1").isEmpty())

        val resolved = engine.getProposal(proposalId, "proj1")
        assertNotNull(resolved)
        assertEquals("ACCEPTED", resolved.state)
        assertEquals("user1", resolved.resolvedByUserId)
        assertEquals(nowIso, resolved.resolvedAt)

        val auditKinds = audit.rowsForProject("proj1").map { it.kind }
        assertEquals(listOf("IntentProposalCreated", "IntentProposalResolved"), auditKinds)

        // Derived status respects applied dependency: sub-b depends on NOT_STARTED sub-a -> BLOCKED.
        assertEquals("NOT_STARTED", statusComputer.derive("sub-a", "proj1"))
        assertEquals("BLOCKED", statusComputer.derive("sub-b", "proj1"))
    }

    @Test
    fun testIntraBatchCycleInProposalRejectedAndAudited() {
        val (driver, engine, audit, _) = setupDb()
        val sessionActor = ActorRef(ActorKind.SESSION, "sess1")

        val cyclicDeps = listOf(
            IntentOperation.AddNode(null, "n1", "GOAL", "N1", null, 1),
            IntentOperation.AddNode(null, "n2", "GOAL", "N2", null, 2),
            IntentOperation.AddNode(null, "n3", "GOAL", "N3", null, 3),
            IntentOperation.AddDependency("n1", "n2"),
            IntentOperation.AddDependency("n2", "n3"),
            IntentOperation.AddDependency("n3", "n1"),
        )

        val cycleErr = assertFailsWith<IllegalArgumentException> {
            engine.propose(
                projectId = "proj1",
                runId = "run1",
                operations = cyclicDeps,
                rationale = "Cyclic batch",
                runTaint = "TRUSTED",
                actor = sessionActor,
                expiresAtIso = "2026-08-06T00:00:00Z",
            )
        }
        assertTrue(cycleErr.message?.contains("intent.cycle_rejected") == true)
        assertEquals(0, countRows(driver, "SELECT COUNT(*) FROM intent_proposals WHERE project_id = ?", "proj1"))
        assertEquals("IntentCycleDetected", audit.rowsForProject("proj1").single().kind)

        // Parent hierarchy cycle within same batch is also rejected.
        val parentCycleOps = listOf(
            IntentOperation.AddNode(parentId = "p2", id = "p1", type = "SUB_GOAL", title = "P1", description = null, priority = 1),
            IntentOperation.AddNode(parentId = "p1", id = "p2", type = "SUB_GOAL", title = "P2", description = null, priority = 2),
        )
        val parentCycleErr = assertFailsWith<IllegalArgumentException> {
            engine.propose(
                projectId = "proj1",
                runId = "run1",
                operations = parentCycleOps,
                rationale = "Cyclic parent hierarchy",
                runTaint = "TRUSTED",
                actor = sessionActor,
                expiresAtIso = "2026-08-06T00:00:00Z",
            )
        }
        assertTrue(parentCycleErr.message?.contains("intent.cycle_rejected") == true)
    }

    @Test
    fun testResolveProposalEnforcesUserOnlyApprovalGate() {
        val (driver, engine, _, _) = setupDb()
        val sessionActor = ActorRef(ActorKind.SESSION, "sess1")
        val workerActor = ActorRef(ActorKind.WORKER, "worker1")

        val proposalId = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = listOf(IntentOperation.AddNode(null, "g1", "GOAL", "Goal", null, 1)),
            rationale = "Needs approval",
            runTaint = "TRUSTED",
            actor = sessionActor,
            expiresAtIso = "2026-08-06T00:00:00Z",
        )

        val sessErr = assertFailsWith<IllegalArgumentException> {
            engine.resolveProposal(proposalId, "proj1", ProposalResolution.Accept, sessionActor)
        }
        assertTrue(sessErr.message?.contains("intent.only_user_can_resolve") == true)

        val workerErr = assertFailsWith<IllegalArgumentException> {
            engine.resolveProposal(proposalId, "proj1", ProposalResolution.Reject("nope"), workerActor)
        }
        assertTrue(workerErr.message?.contains("intent.only_user_can_resolve") == true)

        assertEquals("PENDING", engine.getProposal(proposalId, "proj1")?.state)
        assertEquals(0, countRows(driver, "SELECT COUNT(*) FROM intent_nodes WHERE project_id = ?", "proj1"))
    }

    @Test
    fun testResolveProposalAcceptWithEditsModifyAndArchive() {
        val (driver, engine, _, _) = setupDb()
        insertNode(driver, "existing")
        insertNode(driver, "obsolete")

        val sessionActor = ActorRef(ActorKind.SESSION, "sess1")
        val userActor = ActorRef(ActorKind.USER, "user1")

        val proposalId = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = listOf(IntentOperation.AddNode(null, "draft", "GOAL", "Draft", null, 50)),
            rationale = "Initial draft",
            runTaint = "TRUSTED",
            actor = sessionActor,
            expiresAtIso = "2026-08-06T00:00:00Z",
        )

        val editedOps = listOf(
            IntentOperation.AddNode("existing", "new-sub", "SUB_GOAL", "Edited sub-goal", "Refined", 5),
            IntentOperation.ModifyNode(id = "existing", title = "Updated existing", priority = 10),
            IntentOperation.ArchiveNode(id = "obsolete"),
        )
        val state = engine.resolveProposal(
            proposalId = proposalId,
            projectId = "proj1",
            resolution = ProposalResolution.AcceptWithEdits(editedOps),
            actor = userActor,
        )

        assertEquals(ProposalState.ACCEPTED_WITH_EDITS, state)
        assertEquals(0, countRows(driver, "SELECT COUNT(*) FROM intent_nodes WHERE id = ?", "draft"))
        assertEquals(1, countRows(driver, "SELECT COUNT(*) FROM intent_nodes WHERE id = ? AND parent_id = ?", "new-sub", "existing"))
        assertEquals(
            1,
            countRows(
                driver,
                "SELECT COUNT(*) FROM intent_nodes WHERE id = ? AND title = 'Updated existing' AND priority = 10 AND row_version = 2",
                "existing",
            ),
        )
        assertEquals(
            1,
            countRows(driver, "SELECT COUNT(*) FROM intent_nodes WHERE id = ? AND lifecycle = 'ARCHIVED'", "obsolete"),
        )
        assertEquals(3, engine.getProposal(proposalId, "proj1")?.operations?.size)
    }

    @Test
    fun testResolveRejectSupersedeAndDoubleResolutionGuards() {
        val (driver, engine, _, _) = setupDb()
        val sessionActor = ActorRef(ActorKind.SESSION, "sess1")
        val userActor = ActorRef(ActorKind.USER, "user1")

        val propReject = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = listOf(IntentOperation.AddNode(null, "g-rej", "GOAL", "Rejected goal", null, 1)),
            rationale = "Will reject",
            runTaint = "TRUSTED",
            actor = sessionActor,
            expiresAtIso = "2026-08-06T00:00:00Z",
        )
        assertEquals(
            ProposalState.REJECTED,
            engine.resolveProposal(propReject, "proj1", ProposalResolution.Reject("Out of scope"), userActor),
        )
        assertEquals(0, countRows(driver, "SELECT COUNT(*) FROM intent_nodes WHERE id = ?", "g-rej"))
        assertEquals("REJECTED", engine.getProposal(propReject, "proj1")?.state)

        val doubleErr = assertFailsWith<IllegalStateException> {
            engine.resolveProposal(propReject, "proj1", ProposalResolution.Accept, userActor)
        }
        assertTrue(doubleErr.message?.contains("intent.proposal_already_resolved") == true)

        val propSup = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = listOf(IntentOperation.AddNode(null, "g-sup", "GOAL", "Superseded goal", null, 1)),
            rationale = "Will supersede",
            runTaint = "TRUSTED",
            actor = sessionActor,
            expiresAtIso = "2026-08-06T00:00:00Z",
        )
        assertEquals(
            ProposalState.SUPERSEDED,
            engine.resolveProposal(propSup, "proj1", ProposalResolution.Supersede("prop-newer"), userActor),
        )
    }

    @Test
    fun testExpiredProposalRejectsAcceptAndAllowsRuntimeExpire() {
        val (_, engine, _, _) = setupDb()
        val sessionActor = ActorRef(ActorKind.SESSION, "sess1")
        val userActor = ActorRef(ActorKind.USER, "user1")
        val runtimeActor = ActorRef(ActorKind.RUNTIME, "runtime")

        val expiredProp = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = listOf(IntentOperation.AddNode(null, "g-exp", "GOAL", "Expired goal", null, 1)),
            rationale = "Expired",
            runTaint = "TRUSTED",
            actor = sessionActor,
            expiresAtIso = "2026-08-05T11:00:00Z",
        )

        val expiredErr = assertFailsWith<IllegalStateException> {
            engine.resolveProposal(expiredProp, "proj1", ProposalResolution.Accept, userActor)
        }
        assertTrue(expiredErr.message?.contains("intent.proposal_expired") == true)

        assertEquals(
            ProposalState.EXPIRED,
            engine.resolveProposal(expiredProp, "proj1", ProposalResolution.Expire, runtimeActor),
        )
        val loaded = engine.getProposal(expiredProp, "proj1")
        assertEquals("EXPIRED", loaded?.state)
        assertNull(loaded?.resolvedByUserId)
    }

    @Test
    fun testResolveRollsBackAtomicallyIfGraphChangesBeforeApproval() {
        val (driver, engine, _, _) = setupDb()
        insertNode(driver, "a")
        insertNode(driver, "b")

        val sessionActor = ActorRef(ActorKind.SESSION, "sess1")
        val userActor = ActorRef(ActorKind.USER, "user1")

        // Safe when proposed.
        val proposalId = engine.propose(
            projectId = "proj1",
            runId = "run1",
            operations = listOf(IntentOperation.AddDependency(fromNodeId = "b", toNodeId = "a")),
            rationale = "b depends on a",
            runTaint = "TRUSTED",
            actor = sessionActor,
            expiresAtIso = "2026-08-06T00:00:00Z",
        )

        // Another accepted change introduces a -> b before user approves b -> a.
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_edges (id, project_id, from_node_id, to_node_id, edge_kind, created_at) " +
                "VALUES ('edge-ab', 'proj1', 'a', 'b', 'DEPENDS_ON', ?)",
            parameters = 1,
        ) {
            bindString(0, nowIso)
        }

        val err = assertFailsWith<IllegalArgumentException> {
            engine.resolveProposal(proposalId, "proj1", ProposalResolution.Accept, userActor)
        }
        assertTrue(err.message?.contains("intent.cycle_rejected") == true)
        assertEquals("PENDING", engine.getProposal(proposalId, "proj1")?.state)
        assertEquals(
            0,
            countRows(driver, "SELECT COUNT(*) FROM intent_edges WHERE from_node_id = ? AND to_node_id = ?", "b", "a"),
        )
    }
}
