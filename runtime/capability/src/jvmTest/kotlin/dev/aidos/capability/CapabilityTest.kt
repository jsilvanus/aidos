package dev.aidos.capability

import dev.aidos.kernel.Capability
import dev.aidos.kernel.CapabilityCheckResult
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.DenialReason
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.Operation
import dev.aidos.kernel.Permission
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.RelPath
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.UserId
import dev.aidos.identity.UuidV7Generator
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * M3 done-when:
 * 1. No input to RelPath.of escapes its root (property test).
 * 2. Revocation by epoch invalidates outstanding handles within one step.
 * 3. validate() refuses when Run taint exceeds the grant's ceiling.
 */
class CapabilityTest {

    private fun openProjectDriver(): app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver {
        val root = Files.createTempDirectory("capability-test").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { "2026-08-05T00:00:00Z" }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun manager(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver): SqliteCapabilityManager {
        var t = "2026-08-05T00:00:00Z"
        return SqliteCapabilityManager(driver, UuidV7Generator()) { t }
    }

    // ─── 1. RelPath property test ─────────────────────────────────────────────

    @Test
    fun `RelPath rejects absolute unix path`() {
        assertTrue(RelPath.of("/etc/passwd").isFailure)
    }

    @Test
    fun `RelPath rejects absolute windows path`() {
        assertTrue(RelPath.of("\\Windows\\System32").isFailure)
    }

    @Test
    fun `RelPath rejects drive-qualified path`() {
        assertTrue(RelPath.of("C:\\foo").isFailure)
    }

    @Test
    fun `RelPath rejects empty path`() {
        assertTrue(RelPath.of("").isFailure)
    }

    @Test
    fun `RelPath rejects NUL byte`() {
        assertTrue(RelPath.of("foo\u0000bar").isFailure)
    }

    @Test
    fun `RelPath rejects single dot-dot`() {
        assertTrue(RelPath.of("..").isFailure)
    }

    @Test
    fun `RelPath rejects dot-dot at start of path`() {
        assertTrue(RelPath.of("../etc/passwd").isFailure)
    }

    @Test
    fun `RelPath rejects dot-dot in middle of path`() {
        assertTrue(RelPath.of("foo/../../../etc/passwd").isFailure)
    }

    @Test
    fun `RelPath rejects dot-dot with backslash separators`() {
        assertTrue(RelPath.of("foo\\..\\bar").isFailure)
    }

    @Test
    fun `RelPath accepts normal relative paths`() {
        assertTrue(RelPath.of("src/main/kotlin/Foo.kt").isSuccess)
    }

    @Test
    fun `RelPath accepts dot (current dir reference)`() {
        // A single '.' does not escape; it is the current directory.
        assertTrue(RelPath.of("./foo").isSuccess)
    }

    @Test
    fun `RelPath property test over crafted payloads`() {
        // Every one of these should fail.
        val evilPaths = listOf(
            "..",
            "../secret",
            "a/../../b",
            "/etc/passwd",
            "\\\\server\\share",
            "C:\\Windows",
            "D:/data",
            "foo\u0000bar",
            "\u0000",
            "../../etc/shadow",
            "sub/../../../escape",
        )
        for (path in evilPaths) {
            assertTrue(
                RelPath.of(path).isFailure,
                "Expected RelPath.of($path) to fail but it succeeded"
            )
        }
    }

    // ─── 2. Revocation by epoch ───────────────────────────────────────────────

    private val noOp = object : Operation<Unit> {
        override val name = "test.op"
        override val effect = EffectKind.Read
        override val recoveryClass = RecoveryClass.PURE
    }

    private fun seedEpoch(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, projectId: String) {
        driver.execute(null,
            "INSERT OR IGNORE INTO project_revocation_epoch (project_id, epoch) VALUES (?, 0)",
            1
        ) { bindString(0, projectId) }
    }

    private fun seedProject(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, projectId: String) {
        driver.execute(null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', '2026-08-05T00:00:00Z', '2026-08-05T00:00:00Z', '2026-08-05T00:00:00Z')",
            1
        ) { bindString(0, projectId) }
    }

    @Test
    fun `revocation invalidates capability within one step`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000099"

        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        // Seed the audit_log row that capabilities foreign-key to.

        val cap = mgr.grant(
            subjectId = "session-1",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        // Before revocation: allowed.
        val before = mgr.validate("session-1", cap.id, noOp, TrustLevel.TRUSTED)
        assertEquals(CapabilityCheckResult.Allowed, before)

        // Revoke.
        mgr.revoke(cap.id, "user-1")

        // After revocation: denied (epoch advanced).
        val after = mgr.validate("session-1", cap.id, noOp, TrustLevel.TRUSTED)
        assertIs<CapabilityCheckResult.Denied>(after)
        assertEquals(DenialReason.CAPABILITY_REVOKED, after.reason)
    }

    // ─── 3. Taint ceiling ────────────────────────────────────────────────────

    @Test
    fun `validate denies SECRETS_READ when run taint is UNTRUSTED`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000100"

        seedProject(driver, projectId)
        seedEpoch(driver, projectId)


        val cap = mgr.grant(
            subjectId = "session-2",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.SECRETS_READ,
            scope = CapabilityScope.Secrets(ProjectId(projectId), emptyList()),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        // TRUSTED run: allowed.
        val trusted = mgr.validate("session-2", cap.id, noOp, TrustLevel.TRUSTED)
        assertEquals(CapabilityCheckResult.Allowed, trusted)

        // UNTRUSTED run: denied by taint.
        val untrusted = mgr.validate("session-2", cap.id, noOp, TrustLevel.UNTRUSTED)
        assertIs<CapabilityCheckResult.Denied>(untrusted)
        assertEquals(DenialReason.ATTENUATED_BY_TAINT, untrusted.reason)
    }

    @Test
    fun `taint ceiling denies NETWORK_EGRESS and SHELL_EXEC but not FS_READ`() {
        // isTaintDenied is the authority used by validate(). Testing it directly exercises
        // the taint ceiling for all permission classes that RFC-0027 requires to deny.
        assertTrue(SqliteCapabilityManager.isTaintDenied(Permission.NETWORK_EGRESS, noOp))
        assertTrue(SqliteCapabilityManager.isTaintDenied(Permission.SECRETS_READ, noOp))
        assertTrue(SqliteCapabilityManager.isTaintDenied(Permission.SHELL_EXEC, noOp))
        assertFalse(SqliteCapabilityManager.isTaintDenied(Permission.FS_READ, noOp))
        assertFalse(SqliteCapabilityManager.isTaintDenied(Permission.FS_WRITE, noOp))
        assertFalse(SqliteCapabilityManager.isTaintDenied(Permission.GIT_READ, noOp))
    }

    @Test
    fun `STT_QUERY and TTS_QUERY permissions are available for M33 voice support`() {
        // M33: Verify STT_QUERY and TTS_QUERY permissions are defined (RFC-0020).
        // These enable fine-grained control over voice input (STT) and voice output (TTS).
        // Sessions can be granted STT_QUERY to capture voice input and TTS_QUERY for voice output.
        val sttPermission = Permission.STT_QUERY
        val ttsPermission = Permission.TTS_QUERY
        assertEquals("STT_QUERY", sttPermission.name)
        assertEquals("TTS_QUERY", ttsPermission.name)

        // These permissions complement MODEL_QUERY for voice-specific capabilities.
        // Like MODEL_QUERY, they use CapabilityScope.Model to restrict to appropriate model kinds.
    }

    @Test
    fun `validate allows FS_READ even when run taint is UNTRUSTED`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000102"

        seedProject(driver, projectId)
        seedEpoch(driver, projectId)


        val cap = mgr.grant(
            subjectId = "session-4",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        // FS_READ within project is allowed even when tainted (RFC-0027).
        val result = mgr.validate("session-4", cap.id, noOp, TrustLevel.UNTRUSTED)
        assertEquals(CapabilityCheckResult.Allowed, result)
    }
}
