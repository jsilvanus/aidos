package dev.aidos.broker

import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** RFC-0003: audit gaps are detectable, and `detail_json` is redacted before it is written. */
class AuditIntegrityTest {
    private val nowIso = "2026-11-15T00:00:00Z"
    private val projectId = "01234567-89ab-7def-8abc-000000000300"

    private fun driver(): app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver {
        val root = Files.createTempDirectory("audit-integrity").toFile()
        val d = AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
            as app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
        d.execute(null,
            "INSERT OR IGNORE INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', '$nowIso', '$nowIso', '$nowIso')", 1
        ) { bindString(0, projectId) }
        return d
    }

    private fun AuditLog.row(n: Int, detail: String = "{}") = write(
        id = "a-$n", projectId = projectId, kind = "ToolInvoked", actorKind = "SESSION", actorId = "s",
        detailJson = detail, nowIso = nowIso,
    )

    @Test
    fun `intact log verifies and a deleted middle row is reported as a gap`() {
        val d = driver()
        val log = AuditLog(d)
        (1..5).forEach { log.row(it) }
        assertTrue(AuditVerifier.verify(d, projectId).intact)

        d.execute(null, "DELETE FROM audit_log WHERE sequence = 3", 0)
        val report = AuditVerifier.verify(d, projectId)
        assertFalse(report.intact)
        assertEquals(listOf(3L), report.missingSequences)
    }

    @Test
    fun `a deleted prefix is detected because the sequence must start at one`() {
        val d = driver()
        val log = AuditLog(d)
        (1..3).forEach { log.row(it) }
        d.execute(null, "DELETE FROM audit_log WHERE sequence = 1", 0)
        assertFalse(AuditVerifier.verify(d, projectId).startsAtOne)
    }

    @Test
    fun `detail json is redacted before it is persisted`() {
        val d = driver()
        val log = AuditLog(d) { it.replace("sk-secret-123", "[REDACTED]") }
        log.row(1, """{"cmd":"curl -H sk-secret-123"}""")
        var stored = ""
        d.executeQuery(null, "SELECT detail_json FROM audit_log WHERE id = 'a-1'", { c ->
            if (c.next().value) stored = c.getString(0)!!
            app.cash.sqldelight.db.QueryResult.Value(Unit)
        }, 0)
        assertFalse(stored.contains("sk-secret-123"))
        assertTrue(stored.contains("[REDACTED]"))
    }
}
