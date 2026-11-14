package dev.aidos.capability

import app.cash.sqldelight.db.QueryResult
import dev.aidos.kernel.Budget
import dev.aidos.kernel.CapabilityCheckResult
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.DenialReason
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.GrantSource
import dev.aidos.kernel.ModelKind
import dev.aidos.kernel.MutationScope
import dev.aidos.kernel.Operation
import dev.aidos.kernel.Permission
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.RedirectPolicy
import dev.aidos.kernel.SubjectKind
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
 * Tests for RFC-0018 newly implemented features:
 * 1. maxExerciseCount enforcement in validate()
 * 2. delegate() scope containment checks
 */
class CapabilityTestNewFeatures {

    private fun op(effect: EffectKind) = object : Operation<Unit> {
        override val name = "test.op"
        override val effect = effect
        override val recoveryClass = RecoveryClass.PURE
    }

    private fun openProjectDriver(): app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver {
        val root = Files.createTempDirectory("capability-test").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { "2026-08-05T00:00:00Z" }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun manager(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver): SqliteCapabilityManager {
        var t = "2026-08-05T00:00:00Z"
        return SqliteCapabilityManager(driver, UuidV7Generator()) { t }
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

    // ─── maxExerciseCount enforcement ─────────────────────────────────────────

    private fun readExerciseCount(
        driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver,
        capabilityId: dev.aidos.kernel.CapabilityId,
    ): Long = driver.executeQuery(
        null,
        "SELECT exercised_count FROM capability_usage WHERE capability_id = ?",
        { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
        1,
    ) { bindString(0, capabilityId.value) }.value

    @Test
    fun `maxExerciseCount limit is enforced and auto-incremented on validate`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000300"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        // Grant a capability with maxExerciseCount = 2
        val granted = mgr.grant(
            subjectId = "session-limited",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src"),
            constraints = CapabilityConstraints(
                maxExerciseCount = 2,
            ),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        // First validation succeeds and increments exercised_count to 1
        val result1 = mgr.validate("session-limited", granted.id, op(EffectKind.Read), TrustLevel.PROJECT)
        assertIs<CapabilityCheckResult.Allowed>(result1)
        assertEquals(1L, readExerciseCount(driver, granted.id))

        // Second validation succeeds and increments exercised_count to 2
        val result2 = mgr.validate("session-limited", granted.id, op(EffectKind.Read), TrustLevel.PROJECT)
        assertIs<CapabilityCheckResult.Allowed>(result2)
        assertEquals(2L, readExerciseCount(driver, granted.id))

        // Third validation fails with CONSTRAINT_EXCEEDED without incrementing past 2
        val result3 = mgr.validate("session-limited", granted.id, op(EffectKind.Read), TrustLevel.PROJECT)
        val denied = assertIs<CapabilityCheckResult.Denied>(result3)
        assertEquals(DenialReason.CONSTRAINT_EXCEEDED, denied.reason)
        assertEquals(2L, readExerciseCount(driver, granted.id))
    }

    @Test
    fun `maxExerciseCount zero denies immediately without incrementing`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000310"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val granted = mgr.grant(
            subjectId = "session-zero",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src"),
            constraints = CapabilityConstraints(maxExerciseCount = 0),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        val result = mgr.validate("session-zero", granted.id, op(EffectKind.Read), TrustLevel.PROJECT)
        val denied = assertIs<CapabilityCheckResult.Denied>(result)
        assertEquals(DenialReason.CONSTRAINT_EXCEEDED, denied.reason)
        assertEquals(0L, readExerciseCount(driver, granted.id))
    }

    @Test
    fun `unlimited maxExerciseCount (null) does not restrict exercises`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000301"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        // Grant with no maxExerciseCount (unlimited)
        val granted = mgr.grant(
            subjectId = "session-unlimited",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src"),
            constraints = CapabilityConstraints(maxExerciseCount = null),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        // Set usage to a very high number
        driver.execute(null,
            "INSERT INTO capability_usage (capability_id, exercised_count) VALUES (?, 1000)",
            1
        ) { bindString(0, granted.id.value) }

        // Validation should still succeed
        val result = mgr.validate("session-unlimited", granted.id, op(EffectKind.Read), TrustLevel.PROJECT)
        assertIs<dev.aidos.kernel.CapabilityCheckResult.Allowed>(result)
    }

    // ─── Delegation scope narrowing validation ─────────────────────────────────

    @Test
    fun `filesystem delegation rejects cross-project paths`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000302"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parent = mgr.grant(
            subjectId = "session-parent",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.FS_WRITE,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        // Update the parent to allow delegation
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, parent.id.value)
        }

        val otherProject = "01234567-89ab-7def-8abc-000000000999"
        seedProject(driver, otherProject)
        seedEpoch(driver, otherProject)

        val result = mgr.delegate(
            parent = parent.id,
            toSubjectId = "worker-1",
            toSubjectKind = dev.aidos.kernel.SubjectKind.WORKER,
            attenuatedScope = CapabilityScope.Filesystem(ProjectId(otherProject), "src"),
            attenuatedConstraints = CapabilityConstraints(),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `filesystem delegation narrows path correctly`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000303"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parent = mgr.grant(
            subjectId = "session-parent",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.FS_WRITE,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        // Update to allow delegation
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, parent.id.value)
        }

        // Narrow to a subdirectory
        val result = mgr.delegate(
            parent = parent.id,
            toSubjectId = "worker-1",
            toSubjectKind = dev.aidos.kernel.SubjectKind.WORKER,
            attenuatedScope = CapabilityScope.Filesystem(ProjectId(projectId), "src/tests"),
            attenuatedConstraints = CapabilityConstraints(),
        )

        assertTrue(result.isSuccess)
        val delegated = result.getOrThrow()
        val delegatedScope = assertIs<CapabilityScope.Filesystem>(delegated.scope)
        assertEquals("src/tests", delegatedScope.rootRelativePath)
    }

    @Test
    fun `filesystem delegation rejects path expansion`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000304"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parent = mgr.grant(
            subjectId = "session-parent",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.FS_WRITE,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src/tests"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, parent.id.value)
        }

        // Try to expand to parent directory
        val result = mgr.delegate(
            parent = parent.id,
            toSubjectId = "worker-1",
            toSubjectKind = dev.aidos.kernel.SubjectKind.WORKER,
            attenuatedScope = CapabilityScope.Filesystem(ProjectId(projectId), "src"),
            attenuatedConstraints = CapabilityConstraints(),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `shell delegation narrows commands correctly`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000305"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parent = mgr.grant(
            subjectId = "session-parent",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.SHELL_EXEC,
            scope = CapabilityScope.Shell(ProjectId(projectId), ".", listOf("cargo", "test", "build")),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, parent.id.value)
        }

        // Narrow to a subset of commands
        val result = mgr.delegate(
            parent = parent.id,
            toSubjectId = "worker-1",
            toSubjectKind = dev.aidos.kernel.SubjectKind.WORKER,
            attenuatedScope = CapabilityScope.Shell(ProjectId(projectId), ".", listOf("test")),
            attenuatedConstraints = CapabilityConstraints(),
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `shell delegation rejects command expansion`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000306"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parent = mgr.grant(
            subjectId = "session-parent",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.SHELL_EXEC,
            scope = CapabilityScope.Shell(ProjectId(projectId), ".", listOf("test")),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, parent.id.value)
        }

        // Try to add a command not in parent
        val result = mgr.delegate(
            parent = parent.id,
            toSubjectId = "worker-1",
            toSubjectKind = dev.aidos.kernel.SubjectKind.WORKER,
            attenuatedScope = CapabilityScope.Shell(ProjectId(projectId), ".", listOf("test", "build")),
            attenuatedConstraints = CapabilityConstraints(),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `network delegation narrows hosts correctly`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000307"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parent = mgr.grant(
            subjectId = "session-parent",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.NETWORK_EGRESS,
            scope = CapabilityScope.Network(listOf("*.github.com", "api.anthropic.com")),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, parent.id.value)
        }

        // Narrow to specific host
        val result = mgr.delegate(
            parent = parent.id,
            toSubjectId = "worker-1",
            toSubjectKind = dev.aidos.kernel.SubjectKind.WORKER,
            attenuatedScope = CapabilityScope.Network(listOf("api.anthropic.com")),
            attenuatedConstraints = CapabilityConstraints(),
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `constraint attenuation rejects budget expansion`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000308"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parentBudget = dev.aidos.kernel.Budget(
            modelCalls = 5, steps = 10, wallClockSeconds = 30,
            inputTokens = null, outputTokens = null, costUnits = null, toolInvocations = null,
        )
        val parent = mgr.grant(
            subjectId = "session-parent",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.MODEL_QUERY,
            scope = CapabilityScope.Model(null, setOf(dev.aidos.kernel.ModelKind.LLM)),
            constraints = CapabilityConstraints(budget = parentBudget),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, parent.id.value)
        }

        // Try to expand budget beyond parent
        val expandedBudget = dev.aidos.kernel.Budget(
            modelCalls = 10, steps = 10, wallClockSeconds = 30,
            inputTokens = null, outputTokens = null, costUnits = null, toolInvocations = null,
        )
        val result = mgr.delegate(
            parent = parent.id,
            toSubjectId = "worker-1",
            toSubjectKind = dev.aidos.kernel.SubjectKind.WORKER,
            attenuatedScope = CapabilityScope.Model(null, setOf(dev.aidos.kernel.ModelKind.LLM)),
            attenuatedConstraints = CapabilityConstraints(budget = expandedBudget),
        )

        assertTrue(result.isFailure)
    }

    @Test
    fun `constraint attenuation allows budget narrowing`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000309"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parentBudget = dev.aidos.kernel.Budget(
            modelCalls = 10, steps = 20, wallClockSeconds = 60,
            inputTokens = null, outputTokens = null, costUnits = null, toolInvocations = null,
        )
        val parent = mgr.grant(
            subjectId = "session-parent",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.MODEL_QUERY,
            scope = CapabilityScope.Model(null, setOf(dev.aidos.kernel.ModelKind.LLM)),
            constraints = CapabilityConstraints(budget = parentBudget),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, parent.id.value)
        }

        // Narrow budget
        val narrowedBudget = dev.aidos.kernel.Budget(
            modelCalls = 5, steps = 10, wallClockSeconds = 30,
            inputTokens = null, outputTokens = null, costUnits = null, toolInvocations = null,
        )
        val result = mgr.delegate(
            parent = parent.id,
            toSubjectId = "worker-1",
            toSubjectKind = dev.aidos.kernel.SubjectKind.WORKER,
            attenuatedScope = CapabilityScope.Model(null, setOf(dev.aidos.kernel.ModelKind.LLM)),
            attenuatedConstraints = CapabilityConstraints(budget = narrowedBudget),
        )

        assertTrue(result.isSuccess)
    }

    @Test
    fun `filesystem delegation enforces segment-safe containment and rejects traversal`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000311"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val relRootParent = mgr.grant(
            subjectId = "session-rel",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_WRITE,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "."),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        val prefixParent = mgr.grant(
            subjectId = "session-prefix",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_WRITE,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "/a/b/foo"),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1", 0)

        assertTrue(
            mgr.delegate(
                relRootParent.id, "worker-1", SubjectKind.WORKER,
                CapabilityScope.Filesystem(ProjectId(projectId), "src/main"), CapabilityConstraints(),
            ).isSuccess
        )
        for (badChild in listOf("/etc/passwd", "/", "../escape", "src\\..\\escape", "\\etc\\passwd", "C:\\Windows")) {
            assertFalse(
                mgr.delegate(
                    relRootParent.id, "worker-bad", SubjectKind.WORKER,
                    CapabilityScope.Filesystem(ProjectId(projectId), badChild), CapabilityConstraints(),
                ).isSuccess,
                "Expected rejection for child '$badChild' under '.'",
            )
        }

        assertTrue(
            mgr.delegate(
                prefixParent.id, "worker-2", SubjectKind.WORKER,
                CapabilityScope.Filesystem(ProjectId(projectId), "/a/b/foo/bar"), CapabilityConstraints(),
            ).isSuccess
        )
        assertTrue(
            mgr.delegate(
                prefixParent.id, "worker-bad-prefix", SubjectKind.WORKER,
                CapabilityScope.Filesystem(ProjectId(projectId), "/a/b/foobar"), CapabilityConstraints(),
            ).isFailure
        )
    }

    @Test
    fun `shell delegation enforces workingDirectory containment`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000312"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parent = mgr.grant(
            subjectId = "session-shell",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.SHELL_EXEC,
            scope = CapabilityScope.Shell(ProjectId(projectId), "src", listOf("cargo", "test")),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, parent.id.value)
        }

        assertTrue(
            mgr.delegate(
                parent.id, "worker-ok", SubjectKind.WORKER,
                CapabilityScope.Shell(ProjectId(projectId), "src/tests", listOf("test")), CapabilityConstraints(),
            ).isSuccess
        )
        for (badDir in listOf("src_other", ".", "..", "src/../outside", "/tmp", "src\\..\\etc")) {
            assertTrue(
                mgr.delegate(
                    parent.id, "worker-bad", SubjectKind.WORKER,
                    CapabilityScope.Shell(ProjectId(projectId), badDir, listOf("test")), CapabilityConstraints(),
                ).isFailure,
                "Expected failure for workingDirectory '$badDir'",
            )
        }
    }

    @Test
    fun `network delegation enforces hosts, private addresses, redirects, and maxResponseBytes`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000313"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parent = mgr.grant(
            subjectId = "session-net",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.NETWORK_EGRESS,
            scope = CapabilityScope.Network(
                allowedHosts = listOf("*.example.com"),
                allowedPorts = listOf(443),
                allowPrivateAddresses = false,
                followRedirects = RedirectPolicy.SAME_HOST_ONLY,
                maxResponseBytes = 1024L,
            ),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        val emptyParent = mgr.grant(
            subjectId = "session-net-empty",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.NETWORK_EGRESS,
            scope = CapabilityScope.Network(allowedHosts = emptyList()),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-2"),
        ).getOrThrow()
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1", 0)

        // Valid attenuation
        assertTrue(
            mgr.delegate(
                parent.id, "worker-ok", SubjectKind.WORKER,
                CapabilityScope.Network(
                    allowedHosts = listOf("api.example.com"),
                    allowedPorts = listOf(443),
                    allowPrivateAddresses = false,
                    followRedirects = RedirectPolicy.NONE,
                    maxResponseBytes = 512L,
                ),
                CapabilityConstraints(),
            ).isSuccess
        )

        // Empty allowedHosts rejected on both delegated and empty parent
        assertTrue(
            mgr.delegate(
                parent.id, "w-empty", SubjectKind.WORKER,
                CapabilityScope.Network(allowedHosts = emptyList(), allowedPorts = listOf(443)),
                CapabilityConstraints(),
            ).isFailure
        )
        assertTrue(
            mgr.delegate(
                emptyParent.id, "w-empty-parent", SubjectKind.WORKER,
                CapabilityScope.Network(allowedHosts = listOf("api.example.com")),
                CapabilityConstraints(),
            ).isFailure
        )

        // Wildcard leading-dot bypass rejected
        assertTrue(
            mgr.delegate(
                parent.id, "w-dot", SubjectKind.WORKER,
                CapabilityScope.Network(allowedHosts = listOf(".example.com"), allowedPorts = listOf(443)),
                CapabilityConstraints(),
            ).isFailure
        )

        // allowPrivateAddresses escalation false -> true rejected
        assertTrue(
            mgr.delegate(
                parent.id, "w-priv", SubjectKind.WORKER,
                CapabilityScope.Network(
                    allowedHosts = listOf("api.example.com"),
                    allowedPorts = listOf(443),
                    allowPrivateAddresses = true,
                    followRedirects = RedirectPolicy.NONE,
                    maxResponseBytes = 512L,
                ),
                CapabilityConstraints(),
            ).isFailure
        )

        // followRedirects relaxation rejected
        assertTrue(
            mgr.delegate(
                parent.id, "w-redir", SubjectKind.WORKER,
                CapabilityScope.Network(
                    allowedHosts = listOf("api.example.com"),
                    allowedPorts = listOf(443),
                    allowPrivateAddresses = false,
                    followRedirects = RedirectPolicy.ANY_ALLOWED_HOST,
                    maxResponseBytes = 512L,
                ),
                CapabilityConstraints(),
            ).isFailure
        )

        // maxResponseBytes escalation and negative value rejected
        assertTrue(
            mgr.delegate(
                parent.id, "w-bytes", SubjectKind.WORKER,
                CapabilityScope.Network(
                    allowedHosts = listOf("api.example.com"),
                    allowedPorts = listOf(443),
                    allowPrivateAddresses = false,
                    followRedirects = RedirectPolicy.SAME_HOST_ONLY,
                    maxResponseBytes = 2048L,
                ),
                CapabilityConstraints(),
            ).isFailure
        )
        assertTrue(
            mgr.delegate(
                parent.id, "w-neg", SubjectKind.WORKER,
                CapabilityScope.Network(
                    allowedHosts = listOf("api.example.com"),
                    allowedPorts = listOf(443),
                    allowPrivateAddresses = false,
                    followRedirects = RedirectPolicy.SAME_HOST_ONLY,
                    maxResponseBytes = -1L,
                ),
                CapabilityConstraints(),
            ).isFailure
        )
    }

    @Test
    fun `model delegation allows restricting providers and blocks expanding from restricted`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000314"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val allProvidersParent = mgr.grant(
            subjectId = "session-model-all",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.MODEL_QUERY,
            scope = CapabilityScope.Model(allowedProviders = null, allowedKinds = setOf(ModelKind.LLM, ModelKind.VISION)),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        val restrictedParent = mgr.grant(
            subjectId = "session-model-one",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.MODEL_QUERY,
            scope = CapabilityScope.Model(allowedProviders = listOf("anthropic"), allowedKinds = setOf(ModelKind.LLM)),
            constraints = CapabilityConstraints(),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1", 0)

        // Narrowing from null (all providers) -> listOf("anthropic") must succeed
        assertTrue(
            mgr.delegate(
                allProvidersParent.id, "worker-narrow", SubjectKind.WORKER,
                CapabilityScope.Model(allowedProviders = listOf("anthropic"), allowedKinds = setOf(ModelKind.LLM)),
                CapabilityConstraints(),
            ).isSuccess
        )

        // Expanding from listOf("anthropic") -> null (all providers) must fail
        assertTrue(
            mgr.delegate(
                restrictedParent.id, "worker-expand-null", SubjectKind.WORKER,
                CapabilityScope.Model(allowedProviders = null, allowedKinds = setOf(ModelKind.LLM)),
                CapabilityConstraints(),
            ).isFailure
        )
        // Expanding from listOf("anthropic") -> listOf("openai") must fail
        assertTrue(
            mgr.delegate(
                restrictedParent.id, "worker-expand-other", SubjectKind.WORKER,
                CapabilityScope.Model(allowedProviders = listOf("openai"), allowedKinds = setOf(ModelKind.LLM)),
                CapabilityConstraints(),
            ).isFailure
        )
    }

    @Test
    fun `constraint attenuation preserves requiresApprovalPerUse and rejects dropping it`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000315"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val approvalParent = mgr.grant(
            subjectId = "session-approval",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_WRITE,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src"),
            constraints = CapabilityConstraints(requiresApprovalPerUse = true),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        val noApprovalParent = mgr.grant(
            subjectId = "session-no-approval",
            subjectKind = SubjectKind.SESSION,
            permission = Permission.FS_WRITE,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src"),
            constraints = CapabilityConstraints(requiresApprovalPerUse = false),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1", 0)

        // Dropping requiresApprovalPerUse=true -> false is privilege escalation and must fail
        assertTrue(
            mgr.delegate(
                approvalParent.id, "worker-bypass", SubjectKind.WORKER,
                approvalParent.scope, CapabilityConstraints(requiresApprovalPerUse = false),
            ).isFailure
        )
        // Keeping requiresApprovalPerUse=true succeeds
        assertTrue(
            mgr.delegate(
                approvalParent.id, "worker-keep", SubjectKind.WORKER,
                approvalParent.scope, CapabilityConstraints(requiresApprovalPerUse = true),
            ).isSuccess
        )
        // Tightening requiresApprovalPerUse=false -> true succeeds
        assertTrue(
            mgr.delegate(
                noApprovalParent.id, "worker-tighten", SubjectKind.WORKER,
                noApprovalParent.scope, CapabilityConstraints(requiresApprovalPerUse = true),
            ).isSuccess
        )
    }

    @Test
    fun `constraint attenuation rejects null budget and checks all seven budget dimensions`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000316"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        val parentBudget = Budget(
            modelCalls = 4,
            inputTokens = 1000L,
            outputTokens = 500L,
            costUnits = 100L,
            steps = 8,
            wallClockSeconds = 60,
            toolInvocations = 10,
        )
        val parent = mgr.grant(
            subjectId = "worker-lead",
            subjectKind = SubjectKind.WORKER,
            permission = Permission.MODEL_QUERY,
            scope = CapabilityScope.Model(null, setOf(ModelKind.LLM)),
            constraints = CapabilityConstraints(budget = parentBudget),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) {
            bindString(0, parent.id.value)
        }

        // Dropping budget completely (budget = null) must fail
        assertTrue(
            mgr.delegate(
                parent.id, "worker-null-budget", SubjectKind.WORKER,
                parent.scope, CapabilityConstraints(budget = null),
            ).isFailure
        )

        // Dropping or expanding any of the 7 dimensions must fail
        val invalidBudgets = listOf(
            parentBudget.copy(modelCalls = 5),
            parentBudget.copy(inputTokens = 1001L),
            parentBudget.copy(inputTokens = null),
            parentBudget.copy(outputTokens = 501L),
            parentBudget.copy(costUnits = 101L),
            parentBudget.copy(steps = 9),
            parentBudget.copy(wallClockSeconds = 61),
            parentBudget.copy(toolInvocations = 11),
            parentBudget.copy(toolInvocations = null),
        )
        for (bad in invalidBudgets) {
            assertTrue(
                mgr.delegate(
                    parent.id, "worker-bad-dim", SubjectKind.WORKER,
                    parent.scope, CapabilityConstraints(budget = bad),
                ).isFailure,
                "Expected rejection for expanded budget $bad",
            )
        }

        // Narrowing all 7 dimensions succeeds and round-trips through SQLite cleanly
        val narrowed = Budget(
            modelCalls = 2,
            inputTokens = 500L,
            outputTokens = 250L,
            costUnits = 50L,
            steps = 4,
            wallClockSeconds = 30,
            toolInvocations = 5,
        )
        val delegated = mgr.delegate(
            parent.id, "worker-sub", SubjectKind.WORKER,
            CapabilityScope.Model(listOf("anthropic"), setOf(ModelKind.LLM)),
            CapabilityConstraints(budget = narrowed),
        ).getOrThrow()

        val reloaded = mgr.loadForSubject("worker-sub").single()
        assertEquals(delegated.id, reloaded.id)
        assertEquals(parent.id, reloaded.parentCapabilityId)
        assertEquals(narrowed, reloaded.constraints.budget)
        assertIs<GrantSource.Delegation>(reloaded.issuedBy)

        val issuedByKind = driver.executeQuery(
            null,
            "SELECT issued_by_kind FROM capabilities WHERE id = ?",
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            1,
        ) { bindString(0, delegated.id.value) }.value
        assertEquals("WORKER", issuedByKind)
    }
}
