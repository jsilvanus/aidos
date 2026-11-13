package dev.aidos.daemon

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.capability.SqliteCapabilityManager
import dev.aidos.identity.UuidV7Generator
import dev.aidos.kernel.Permission
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SubjectKind
import dev.aidos.mcp.core.McpTransport
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.test.runTest

class McpCapabilityIssuanceTest {
    private val nowIso = "2026-11-13T00:00:00Z"

    private fun openProjectDriver(): SqlDriver {
        val root = Files.createTempDirectory("mcp-capability-issuance").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { nowIso }.driver
    }

    private fun seedProject(driver: SqlDriver, projectId: String) {
        driver.execute(
            null,
            "INSERT INTO projects (id, name, root_path, project_type, created_at, updated_at, state_updated_at) " +
                "VALUES (?, 'test', '/', 'generic', ?, ?, ?)",
            4,
        ) { bindString(0, projectId); bindString(1, nowIso); bindString(2, nowIso); bindString(3, nowIso) }
    }

    @Test
    fun `issuing MCP capability creates MCP_SERVER row once`() = runTest {
        val driver = openProjectDriver()
        seedProject(driver, "proj-mcp-issue")
        val manager = SqliteCapabilityManager(driver, UuidV7Generator(), { nowIso })

        ensureMcpServerCapability(
            capabilityManager = manager,
            projectId = ProjectId("proj-mcp-issue"),
            serverId = "issues",
            transport = McpTransport.Http(endpointUrl = "https://mcp.example.com/v1"),
        )
        ensureMcpServerCapability(
            capabilityManager = manager,
            projectId = ProjectId("proj-mcp-issue"),
            serverId = "issues",
            transport = McpTransport.Http(endpointUrl = "https://mcp.example.com/v1"),
        )

        val count = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM capabilities WHERE subject_kind = 'MCP_SERVER' AND subject_id = ? AND permission = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
            parameters = 2,
        ) { bindString(0, "issues"); bindString(1, Permission.NETWORK_EGRESS.name) }.value
        assertEquals(1L, count)

        val kind = manager.loadForSubject("issues").single().subjectKind
        assertEquals(SubjectKind.MCP_SERVER, kind)
    }
}
