package dev.aidos.capability

import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.DenialReason
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.MutationScope
import dev.aidos.kernel.Operation
import dev.aidos.kernel.Permission
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.UserId
import dev.aidos.identity.UuidV7Generator
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Tests for RFC-0018 newly implemented features:
 * 1. maxExerciseCount enforcement in validate()
 * 2. delegate() scope containment checks
 */
class CapabilityTestNewFeatures {

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

    @Test
    fun `maxExerciseCount limit is enforced on validate`(): Unit = runBlocking {
        val driver = openProjectDriver()
        val mgr = manager(driver)
        val projectId = "01234567-89ab-7def-8abc-000000000300"
        seedProject(driver, projectId)
        seedEpoch(driver, projectId)

        // Grant a capability with maxExerciseCount = 2
        val granted = mgr.grant(
            subjectId = "session-limited",
            subjectKind = dev.aidos.kernel.SubjectKind.SESSION,
            permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId(projectId), "src"),
            constraints = CapabilityConstraints(
                maxExerciseCount = 2,
            ),
            expiresAt = null,
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        // First validation should succeed
        val op = Operation<MutationScope>("fs:read", EffectKind.READ)
        val result1 = mgr.validate("session-limited", granted.id, op, TrustLevel.PROJECT)
        assertIs<dev.aidos.kernel.CapabilityCheckResult.Allowed>(result1)

        // Insert a usage record to simulate one exercise
        driver.execute(null,
            "INSERT INTO capability_usage (capability_id, exercised_count) VALUES (?, 1)",
            1
        ) { bindString(0, granted.id.value) }

        val result2 = mgr.validate("session-limited", granted.id, op, TrustLevel.PROJECT)
        assertIs<dev.aidos.kernel.CapabilityCheckResult.Allowed>(result2)

        // Update usage to reach the limit
        driver.execute(null,
            "UPDATE capability_usage SET exercised_count = 2 WHERE capability_id = ?",
            1
        ) { bindString(0, granted.id.value) }

        // Third validation should fail with CONSTRAINT_EXCEEDED
        val result3 = mgr.validate("session-limited", granted.id, op, TrustLevel.PROJECT)
        assertIs<dev.aidos.kernel.CapabilityCheckResult.Denied>(result3)
        assertEquals(DenialReason.CONSTRAINT_EXCEEDED, result3.reason)
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

        val op = Operation<MutationScope>("fs:read", EffectKind.READ)

        // Set usage to a very high number
        driver.execute(null,
            "INSERT INTO capability_usage (capability_id, exercised_count) VALUES (?, 1000)",
            1
        ) { bindString(0, granted.id.value) }

        // Validation should still succeed
        val result = mgr.validate("session-unlimited", granted.id, op, TrustLevel.PROJECT)
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
            permission = Permission.MODEL_RUN,
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
            permission = Permission.MODEL_RUN,
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
}
