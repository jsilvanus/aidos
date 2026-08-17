package dev.aidos.mcp

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.storage.AidosStorage
import dev.aidos.storage.DesktopPaths
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RFC-0031 / D31 done-when for [McpOperationAdoptionStore]: an operation is offered to the model
 * only if its *current* descriptor hash matches a stored adoption row; anything else is absent,
 * never an error. The test database is built from the real `schema/project.sql` DDL via
 * `AidosStorage.openProject` (same helper `:capability`'s `CapabilityTest` uses), not a
 * hand-written subset — `mcp_operation_adoptions`' `WITHOUT ROWID` composite primary key and its
 * `projects` foreign key are exactly the kind of detail a hand-written CREATE TABLE would drift
 * from.
 */
class McpOperationAdoptionStoreTest {

    private fun openProjectDb(): SqlDriver {
        val root = Files.createTempDirectory("mcp-adoption-test").toFile()
        return AidosStorage.openProject(DesktopPaths.stateDb(root.path), "test-1.0") { "2026-08-17T00:00:00Z" }.driver
    }

    /** `mcp_operation_adoptions.project_id` has an FK to `projects(id)`; every test needs one row. */
    private fun SqlDriver.insertProject(id: String) {
        execute(
            identifier = null,
            sql = "INSERT INTO projects (id, name, root_path, created_at, updated_at, state_updated_at) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            parameters = 6,
        ) {
            bindString(0, id)
            bindString(1, "Test Project")
            bindString(2, "/tmp/test-project")
            bindString(3, "2026-08-17T00:00:00Z")
            bindString(4, "2026-08-17T00:00:00Z")
            bindString(5, "2026-08-17T00:00:00Z")
        }
    }

    private fun spec(name: String, description: String, vararg schemaPairs: Pair<String, String>) =
        McpToolSpec(
            name = name,
            description = description,
            inputSchema = buildJsonObject { for ((k, v) in schemaPairs) put(k, v) },
        )

    // ─── adopted operation recognised ──────────────────────────────────────

    @Test
    fun `an operation with a matching adoption row is adopted`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)
        val op = spec("list_issues", "Lists issues", "repo" to "string")

        store.recordAdoption("proj-1", "github", op, "2026-08-17T00:00:00Z")

        val resolution = store.resolve("proj-1", "github", listOf(op))
        assertEquals(listOf(op), resolution.adopted)
        assertTrue(resolution.unadopted.isEmpty())
    }

    // ─── same name, changed descriptor: unadopted ──────────────────────────

    @Test
    fun `same operation name with a changed description is unadopted`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)
        val original = spec("delete_file", "Deletes a file", "path" to "string")
        store.recordAdoption("proj-1", "fs", original, "2026-08-17T00:00:00Z")

        // Description changed; name and schema shape did not.
        val changed = spec("delete_file", "Deletes a file permanently, bypassing trash", "path" to "string")

        val resolution = store.resolve("proj-1", "fs", listOf(changed))
        assertTrue(resolution.adopted.isEmpty(), "a changed descriptor must not ride the old adoption")
        assertEquals(listOf(changed), resolution.unadopted)
    }

    @Test
    fun `same operation name with a widened schema is unadopted even if description is unchanged`() {
        // This is the attack schema/project.sql's comment names explicitly: "a constant
        // description over a widened parameter is a real attack."
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)
        val narrow = spec("read_file", "Reads a file", "path" to "string")
        store.recordAdoption("proj-1", "fs", narrow, "2026-08-17T00:00:00Z")

        val widened = spec("read_file", "Reads a file", "path" to "string", "follow_symlinks" to "boolean")

        val resolution = store.resolve("proj-1", "fs", listOf(widened))
        assertTrue(resolution.adopted.isEmpty(), "a widened schema must not ride the old adoption")
        assertEquals(listOf(widened), resolution.unadopted)
    }

    // ─── unknown operation: unadopted ──────────────────────────────────────

    @Test
    fun `an operation with no adoption row at all is unadopted`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)
        val neverAdopted = spec("new_operation", "Does something new")

        val resolution = store.resolve("proj-1", "github", listOf(neverAdopted))
        assertTrue(resolution.adopted.isEmpty())
        assertEquals(listOf(neverAdopted), resolution.unadopted)
    }

    @Test
    fun `resolve never throws for an unadopted operation`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)
        // Should not throw, prompt, or otherwise interrupt -- just report it absent from adopted.
        val resolution = store.resolve("proj-1", "github", listOf(spec("mystery_op", "???")))
        assertTrue(resolution.adopted.isEmpty())
    }

    // ─── empty catalog ──────────────────────────────────────────────────────

    @Test
    fun `an empty catalog resolves to two empty lists`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)

        val resolution = store.resolve("proj-1", "github", emptyList())
        assertTrue(resolution.adopted.isEmpty())
        assertTrue(resolution.unadopted.isEmpty())
    }

    // ─── other coverage ─────────────────────────────────────────────────────

    @Test
    fun `adoption is scoped per project -- same server and operation, different project`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        driver.insertProject("proj-2")
        val store = McpOperationAdoptionStore(driver)
        val op = spec("list_issues", "Lists issues", "repo" to "string")

        store.recordAdoption("proj-1", "github", op, "2026-08-17T00:00:00Z")

        val resolution = store.resolve("proj-2", "github", listOf(op))
        assertTrue(resolution.adopted.isEmpty(), "adoption in one project must not leak into another")
        assertEquals(listOf(op), resolution.unadopted)
    }

    @Test
    fun `adoption is scoped per server -- same operation name, different server`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)
        val op = spec("search", "Searches", "q" to "string")

        store.recordAdoption("proj-1", "github", op, "2026-08-17T00:00:00Z")

        val resolution = store.resolve("proj-1", "slack", listOf(op))
        assertTrue(resolution.adopted.isEmpty(), "adoption of one server's operation must not adopt another server's same-named operation")
    }

    @Test
    fun `re-adopting the same descriptor is idempotent`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)
        val op = spec("list_issues", "Lists issues", "repo" to "string")

        store.recordAdoption("proj-1", "github", op, "2026-08-17T00:00:00Z")
        // A server reverting to a previously adopted descriptor should not error on re-adoption.
        store.recordAdoption("proj-1", "github", op, "2026-08-17T01:00:00Z")

        val resolution = store.resolve("proj-1", "github", listOf(op))
        assertEquals(listOf(op), resolution.adopted)
    }

    @Test
    fun `a mixed catalog splits correctly between adopted and unadopted`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)
        val adoptedOp = spec("list_issues", "Lists issues", "repo" to "string")
        val newOp = spec("close_issue", "Closes an issue", "id" to "string")
        store.recordAdoption("proj-1", "github", adoptedOp, "2026-08-17T00:00:00Z")

        val resolution = store.resolve("proj-1", "github", listOf(adoptedOp, newOp))
        assertEquals(listOf(adoptedOp), resolution.adopted)
        assertEquals(listOf(newOp), resolution.unadopted)
    }
}
