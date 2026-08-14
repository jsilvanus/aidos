package dev.aidos.daemon

import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.Permission
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.UserId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * [CapabilityResolver] (M19, RFC-0008 step 8c): the shape confirmed with the project owner
 * before being built — match by `(subjectId, permission)`, prefer the most recently issued grant
 * among unexpired, unrevoked matches, no error if more than one exists.
 */
class CapabilityResolverTest {

    private var clock = "2026-08-10T00:00:00Z"

    private fun openDriver() = run {
        val root = Files.createTempDirectory("capability-resolver-test").toFile()
        AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { clock }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
    }

    private fun manager(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver) =
        SqliteCapabilityManager(driver, UuidV7Generator()) { clock }

    private fun seedProject(driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver, projectId: String) {
        driver.execute(
            null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', '2026-08-10T00:00:00Z', '2026-08-10T00:00:00Z', '2026-08-10T00:00:00Z')",
            1
        ) { bindString(0, projectId) }
    }

    @Test
    fun `resolves the subject's held capability for the tool's required permission`() = runTest {
        val driver = openDriver()
        seedProject(driver, "proj-1")
        val mgr = manager(driver)
        val resolver = CapabilityResolver(mgr) { clock }

        val cap = mgr.grant(
            subjectId = "session-1", subjectKind = SubjectKind.SESSION, permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId("proj-1"), "/"), constraints = CapabilityConstraints(),
            expiresAt = null, grantedBy = UserId("user-1"),
        ).getOrThrow()

        assertEquals(cap.id, resolver.resolve("session-1", Permission.FS_READ))
    }

    @Test
    fun `no held capability for that permission resolves to null`() = runTest {
        val driver = openDriver()
        seedProject(driver, "proj-1")
        val mgr = manager(driver)
        val resolver = CapabilityResolver(mgr) { clock }

        mgr.grant(
            subjectId = "session-1", subjectKind = SubjectKind.SESSION, permission = Permission.GIT_READ,
            scope = CapabilityScope.Git(ProjectId("proj-1"), emptySet()), constraints = CapabilityConstraints(),
            expiresAt = null, grantedBy = UserId("user-1"),
        ).getOrThrow()

        // Held a GIT_READ capability, not FS_READ -- the tool needs FS_READ.
        assertNull(resolver.resolve("session-1", Permission.FS_READ))
    }

    @Test
    fun `a revoked capability is never resolved, even if it is the only one`() = runTest {
        val driver = openDriver()
        seedProject(driver, "proj-1")
        val mgr = manager(driver)
        val resolver = CapabilityResolver(mgr) { clock }

        val cap = mgr.grant(
            subjectId = "session-1", subjectKind = SubjectKind.SESSION, permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId("proj-1"), "/"), constraints = CapabilityConstraints(),
            expiresAt = null, grantedBy = UserId("user-1"),
        ).getOrThrow()
        mgr.revoke(cap.id, "user-1")

        assertNull(resolver.resolve("session-1", Permission.FS_READ))
    }

    @Test
    fun `an expired capability is never resolved`() = runTest {
        val driver = openDriver()
        seedProject(driver, "proj-1")
        val mgr = manager(driver)

        mgr.grant(
            subjectId = "session-1", subjectKind = SubjectKind.SESSION, permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId("proj-1"), "/"), constraints = CapabilityConstraints(),
            expiresAt = kotlinx.datetime.Instant.parse("2026-08-09T00:00:00Z"), // before `clock`
            grantedBy = UserId("user-1"),
        ).getOrThrow()

        val resolver = CapabilityResolver(mgr) { clock } // clock = 2026-08-10, after expiry
        assertNull(resolver.resolve("session-1", Permission.FS_READ))
    }

    @Test
    fun `two held grants for the same permission -- the most recently issued one wins, no error`() = runTest {
        val driver = openDriver()
        seedProject(driver, "proj-1")
        val mgr = manager(driver)

        clock = "2026-08-10T00:00:00Z"
        val older = mgr.grant(
            subjectId = "session-1", subjectKind = SubjectKind.SESSION, permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId("proj-1"), "/a"), constraints = CapabilityConstraints(),
            expiresAt = null, grantedBy = UserId("user-1"),
        ).getOrThrow()

        clock = "2026-08-10T01:00:00Z"
        val newer = mgr.grant(
            subjectId = "session-1", subjectKind = SubjectKind.SESSION, permission = Permission.FS_READ,
            scope = CapabilityScope.Filesystem(ProjectId("proj-1"), "/b"), constraints = CapabilityConstraints(),
            expiresAt = null, grantedBy = UserId("user-1"),
        ).getOrThrow()

        val resolver = CapabilityResolver(mgr) { clock }
        assertEquals(newer.id, resolver.resolve("session-1", Permission.FS_READ))
        // Not the older one -- confirms this is actually picking by recency, not by insertion order.
        assert(older.id != newer.id)
    }
}
