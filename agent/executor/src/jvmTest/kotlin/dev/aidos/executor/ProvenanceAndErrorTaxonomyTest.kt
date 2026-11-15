package dev.aidos.executor

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.broker.AuditLog
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ContentKind
import dev.aidos.kernel.ContentNode
import dev.aidos.kernel.ContentNodeId
import dev.aidos.kernel.ContentNodeState
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.EgressEligibility
import dev.aidos.kernel.EventId
import dev.aidos.kernel.InferenceRouter
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.MutabilityPolicy
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RoutingContext
import dev.aidos.kernel.RoutingDecision
import dev.aidos.kernel.SensitivityLevel
import dev.aidos.kernel.SessionId
import dev.aidos.kernel.StopReason
import dev.aidos.kernel.StorageLocation
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.TrustLevel
import dev.aidos.prompt.PromptAssembler
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import dev.aidos.storage.SqliteContentNodeStore
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * RFC-0024 + RFC-0025 gap-closure test:
 * `AgentLoopTaskRunner` persists `attempts.prompt_package_json` on `MODEL_CALL` with
 * `SqliteContentNodeStore` wired into `PromptAssembler`.
 */
class ProvenanceAndErrorTaxonomyTest {

    private val nowIso = "2026-08-05T00:00:00Z"
    private fun nextId() = UuidV7Generator().next()

    private fun openDriver(): JdbcSqliteDriver {
        val root = Files.createTempDirectory("rfc0025-test").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as JdbcSqliteDriver
    }

    @Test
    fun `AgentLoopTaskRunner persists prompt_package_json for MODEL_CALL`(): Unit = runBlocking {
        val driver = openDriver()
        val projectId = nextId()
        val sessionId = nextId()
        val evtId = nextId()
        driver.execute(
            null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)",
            4,
        ) { bindString(0, projectId); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso) }
        driver.execute(
            null,
            "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) " +
                "VALUES (?, ?, 'driver', 'DRIVER', 'RUNNING', ?, ?, ?)",
            5,
        ) { bindString(0, sessionId); bindString(1, projectId); bindString(2, nowIso); bindString(3, nowIso); bindString(4, nowIso) }
        val events = EventStore(driver)
        events.publish(
            id = evtId,
            projectId = projectId,
            type = EventTypes.USER_COMMAND,
            source = "user",
            nowIso = nowIso,
        )

        val contentStore = SqliteContentNodeStore(driver)
        val nodeId = ContentNodeId(nextId())
        contentStore.create(
            ContentNode(
                id = nodeId,
                projectId = ProjectId(projectId),
                kind = ContentKind.ARCHITECTURE_DOCUMENT,
                name = "arch.md",
                description = null,
                mutabilityPolicy = MutabilityPolicy.VERSIONED,
                sensitivityLevel = SensitivityLevel.PUBLIC,
                egressEligibility = EgressEligibility.ELIGIBLE,
                trustLevel = TrustLevel.PROJECT,
                storageLocation = StorageLocation.FilesystemPath("arch.md", true),
                contentHash = "abc",
                contentType = "text/markdown",
                sizeBytes = 10L,
                createdAt = Instant.parse(nowIso),
                createdByKind = ActorKind.USER,
                createdById = "user-1",
                updatedAt = Instant.parse(nowIso),
                updatedByKind = null,
                updatedById = null,
                contentVersion = 1,
                rowVersion = 1,
                state = ContentNodeState.ACTIVE,
                tags = emptyList(),
            )
        )

        val runCreator = RunCreator(driver, ::nextId) { nowIso }
        val runId = runCreator.createForUserMessage(
            SessionId(sessionId), ProjectId(projectId), EventId(evtId),
            "hello", PlatformProfile.DESKTOP, "dev-1", false,
        )

        val adapter = object : ModelAdapter {
            override val providerId = "test"
            override val modelId = "test-local"
            override val modelVersion = "1.0"
            override val contextWindow = 4096
            override val isLocal = true
            override fun supportsNativeToolCalls() = false
            override suspend fun invoke(request: ModelRequest) = Result.success(
                ModelResponse(
                    outputs = listOf(TextOutput("done")),
                    stopReason = StopReason.END_TURN,
                    usage = null,
                    model = null,
                )
            )
        }
        val router = object : InferenceRouter {
            override suspend fun select(kind: ModelKind, context: RoutingContext) = RoutingDecision.Local(adapter)
        }
        val broker = object : EffectBroker {
            override fun register(tool: Tool) {}
            override fun descriptorsFor(subjectId: String, profile: PlatformProfile, networkAvailable: Boolean) = emptyList<ToolDescriptor>()
            override suspend fun preview(subjectId: String, call: ToolCall): Result<Preview> = error("no tools")
            override suspend fun invoke(subjectId: String, call: ToolCall, runTaint: TrustLevel): ToolCallResult = error("no tools")
            override suspend fun cancel(callId: String) {}
        }
        val audit = AuditLog(driver)
        val runner = AgentLoopTaskRunner(
            driver = driver,
            audit = audit,
            idGen = ::nextId,
            nowIso = { nowIso },
            router = router,
            assembler = PromptAssembler(contentNodeLookup = { id -> contentStore.get(id) }),
            broker = broker,
            subjectId = sessionId,
        )
        val executor = SqliteExecutor(driver, audit, events, ::nextId, { nowIso }, runner)
        executor.drive(runId)

        val pkgJson = driver.executeQuery(
            null,
            "SELECT a.prompt_package_json FROM attempts a JOIN tasks t ON a.task_id = t.id WHERE t.run_id = ? AND a.prompt_package_json IS NOT NULL LIMIT 1",
            { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            1,
        ) { bindString(0, runId.value) }.value
        assertNotNull(pkgJson, "attempts.prompt_package_json must be populated on MODEL_CALL")
        assertTrue(pkgJson.contains("\"modelId\":\"test-local\""))
    }
}
