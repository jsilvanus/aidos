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

    @Test
    fun `validate denies a grant issued with requiresApprovalPerUse, unconditionally`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000101"

        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val cap = mgr.grant(
            subjectId = "session-3",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.SHELL_EXEC,
            scope = CapabilityScope.Shell(ProjectId(projectId), "/", null),
            constraints = CapabilityConstraints(requiresApprovalPerUse = true),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        // Every use is denied -- "per use" means every use, not just the first. A fresh, unexpired,
        // unrevoked grant with this constraint set must never fall through to Allowed.
        repeat(2) {
            val result = mgr.validate("session-3", cap.id, noOp, TrustLevel.TRUSTED)
            assertIs<CapabilityCheckResult.Denied>(result)
            assertEquals(DenialReason.REQUIRES_APPROVAL, result.reason)
        }
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

    // ─── Scope/constraints JSON round-trip ────────────────────────────────────
    //
    // parseCapabilityRow used to reconstruct a hard-coded Filesystem scope (using the raw
    // scope_json string as the path!) for every capability regardless of its real type, and an
    // always-empty CapabilityConstraints regardless of what was stored -- silently dropping
    // Budget down to whatever the caller's in-memory Capability object happened to hold before
    // the round trip, never what SQLite actually has. These tests grant/delegate a capability of
    // each scope type with a fully populated constraints/budget, reload it via loadForSubject(),
    // and assert every field survived.

    @Test
    fun `Filesystem scope and full constraints round-trip through loadForSubject`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000200"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val budget = dev.aidos.kernel.Budget(
            modelCalls = 3, inputTokens = 1000, outputTokens = 2000,
            costUnits = 5000, steps = 8, wallClockSeconds = 60, toolInvocations = 4,
        )
        val granted = mgr.grant(
            subjectId = "session-fs",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.FS_WRITE,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src/tests"),
            constraints = CapabilityConstraints(
                maxDurationSeconds = 30, maxBytesRead = 100L, maxBytesWritten = 200L,
                requiresApprovalPerUse = false, maxExerciseCount = 5, budget = budget,
            ),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        val reloaded = mgr.loadForSubject("session-fs").single { it.id == granted.id }
        val scope = assertIs<CapabilityScope.Filesystem>(reloaded.scope)
        assertEquals(ProjectId(projectId), scope.projectId)
        assertEquals("src/tests", scope.rootRelativePath)
        assertEquals(CapabilityConstraints(30, 100L, 200L, false, 5, budget), reloaded.constraints)
    }

    @Test
    fun `Git scope round-trips allowedOperations`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000201"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val granted = mgr.grant(
            subjectId = "session-git",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.GIT_WRITE,
            scope = CapabilityScope.Git(
                ProjectId(projectId),
                setOf(dev.aidos.kernel.GitOperation.READ, dev.aidos.kernel.GitOperation.WRITE),
            ),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        val reloaded = mgr.loadForSubject("session-git").single { it.id == granted.id }
        val scope = assertIs<CapabilityScope.Git>(reloaded.scope)
        assertEquals(ProjectId(projectId), scope.projectId)
        assertEquals(setOf(dev.aidos.kernel.GitOperation.READ, dev.aidos.kernel.GitOperation.WRITE), scope.allowedOperations)
    }

    @Test
    fun `Shell scope round-trips workingDirectory and allowedCommands`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000202"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val granted = mgr.grant(
            subjectId = "session-shell",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.SHELL_EXEC,
            scope = CapabilityScope.Shell(ProjectId(projectId), "project/tests", listOf("cargo", "test")),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        val reloaded = mgr.loadForSubject("session-shell").single { it.id == granted.id }
        val scope = assertIs<CapabilityScope.Shell>(reloaded.scope)
        assertEquals("project/tests", scope.workingDirectory)
        assertEquals(listOf("cargo", "test"), scope.allowedCommands)
    }

    @Test
    fun `delegated capability carries a real (not empty) scope and a split budget`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000203"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parentBudget = dev.aidos.kernel.Budget(modelCalls = 9, costUnits = 9000, steps = 24)
        val parent = mgr.grant(
            subjectId = "driver-1",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.FS_WRITE,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src"),
            constraints = CapabilityConstraints(budget = parentBudget),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        // allowsDelegation defaults to false on a fresh grant() -- flip it via a raw update
        // (there is no public API for this in MVP; mirrors how other tests seed rows directly).
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, parent.id.value)
        }

        // Reload the parent the same way a real WorkerSpawner would (loadForSubject, not the
        // in-memory `parent` reference returned by grant()) -- this is exactly the path that was
        // broken: before this fix, the reloaded scope was always Filesystem("/") over whatever
        // scope_json actually said, and its budget was always empty regardless of parentBudget.
        val reloadedParent = mgr.loadForSubject("driver-1").single { it.id == parent.id }
        val splitBudget = parentBudget.split(3)

        val delegated = mgr.delegate(
            parent = reloadedParent.id,
            toSubjectId = "worker-1",
            toSubjectKind = dev.aidos.kernel.SubjectKind.WORKER,
            attenuatedScope = reloadedParent.scope,
            attenuatedConstraints = reloadedParent.constraints.copy(budget = splitBudget),
        ).getOrThrow()

        val reloadedWorkerCap = mgr.loadForSubject("worker-1").single { it.id == delegated.id }
        val scope = assertIs<CapabilityScope.Filesystem>(reloadedWorkerCap.scope)
        assertEquals("src", scope.rootRelativePath)
        assertEquals(splitBudget, reloadedWorkerCap.constraints.budget)
    }
}
