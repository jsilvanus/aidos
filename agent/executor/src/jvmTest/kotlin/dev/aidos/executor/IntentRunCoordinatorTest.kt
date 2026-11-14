package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.identity.UuidV7Generator
import dev.aidos.intent.DerivedStatusComputer
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentCheckKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentGraphRepository
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SessionId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class IntentRunCoordinatorTest {

    private val nowIso = "2026-08-09T00:00:00Z"

    private fun nextId() = UuidV7Generator().next()

    private fun openProjectDb(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("intent-run-coord-test").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as JdbcSqliteDriver
    }

    private fun seedProjectAndSession(driver: JdbcSqliteDriver, projectId: String, sessionId: String) {
        driver.execute(
            null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)",
            4,
        ) {
            bindString(0, projectId)
            bindString(1, nowIso)
            bindString(2, nowIso)
            bindString(3, nowIso)
        }
        driver.execute(
            null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'worker', 'DRIVER', 'RUNNING', ?, ?, ?)",
            5,
        ) {
            bindString(0, sessionId)
            bindString(1, projectId)
            bindString(2, nowIso)
            bindString(3, nowIso)
            bindString(4, nowIso)
        }
    }

    @Test
    fun `rejects starting agent Run for HUMAN task`() {
        val driver = openProjectDb()
        val pid = nextId()
        val sid = nextId()
        seedProjectAndSession(driver, pid, sid)

        val repo = IntentGraphRepository(driver, idGen = ::nextId, nowIso = { nowIso })
        val humanNode = repo.createNode(
            projectId = pid,
            title = "Buy milk",
            type = IntentNodeType.ATOMIC_TASK,
            actorKind = IntentActorKind.HUMAN,
        )

        val coordinator = IntentRunCoordinator(driver, idGen = ::nextId, nowIso = { nowIso })
        assertFailsWith<IllegalArgumentException> {
            coordinator.startTargetedRun(
                sessionId = SessionId(sid),
                projectId = ProjectId(pid),
                nodeId = humanNode.id,
                platformProfile = PlatformProfile.MOBILE,
                availableLocality = IntentMinLocality.LOCAL_ONLY,
                deviceId = "phone-1",
                networkAvailable = false,
            )
        }
    }

    @Test
    fun `mobile run caps steps and parks in NEEDS_REVIEW until desktop CHECK confirms`() {
        val driver = openProjectDb()
        val pid = nextId()
        val sid = nextId()
        seedProjectAndSession(driver, pid, sid)

        val repo = IntentGraphRepository(driver, idGen = ::nextId, nowIso = { nowIso })
        val statusComputer = DerivedStatusComputer(driver)
        val coordinator = IntentRunCoordinator(driver, idGen = ::nextId, nowIso = { nowIso })

        val mobileLeaf = repo.createNode(
            projectId = pid,
            id = "leaf-1",
            title = "Guard null refresh token",
            type = IntentNodeType.ATOMIC_TASK,
            actorKind = IntentActorKind.AGENT,
            dispatchPolicy = NodeDispatchPolicy.AUTO_WHEN_READY,
            targetProfile = IntentTargetProfile.MOBILE,
            minLocality = IntentMinLocality.LOCAL_ONLY,
            estimatedSteps = 6,
            scopePaths = listOf("src/Auth.kt"),
        )
        val checkCrit = repo.createNode(
            projectId = pid,
            id = "crit-1",
            title = "Auth tests pass",
            type = IntentNodeType.ACCEPTANCE_CRITERION,
            checkKind = IntentCheckKind.SHELL_COMMAND,
            checkSpec = "./gradlew :auth:jvmTest",
        )
        repo.addEdge(pid, mobileLeaf.id, checkCrit.id, IntentEdgeKind.ACCEPTS)

        val readyAuto = coordinator.findReadyAutoDispatchNodes(
            projectId = ProjectId(pid),
            platformProfile = PlatformProfile.MOBILE,
            availableLocality = IntentMinLocality.LOCAL_ONLY,
        )
        assertEquals(listOf("leaf-1"), readyAuto.map { it.node.id })

        val runId = coordinator.startTargetedRun(
            sessionId = SessionId(sid),
            projectId = ProjectId(pid),
            nodeId = mobileLeaf.id,
            platformProfile = PlatformProfile.MOBILE,
            availableLocality = IntentMinLocality.LOCAL_ONLY,
            deviceId = "phone-1",
            networkAvailable = false,
            defaultMaxSteps = 24,
        )

        val maxSteps = driver.executeQuery(
            null,
            "SELECT max_steps FROM runs WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0)?.toInt() else null) },
            1,
        ) {
            bindString(0, runId.value)
        }.value
        assertEquals(6, maxSteps, "Run max_steps must be clamped to node.estimatedSteps")
        assertEquals("IN_PROGRESS", statusComputer.derive(mobileLeaf.id, pid))

        // Mark run COMPLETED in runs table and complete via coordinator on MOBILE (even with checkId, SHELL_COMMAND cannot verify on MOBILE)
        driver.execute(null, "UPDATE runs SET state = 'COMPLETED' WHERE id = ?", 1) {
            bindString(0, runId.value)
        }
        coordinator.completeTargetedRun(
            projectId = ProjectId(pid),
            runId = runId,
            nodeId = mobileLeaf.id,
            platformProfile = PlatformProfile.MOBILE,
            verifiedByCheckId = "should-not-bypass-mobile-shell-gate",
        )
        assertEquals("NEEDS_REVIEW", statusComputer.derive(mobileLeaf.id, pid))
        assertTrue(statusComputer.hasPendingPlatformChecks(mobileLeaf.id, pid))

        // Later on DESKTOP, check confirms implementation and acceptance criterion -> DONE
        coordinator.completeTargetedRun(
            projectId = ProjectId(pid),
            runId = runId,
            nodeId = mobileLeaf.id,
            platformProfile = PlatformProfile.DESKTOP,
            verifiedByCheckId = "desktop-gradle-check",
        )
        assertEquals("DONE", statusComputer.derive(mobileLeaf.id, pid))
    }
}
