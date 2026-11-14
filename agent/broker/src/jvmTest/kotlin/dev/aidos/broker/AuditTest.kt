package dev.aidos.broker

import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.CapabilityCheckResult
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityManager
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.MutationScope
import dev.aidos.kernel.Operation
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolAvailability
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.UserId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * M4 done-when: every validate and every effect writes one audit row naming the subject, the
 * capability actually exercised, and the outcome. An effect with no audit row is a test failure,
 * enforced by the broker harness here, not by review (RFC-0003, RFC-0037).
 */
class AuditTest {

    private val counter = AtomicInteger(0)
    private val nowIso = "2026-08-05T00:00:00Z"

    private fun openProjectDriver() = run {
        val root = Files.createTempDirectory("audit-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun nextId() = "id-${counter.incrementAndGet().toString().padStart(4, '0')}"

    private fun setup(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver): Triple<
        SqliteCapabilityManager, AuditLog, ToolBroker
    > {
        val ids = UuidV7Generator()
        val mgr = SqliteCapabilityManager(driver, ids) { nowIso }
        val audit = AuditLog(driver)
        // Resolve projectId from the capability store so audit rows have correct context.
        val broker = ToolBroker(mgr, audit, { nextId() }, { nowIso },
            projectIdResolver = { capId ->
                mgr.projectIdForCapability(capId) ?: ""
            }
        )
        return Triple(mgr, audit, broker)
    }

    private fun seedProject(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        projectId: String,
    ) {
        driver.execute(null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', '$nowIso', '$nowIso', '$nowIso')", 1
        ) { bindString(0, projectId) }
        driver.execute(null,
            "INSERT OR IGNORE INTO project_revocation_epoch (project_id, epoch) VALUES (?, 0)", 1
        ) { bindString(0, projectId) }
    }

    // ─── Broker harness: every effect writes an audit row ────────────────────

    @Test
    fun `every tool invocation writes audit rows`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val (mgr, audit, broker) = setup(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000200"
        seedProject(driver, projectId)

        val cap = mgr.grant(
            subjectId = "session-x",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        broker.register(EchoTool)

        val before = audit.countForProject(projectId)

        broker.invoke(
            subjectId = "session-x",
            call = ToolCall(
                callId = "call-1",
                toolName = "echo",
                arguments = kotlinx.serialization.json.buildJsonObject { },
                capabilityId = cap.id,
            ),
            runTaint = TrustLevel.TRUSTED,
        )

        val after = audit.countForProject(projectId)
        // Expect: CapabilityGranted (from mgr.grant) + ToolInvoked + ToolCompleted = 3 rows.
        assertTrue(after > before, "Expected audit rows after invocation but count did not increase: $before → $after")
    }

    @Test
    fun `invoke with no capability returns failed outcome`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val (_, _, broker) = setup(driver)

        broker.register(EchoTool)

        val result = broker.invoke(
            subjectId = "session-y",
            call = ToolCall(
                callId = "call-2",
                toolName = "echo",
                arguments = kotlinx.serialization.json.buildJsonObject { },
                capabilityId = null,
            ),
            runTaint = TrustLevel.TRUSTED,
        )

        // A call with no capability must fail — the broker never searches for authority.
        assertTrue(result.outcome is ToolOutcome.Failed, "Expected failed outcome for missing capability")
    }

    @Test
    fun `audit rows name the subject the capability and the outcome`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val (mgr, audit, broker) = setup(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000201"
        seedProject(driver, projectId)

        val cap = mgr.grant(
            subjectId = "session-z",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        broker.register(EchoTool)
        broker.invoke(
            subjectId = "session-z",
            call = ToolCall("call-3", "echo", kotlinx.serialization.json.buildJsonObject { }, cap.id),
            runTaint = TrustLevel.TRUSTED,
        )

        val rows = audit.rowsForProject(projectId)
        val toolRow = rows.firstOrNull { it.kind == "ToolInvoked" }
            ?: error("No ToolInvoked audit row found")

        assertEquals("session-z", toolRow.actorId, "audit row must name the subject")
        assertEquals(cap.id.value, toolRow.capabilityId, "audit row must name the capability")
        assertTrue(rows.any { it.kind == "ToolCompleted" || it.kind == "ToolFailed" },
            "audit row must name the outcome (ToolCompleted or ToolFailed)")
    }

    @Test
    fun `worker invoking a tool is attributed to ActorKind WORKER with authentic deviceId`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val ids = UuidV7Generator()
        val realDeviceId = "device-pixel-pro-7"
        val mgr = SqliteCapabilityManager(driver, ids, { nowIso }, deviceId = realDeviceId)
        val audit = AuditLog(driver, realDeviceId)
        val broker = ToolBroker(mgr, audit, { nextId() }, { nowIso },
            projectIdResolver = { capId -> mgr.projectIdForCapability(capId) ?: "" }
        )
        val projectId = "01234567-89ab-7def-8abc-000000000202"
        seedProject(driver, projectId)

        val cap = mgr.grant(
            subjectId = "worker-subtask-1",
            subjectKind = dev.aidos.kernel.SubjectKind.WORKER,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        broker.register(EchoTool)
        broker.invoke(
            subjectId = "worker-subtask-1",
            call = ToolCall("call-worker", "echo", kotlinx.serialization.json.buildJsonObject { }, cap.id),
            runTaint = TrustLevel.TRUSTED,
        )

        val rows = audit.rowsForProject(projectId)
        val toolInvokedRow = rows.first { it.kind == "ToolInvoked" }
        assertEquals(ActorKind.WORKER.name, toolInvokedRow.actorKind)
        assertEquals(ActorKind.WORKER, toolInvokedRow.actor.kind)
        assertEquals("worker-subtask-1", toolInvokedRow.actorId)
        assertEquals(realDeviceId, toolInvokedRow.deviceId)

        val toolCompletedRow = rows.first { it.kind == "ToolCompleted" }
        assertEquals(ActorKind.WORKER.name, toolCompletedRow.actorKind)
        assertEquals(realDeviceId, toolCompletedRow.deviceId)

        // Grant audit row also records authentic deviceId
        val grantRow = rows.first { it.kind == "CapabilityGranted" }
        assertEquals(realDeviceId, grantRow.deviceId)
        assertEquals("USER", grantRow.actorKind)
    }

    @Test
    fun `mcp server subject is attributed to ActorKind MCP_SERVER`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val ids = UuidV7Generator()
        val realDeviceId = "device-desktop-workstation"
        val mgr = SqliteCapabilityManager(driver, ids, { nowIso }, deviceId = realDeviceId)
        val audit = AuditLog(driver, realDeviceId)
        val broker = ToolBroker(mgr, audit, { nextId() }, { nowIso },
            projectIdResolver = { capId -> mgr.projectIdForCapability(capId) ?: "" }
        )
        val projectId = "01234567-89ab-7def-8abc-000000000203"
        seedProject(driver, projectId)

        val cap = mgr.grant(
            subjectId = "mcp:filesystem-server",
            subjectKind = dev.aidos.kernel.SubjectKind.MCP_SERVER,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        broker.register(EchoTool)
        broker.invoke(
            subjectId = "mcp:filesystem-server",
            call = ToolCall("call-mcp", "echo", kotlinx.serialization.json.buildJsonObject { }, cap.id),
            runTaint = TrustLevel.TRUSTED,
        )

        val rows = audit.rowsForProject(projectId)
        val toolInvokedRow = rows.first { it.kind == "ToolInvoked" }
        assertEquals(ActorKind.MCP_SERVER.name, toolInvokedRow.actorKind)
        assertEquals(ActorKind.MCP_SERVER, toolInvokedRow.actor.kind)
        assertEquals("mcp:filesystem-server", toolInvokedRow.actorId)
        assertEquals(realDeviceId, toolInvokedRow.deviceId)
    }

    @Test
    fun `auditAndReturn persists audit rows with valid projectId on schema failure and capability denial`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val (mgr, audit, broker) = setup(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000204"
        seedProject(driver, projectId)

        val cap = mgr.grant(
            subjectId = "session-audit",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        broker.register(SchemaReadTool)

        // 1. Invalid arguments (missing required 'path'): auditAndReturn writes ToolFailed with projectId.
        val invalidResult = broker.invoke(
            subjectId = "session-audit",
            call = ToolCall("call-bad-args", "schema_read", buildJsonObject {}, cap.id),
            runTaint = TrustLevel.TRUSTED,
        )
        val failed = assertIs<ToolOutcome.Failed>(invalidResult.outcome)
        assertEquals("arguments.invalid", failed.error.code)

        // 2. Missing capabilityId on a call from a subject with a single project: writes CapabilityDenied.
        val missingCapResult = broker.invoke(
            subjectId = "session-audit",
            call = ToolCall("call-no-cap", "schema_read", buildJsonObject { put("path", "a.txt") }, null),
            runTaint = TrustLevel.TRUSTED,
        )
        assertIs<ToolOutcome.Failed>(missingCapResult.outcome)

        // 3. Revoked capability: auditAndReturn writes CapabilityDenied with resolved projectId.
        mgr.revoke(cap.id, "user-1")
        val revokedResult = broker.invoke(
            subjectId = "session-audit",
            call = ToolCall("call-revoked", "schema_read", buildJsonObject { put("path", "a.txt") }, cap.id),
            runTaint = TrustLevel.TRUSTED,
        )
        assertIs<ToolOutcome.Denied>(revokedResult.outcome)

        val rows = audit.rowsForProject(projectId)
        assertTrue(rows.any { it.kind == "ToolFailed" && it.subjectRef == "schema_read" && it.capabilityId == cap.id.value },
            "expected ToolFailed row for schema failure: $rows")
        assertTrue(rows.any { it.kind == "CapabilityDenied" && it.subjectRef == "schema_read" && it.capabilityId == null },
            "expected CapabilityDenied row for missing capability: $rows")
        assertTrue(rows.any { it.kind == "CapabilityDenied" && it.subjectRef == "schema_read" && it.capabilityId == cap.id.value },
            "expected CapabilityDenied row for revoked capability: $rows")
    }

    @Test
    fun `broker resolves projectId from active capability even when projectIdResolver is omitted`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val ids = UuidV7Generator()
        val mgr = SqliteCapabilityManager(driver, ids) { nowIso }
        val audit = AuditLog(driver)
        val broker = ToolBroker(mgr, audit, { nextId() }, { nowIso })
        val projectId = "01234567-89ab-7def-8abc-000000000205"
        seedProject(driver, projectId)

        val cap = mgr.grant(
            subjectId = "session-default-resolver",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        broker.register(EchoTool)
        val result = broker.invoke(
            subjectId = "session-default-resolver",
            call = ToolCall("call-default-proj", "echo", buildJsonObject {}, cap.id),
            runTaint = TrustLevel.TRUSTED,
        )
        assertIs<ToolOutcome.Ok>(result.outcome)

        val rows = audit.rowsForProject(projectId)
        assertTrue(rows.any { it.kind == "ToolInvoked" && it.capabilityId == cap.id.value })
        assertTrue(rows.any { it.kind == "ToolCompleted" && it.capabilityId == cap.id.value })
    }

    @Test
    fun `openHandle failures record ToolFailed audit rows in both mutate preview and execute paths`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val ids = UuidV7Generator()
        val mgr = SqliteCapabilityManager(driver, ids) { nowIso }
        val audit = AuditLog(driver)
        val failingMgr = object : CapabilityManager by mgr {
            override suspend fun openHandle(subjectId: String, capabilityId: CapabilityId): Result<ResourceHandle> =
                Result.failure(IllegalStateException("handle open unavailable"))
        }
        val broker = ToolBroker(
            failingMgr, audit, { nextId() }, { nowIso },
            projectIdResolver = { capId -> mgr.projectIdForCapability(capId) ?: "" },
        )
        val projectId = "01234567-89ab-7def-8abc-000000000206"
        seedProject(driver, projectId)

        val writeCap = mgr.grant(
            subjectId = "session-open-fail",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_WRITE,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        val readCap = mgr.grant(
            subjectId = "session-open-fail",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        broker.register(EchoTool)
        broker.register(MutateDummyTool)

        // Case 1: Mutate tool fails openHandle at Step 4 preview -> ToolFailed row recorded.
        val mutateRes = broker.invoke(
            subjectId = "session-open-fail",
            call = ToolCall("call-mutate-fail", "mutate_dummy", buildJsonObject {}, writeCap.id),
            runTaint = TrustLevel.TRUSTED,
        )
        val mutateErr = assertIs<ToolOutcome.Failed>(mutateRes.outcome)
        assertEquals("capability.open_failed", mutateErr.error.code)

        // Case 2: Read tool fails openHandle at Step 7 execute (after ToolInvoked) -> ToolFailed row recorded.
        val readRes = broker.invoke(
            subjectId = "session-open-fail",
            call = ToolCall("call-read-fail", "echo", buildJsonObject {}, readCap.id),
            runTaint = TrustLevel.TRUSTED,
        )
        val readErr = assertIs<ToolOutcome.Failed>(readRes.outcome)
        assertEquals("capability.open_failed", readErr.error.code)

        val rows = audit.rowsForProject(projectId)
        assertTrue(rows.any { it.kind == "ToolFailed" && it.subjectRef == "mutate_dummy" && it.capabilityId == writeCap.id.value },
            "expected ToolFailed row for mutate openHandle failure: $rows")
        assertTrue(rows.any { it.kind == "ToolInvoked" && it.subjectRef == "echo" && it.capabilityId == readCap.id.value },
            "expected ToolInvoked row before read openHandle failure: $rows")
        assertTrue(rows.any { it.kind == "ToolFailed" && it.subjectRef == "echo" && it.capabilityId == readCap.id.value },
            "expected ToolFailed completion row for read openHandle failure: $rows")
    }

    @Test
    fun `capability permission mismatch is denied with SCOPE_MISMATCH and audited`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val (mgr, audit, broker) = setup(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000207"
        seedProject(driver, projectId)

        val readCap = mgr.grant(
            subjectId = "session-mismatch",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        broker.register(MutateDummyTool)
        val res = broker.invoke(
            subjectId = "session-mismatch",
            call = ToolCall("call-mismatch", "mutate_dummy", buildJsonObject {}, readCap.id),
            runTaint = TrustLevel.TRUSTED,
        )
        val denied = assertIs<ToolOutcome.Denied>(res.outcome)
        assertEquals(dev.aidos.kernel.DenialReason.SCOPE_MISMATCH, denied.reason)
        val rows = audit.rowsForProject(projectId)
        assertTrue(rows.any { it.kind == "CapabilityDenied" && it.subjectRef == "mutate_dummy" && it.capabilityId == readCap.id.value })
    }

    @Test
    fun `openHandle failure with no project context handles missing project gracefully without FK error`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val ids = UuidV7Generator()
        val realMgr = SqliteCapabilityManager(driver, ids) { nowIso }
        val audit = AuditLog(driver)
        val projectlessMgr = object : CapabilityManager by realMgr {
            override suspend fun validate(
                subjectId: String,
                capabilityId: CapabilityId,
                operation: Operation<*>,
                runTaint: TrustLevel,
            ): CapabilityCheckResult = CapabilityCheckResult.Allowed

            override suspend fun openHandle(subjectId: String, capabilityId: CapabilityId): Result<ResourceHandle> =
                Result.failure(IllegalStateException("no backing handle"))
        }
        val broker = ToolBroker(projectlessMgr, audit, { nextId() }, { nowIso })
        broker.register(EchoTool)

        val result = broker.invoke(
            subjectId = "session-orphan",
            call = ToolCall("call-orphan", "echo", buildJsonObject {}, CapabilityId("cap-missing")),
            runTaint = TrustLevel.TRUSTED,
        )
        val err = assertIs<ToolOutcome.Failed>(result.outcome)
        assertEquals("capability.open_failed", err.error.code)
    }
}

// ─── Test double ─────────────────────────────────────────────────────────────

private val ALL_PROFILES_AVAILABILITY = ToolAvailability(
    setOf(PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER, PlatformProfile.MOBILE),
    dev.aidos.kernel.AvailabilityTier.UNIVERSAL,
)

/** A trivial read tool that echoes its arguments back. Used only in tests. */
private object EchoTool : Tool {
    override val id = "echo-tool"
    override val version = "0.1.0"

    override fun operations() = listOf(
        ToolDescriptor(
            name = "echo",
            title = "Echo",
            description = "Returns its arguments",
            inputSchema = buildJsonObject { },
            effect = EffectKind.Read,
            requiredPermission = Permission.FS_READ,
            recoveryClass = RecoveryClass.PURE,
            availability = ALL_PROFILES_AVAILABILITY,
        )
    )

    override suspend fun execute(handle: ResourceHandle, operation: String, arguments: JsonObject): ToolCallResult =
        ToolCallResult("", ToolOutcome.Ok, listOf(ContentBlock.Text("echo: $arguments")), TrustLevel.TRUSTED)

    override suspend fun preview(handle: ResourceHandle, operation: String, arguments: JsonObject): Result<Preview> =
        Result.failure(UnsupportedOperationException("no preview for read"))

    override suspend fun cancel(operationId: String) = Unit
}

private object SchemaReadTool : Tool {
    override val id = "schema-read-tool"
    override val version = "0.1.0"

    override fun operations() = listOf(
        ToolDescriptor(
            name = "schema_read",
            title = "Schema Read",
            description = "Requires path",
            inputSchema = buildJsonObject {
                put("type", "object")
                put("required", buildJsonArray { add(JsonPrimitive("path")) })
                put("properties", buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                })
            },
            effect = EffectKind.Read,
            requiredPermission = Permission.FS_READ,
            recoveryClass = RecoveryClass.PURE,
            availability = ALL_PROFILES_AVAILABILITY,
        )
    )

    override suspend fun execute(handle: ResourceHandle, operation: String, arguments: JsonObject): ToolCallResult =
        ToolCallResult("", ToolOutcome.Ok, listOf(ContentBlock.Text("ok")), TrustLevel.TRUSTED)

    override suspend fun preview(handle: ResourceHandle, operation: String, arguments: JsonObject): Result<Preview> =
        Result.failure(UnsupportedOperationException("no preview for read"))

    override suspend fun cancel(operationId: String) = Unit
}

private object MutateDummyTool : Tool {
    override val id = "mutate-dummy-tool"
    override val version = "0.1.0"

    override fun operations() = listOf(
        ToolDescriptor(
            name = "mutate_dummy",
            title = "Mutate Dummy",
            description = "Dummy mutation tool",
            inputSchema = buildJsonObject { },
            effect = EffectKind.Mutate(MutationScope.IN_PROJECT),
            requiredPermission = Permission.FS_WRITE,
            recoveryClass = RecoveryClass.IDEMPOTENT,
            availability = ALL_PROFILES_AVAILABILITY,
        )
    )

    override suspend fun execute(handle: ResourceHandle, operation: String, arguments: JsonObject): ToolCallResult =
        ToolCallResult("", ToolOutcome.Ok, listOf(ContentBlock.Text("mutated")), TrustLevel.TRUSTED)

    override suspend fun preview(handle: ResourceHandle, operation: String, arguments: JsonObject): Result<Preview> =
        Result.success(Preview.Description("dummy mutate"))

    override suspend fun cancel(operationId: String) = Unit
}
