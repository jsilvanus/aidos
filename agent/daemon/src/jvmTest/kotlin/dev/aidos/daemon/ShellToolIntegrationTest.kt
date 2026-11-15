package dev.aidos.daemon

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.broker.AuditLog
import dev.aidos.broker.ToolBroker
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.UserId
import dev.aidos.shell.ShellTool
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC-0033 through the real broker and capability manager: no grant, no shell; a grant's scope is what the tool enforces. */
class ShellToolIntegrationTest {
    private val now = "2026-11-15T00:00:00Z"
    private val projectId = "01234567-89ab-7def-8abc-000000000333"

    private class Fixture(val root: File, val driver: JdbcSqliteDriver, val mgr: SqliteCapabilityManager, val broker: ToolBroker)

    private fun fixture(): Fixture {
        val root = Files.createTempDirectory("shell-int").toFile()
        File(root, "sub").mkdirs()
        val driver = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "t") { now }.driver as JdbcSqliteDriver
        driver.execute(null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 't', '/', 'generic', '$now', '$now', '$now')", 1) { bindString(0, projectId) }
        driver.execute(null, "INSERT OR IGNORE INTO project_revocation_epoch (project_id, epoch) VALUES (?, 0)", 1) { bindString(0, projectId) }
        val mgr = SqliteCapabilityManager(driver, UuidV7Generator()) { now }
        var n = 0
        val broker = ToolBroker(mgr, AuditLog(driver), { "id-${++n}" }, { now }, projectIdResolver = { mgr.projectIdForCapability(it) ?: "" })
        broker.register(ShellTool(root))
        return Fixture(root, driver, mgr, broker)
    }

    private suspend fun grant(f: Fixture, dir: String, allowed: List<String>?): CapabilityId = f.mgr.grant(
        subjectId = "session-1",
        subjectKind = SubjectKind.SESSION,
        permission = Permission.SHELL_EXEC,
        scope = CapabilityScope.Shell(ProjectId(projectId), dir, allowed),
        constraints = CapabilityConstraints(),
        expiresAt = null,
        grantedBy = UserId("user-1"),
    ).getOrThrow().id

    private fun call(cap: CapabilityId?, command: String, cwd: String? = null) = ToolCall(
        callId = "c1",
        toolName = "shell:exec",
        arguments = buildJsonObject {
            put("command", JsonPrimitive(command))
            cwd?.let { put("working_directory", JsonPrimitive(it)) }
        },
        capabilityId = cap,
    )

    @Test
    fun `a granted capability runs the command, and the scope it was granted with is the scope enforced`(): Unit = runBlocking {
        val f = fixture()
        val cap = grant(f, "sub", null)
        val ok = f.broker.invoke("session-1", call(cap, "echo through-broker"), TrustLevel.TRUSTED)
        assertEquals(ToolOutcome.Ok, ok.outcome)
        assertTrue((ok.content.single() as dev.aidos.kernel.ContentBlock.Text).text.contains("through-broker"))

        val escape = f.broker.invoke("session-1", call(cap, "echo nope", cwd = ".."), TrustLevel.TRUSTED)
        assertTrue(escape.outcome is ToolOutcome.Failed, "the grant was for sub/, not the project root")
    }

    @Test
    fun `no capability means no shell`(): Unit = runBlocking {
        val f = fixture()
        val r = f.broker.invoke("session-1", call(null, "echo nope"), TrustLevel.TRUSTED)
        assertFalse(r.outcome is ToolOutcome.Ok)
    }

    @Test
    fun `an untrusted run cannot use the shell`(): Unit = runBlocking {
        val f = fixture()
        val cap = grant(f, ".", null)
        val r = f.broker.invoke("session-1", call(cap, "echo nope"), TrustLevel.UNTRUSTED)
        assertTrue(r.outcome is ToolOutcome.Denied, "RFC-0027 attenuates SHELL_EXEC for tainted runs: ${r.outcome}")
    }

    @Test
    fun `the model is only offered shell on profiles that have one`() {
        val f = fixture()
        fun offered(p: PlatformProfile) = f.broker.descriptorsFor("session-1", p, false).any { it.name == "shell:exec" }
        assertTrue(offered(PlatformProfile.DESKTOP))
        assertTrue(offered(PlatformProfile.HEADLESS_SERVER))
        assertFalse(offered(PlatformProfile.MOBILE))
    }
}
