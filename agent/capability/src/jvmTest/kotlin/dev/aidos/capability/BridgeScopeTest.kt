package dev.aidos.capability

import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.CapabilityConstraints
import dev.aidos.kernel.CapabilityScope
import dev.aidos.kernel.Permission
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.UserId
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** RFC-0113 §6: the bridge's cross-project authority is stored faithfully and can only be narrowed on delegation. */
class BridgeScopeTest {
    private val now = "2026-11-15T00:00:00Z"
    private val pid = "01234567-89ab-7def-8abc-000000000777"

    private lateinit var driver: app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

    private fun manager(): SqliteCapabilityManager {
        val root = Files.createTempDirectory("bridge-scope").toFile()
        driver = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "t") { now }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
        driver.execute(
            null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 't', '/', 'generic', '$now', '$now', '$now')", 1,
        ) { bindString(0, pid) }
        driver.execute(null, "INSERT OR IGNORE INTO project_revocation_epoch (project_id, epoch) VALUES (?, 0)", 1) { bindString(0, pid) }
        return SqliteCapabilityManager(driver, UuidV7Generator()) { now }
    }

    private val parentScope = CapabilityScope.Bridge(
        allowedQuerySchemas = setOf("CheckAvailability", "CheckListContains"),
        targetProjectIds = setOf("life-1", "life-2"),
        allowedChannels = setOf("sms", "matrix"),
    )

    @Test
    fun `a bridge scope survives storage unchanged`(): Unit = runBlocking {
        val mgr = manager()
        val id = mgr.grant("s1", SubjectKind.SESSION, Permission.BLINDED_QUERY, parentScope, CapabilityConstraints(), null, UserId("u")).getOrThrow().id
        assertEquals(parentScope, mgr.loadForSubject("s1").single { it.id == id }.scope)
    }

    @Test
    fun `delegation can narrow a bridge scope but never widen it`(): Unit = runBlocking {
        val mgr = manager()
        val parent = mgr.grant("s1", SubjectKind.SESSION, Permission.BLINDED_QUERY, parentScope, CapabilityConstraints(), null, UserId("u")).getOrThrow().id

        // allowsDelegation is false on a fresh grant(); the other delegation tests flip it the same way.
        driver.execute(null, "UPDATE capabilities SET allows_delegation = 1 WHERE id = ?", 1) { bindString(0, parent.value) }

        val narrower = CapabilityScope.Bridge(setOf("CheckAvailability"), setOf("life-1"), setOf("sms"))
        assertTrue(mgr.delegate(parent, "w1", SubjectKind.SESSION, narrower, CapabilityConstraints()).isSuccess)

        for (wider in listOf(
            parentScope.copy(allowedQuerySchemas = parentScope.allowedQuerySchemas + "VerifyContactCircle"),
            parentScope.copy(targetProjectIds = parentScope.targetProjectIds + "life-3"),
            parentScope.copy(allowedChannels = parentScope.allowedChannels + "email"),
        )) {
            assertTrue(mgr.delegate(parent, "w2", SubjectKind.SESSION, wider, CapabilityConstraints()).isFailure, "refused: $wider")
        }
    }
}
