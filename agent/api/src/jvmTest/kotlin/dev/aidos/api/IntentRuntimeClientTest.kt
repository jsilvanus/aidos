package dev.aidos.api

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.EffectiveDispatchPolicy
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentCheckKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentOperation
import dev.aidos.intent.NodeDispatchPolicy
import dev.aidos.intent.ProposalResolutionDecision
import dev.aidos.kernel.PlatformProfile
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class IntentRuntimeClientTest {

    private val home = Files.createTempDirectory("intent-runtime-client-test").toFile()
    private val nowIso = { "2026-08-09T00:00:00Z" }

    @AfterTest
    fun cleanup() {
        home.deleteRecursively()
    }

    private fun persistentClient(
        subDir: String,
        onOpenDriver: (SqlDriver) -> Unit = {},
    ): RealRuntimeClient {
        val root = home.resolve(subDir).apply { mkdirs() }
        val userDb = AidosStorage.openUser(DesktopPaths.userDb(root.path), "test", nowIso)
        return RealRuntimeClient().apply {
            userDriver = userDb.driver
            projectDbFactory = { projectRoot ->
                AidosStorage.openProject(DesktopPaths.stateDb(projectRoot), "test", nowIso).driver
                    .also(onOpenDriver)
            }
            projectLocker = JvmProjectLocker()
            runtimeManagedProjectsRoot = "${root.path}/.aidos/projects"
        }
    }

    private fun seedRun(driver: SqlDriver, projectId: String, runId: String) {
        val ts = nowIso()
        driver.execute(
            null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES ('sess-1', ?, 'planner', 'DRIVER', 'RUNNING', ?, ?, ?)",
            4,
        ) {
            bindString(0, projectId)
            bindString(1, ts)
            bindString(2, ts)
            bindString(3, ts)
        }
        driver.execute(
            null,
            "INSERT INTO events (id, project_id, sequence, type, schema_version, category, visibility, " +
                "timestamp, source, payload, causal_depth) VALUES ('evt-1', ?, 1, 'UserCommand', 1, 'SIGNAL', 'SESSION', ?, 'user', '{}', 0)",
            2,
        ) {
            bindString(0, projectId)
            bindString(1, ts)
        }
        driver.execute(
            null,
            "INSERT INTO runs (id, session_id, project_id, trigger_event_id, started_at, " +
                "state, user_message_summary, retry_policy_json, max_steps, platform_profile, " +
                "device_id, network_available) VALUES (?, 'sess-1', ?, 'evt-1', ?, 'RUNNING', 'plan', '{}', 16, 'DESKTOP', 'dev-1', 0)",
            3,
        ) {
            bindString(0, runId)
            bindString(1, projectId)
            bindString(2, ts)
        }
    }

    @Test
    fun `real runtime client supports life management handoffs coding proposals and snapshot sync`() = runTest {
        var projectDriver: SqlDriver? = null
        val client = persistentClient("desktop") { driver -> projectDriver = driver }

        val created = client.projects.create(
            CreateProjectRequest(
                name = "life-and-code",
                description = "Dual mode test",
                location = ProjectLocation.RuntimeManaged("life-and-code"),
            ),
        )
        assertIs<ProjectResult.Success>(created)
        val projectId = created.project.id
        val driver = projectDriver
        assertNotNull(driver)
        seedRun(driver, projectId, "run-1")

        // 1. Life management: AGENT adds milk -> HUMAN buys milk
        val addMilk = client.intents.createNode(
            CreateIntentNodeRequest(
                projectId = projectId,
                id = "add-milk",
                title = "Add milk to shopping list",
                type = IntentNodeType.ATOMIC_TASK,
                actorKind = IntentActorKind.AGENT,
                dispatchPolicy = NodeDispatchPolicy.AUTO_WHEN_READY,
            ),
        )
        val buyMilk = client.intents.createNode(
            CreateIntentNodeRequest(
                projectId = projectId,
                id = "buy-milk",
                title = "Buy milk / send shopping list as order",
                type = IntentNodeType.ATOMIC_TASK,
                actorKind = IntentActorKind.HUMAN,
            ),
        )
        client.intents.addEdge(projectId, buyMilk.id, addMilk.id, IntentEdgeKind.DEPENDS_ON)

        assertEquals(DerivedStatus.BLOCKED, client.intents.getNodeStatus(projectId, buyMilk.id)!!.derivedStatus)
        assertFalse(buyMilk.id in client.intents.listActionableForHuman(projectId).map { it.node.id })

        // Simulate agent run implementation & confirmation -> unblocks HUMAN node
        driver.execute(
            null,
            "INSERT INTO execution_edges (id, project_id, from_node_id, from_node_kind, to_node_id, to_node_kind, edge_kind, confirmed) " +
                "VALUES ('ee-1', ?, 'run-1', 'RUN', 'add-milk', 'INTENT_NODE', 'IMPLEMENTS', 0)",
            1,
        ) {
            bindString(0, projectId)
        }
        client.intents.confirmImplementation(projectId, addMilk.id, confirmedByKind = "CHECK", confirmedById = "list-verify")
        assertEquals(DerivedStatus.DONE, client.intents.getNodeStatus(projectId, addMilk.id)!!.derivedStatus)
        assertEquals(DerivedStatus.NOT_STARTED, client.intents.getNodeStatus(projectId, buyMilk.id)!!.derivedStatus)
        assertTrue(buyMilk.id in client.intents.listActionableForHuman(projectId).map { it.node.id })

        client.intents.completeHumanNode(projectId, buyMilk.id, "user-1", "Purchased 2L")
        assertEquals(DerivedStatus.DONE, client.intents.getNodeStatus(projectId, buyMilk.id)!!.derivedStatus)

        // 2. Coding management: AI proposes atomic mobile task + SHELL_COMMAND criterion, user accepts
        val proposalId = client.intents.propose(
            CreateIntentProposalRequest(
                projectId = projectId,
                runId = "run-1",
                rationale = "Extract atomic mobile fix",
                expiresAtIso = "2026-09-01T00:00:00Z",
                operations = listOf(
                    IntentOperation.AddNode(
                        parentId = null,
                        id = "mobile-fix",
                        type = "ATOMIC_TASK",
                        title = "Fix null refresh token",
                        description = "Scoped leaf task",
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
                        parentId = null,
                        id = "crit-auth",
                        type = "ACCEPTANCE_CRITERION",
                        title = "Auth tests pass",
                        description = null,
                        priority = 10,
                        checkKind = "SHELL_COMMAND",
                        checkSpec = "./gradlew :auth:jvmTest",
                    ),
                    IntentOperation.AddEdge(
                        fromNodeId = "mobile-fix",
                        toNodeId = "crit-auth",
                        edgeKind = IntentEdgeKind.ACCEPTS.name,
                    ),
                ),
            ),
        )
        assertEquals(1, client.intents.listProposals(projectId, state = "PENDING").size)

        val resolved = client.intents.resolveProposal(
            projectId = projectId,
            proposalId = proposalId,
            decision = ProposalResolutionDecision.ACCEPTED,
            resolvedByUserId = "user-1",
        )
        assertEquals("ACCEPTED", resolved.state)

        val mobileReady = client.intents.listReadyForAgent(
            projectId = projectId,
            profile = PlatformProfile.MOBILE,
            locality = IntentMinLocality.LOCAL_ONLY,
            projectDefaultPolicy = EffectiveDispatchPolicy.MANUAL_START,
            onlyAutoDispatch = true,
        )
        assertEquals(listOf("mobile-fix"), mobileReady.map { it.node.id })
        assertTrue(mobileReady.single().pendingPlatformChecks)

        // 3. Git snapshot export & reconciliation into second client instance
        val snapshotJson = client.intents.exportSnapshotJson(projectId)
        assertTrue(snapshotJson.contains("mobile-fix"))

        val secondClient = persistentClient("mobile-mirror")
        var mirrorDriver: SqlDriver? = null
        secondClient.projectDbFactory = { projectRoot ->
            AidosStorage.openProject(DesktopPaths.stateDb(projectRoot), "test", nowIso).driver
                .also { mirrorDriver = it }
        }
        val mirrorCreated = secondClient.projects.create(
            CreateProjectRequest(
                name = "mirror",
                description = "Mirror project",
                location = ProjectLocation.RuntimeManaged("mirror"),
            ),
        )
        assertIs<ProjectResult.Success>(mirrorCreated)
        // Reconcile into a project matching snapshot projectId by seeding project row or matching id
        val mirrorPid = mirrorCreated.project.id
        val normalizedSnapshot = snapshotJson.replace("\"projectId\": \"$projectId\"", "\"projectId\": \"$mirrorPid\"")
        val reconcile = secondClient.intents.importAndReconcileSnapshot(mirrorPid, normalizedSnapshot)
        assertEquals(4, reconcile.addedNodes)
        assertNotNull(mirrorDriver)
        assertEquals(
            "Fix null refresh token",
            secondClient.intents.getNodeStatus(mirrorPid, "mobile-fix")!!.node.title,
        )
    }

    @Test
    fun `mock runtime client supports intent operations in memory`() = runTest {
        val mock = MockRuntimeClient()
        val p = (mock.projects.create(CreateProjectRequest("m", "d", ProjectLocation.RuntimeManaged("m"))) as ProjectResult.Success).project.id
        val node = mock.intents.createNode(
            CreateIntentNodeRequest(
                projectId = p,
                id = "n1",
                title = "Human check",
                actorKind = IntentActorKind.HUMAN,
                checkKind = IntentCheckKind.NONE,
            ),
        )
        assertEquals(1, mock.intents.listActionableForHuman(p).size)
        mock.intents.completeHumanNode(p, node.id, "user-1")
        assertEquals(0, mock.intents.listActionableForHuman(p).size)
    }
}
