package dev.aidos.broker

import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.Permission
import dev.aidos.kernel.Preview
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.ResourceHandle
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
import kotlin.test.Test
import kotlin.test.assertEquals
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
}

// ─── Test double ─────────────────────────────────────────────────────────────

/** A trivial read tool that echoes its arguments back. Used only in tests. */
private object EchoTool : Tool {
    override val id = "echo-tool"
    override val version = "0.1.0"

    override fun operations() = listOf(
        ToolDescriptor(
            name = "echo",
            title = "Echo",
            description = "Returns its arguments",
            inputSchema = kotlinx.serialization.json.buildJsonObject { },
            effect = EffectKind.Read,
            requiredPermission = Permission.FS_READ,
            recoveryClass = RecoveryClass.PURE,
            availability = dev.aidos.kernel.ToolAvailability(setOf(dev.aidos.kernel.PlatformProfile.DESKTOP, dev.aidos.kernel.PlatformProfile.HEADLESS_SERVER, dev.aidos.kernel.PlatformProfile.MOBILE), dev.aidos.kernel.AvailabilityTier.UNIVERSAL),
        )
    )

    override suspend fun execute(handle: ResourceHandle, operation: String, arguments: JsonObject): ToolCallResult =
        ToolCallResult("", ToolOutcome.Ok, listOf(ContentBlock.Text("echo: $arguments")), TrustLevel.TRUSTED)

    override suspend fun preview(handle: ResourceHandle, operation: String, arguments: JsonObject): Result<Preview> =
        Result.failure(UnsupportedOperationException("no preview for read"))

    override suspend fun cancel(operationId: String) = Unit
}
