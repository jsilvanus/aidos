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

    // ─── the stored descriptor, rebuilt without connecting ──────────────────

    @Test
    fun `adoptedCatalog rebuilds the descriptor from storage alone`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)
        val op = spec("list_issues", "Lists issues", "repo" to "string")

        store.recordAdoption("proj-1", "github", op, "2026-08-17T00:00:00Z")

        // Nothing here connects to a server: description and inputSchema come back out of the
        // database. That is what lets descriptors exist at project open under D30, and what keeps
        // an unreachable server's operations describable.
        val catalog = store.adoptedCatalog("proj-1", "github")
        assertEquals(listOf(op), catalog)
        assertEquals("Lists issues", catalog.single().description)
        assertEquals(op.inputSchema, catalog.single().inputSchema)
    }

    @Test
    fun `adoptedCatalog round-trips through the hash it was adopted at`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)
        val op = spec("search", "Searches", "q" to "string")
        store.recordAdoption("proj-1", "github", op, "2026-08-17T00:00:00Z")

        // A descriptor restored from storage must still resolve as adopted -- if the stored form
        // hashed differently from the live one, every restart would silently withdraw everything.
        val restored = store.adoptedCatalog("proj-1", "github")
        assertEquals(listOf(op), store.resolve("proj-1", "github", restored).adopted)
    }

    @Test
    fun `adoptedCatalog is scoped to its project and server`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        driver.insertProject("proj-2")
        val store = McpOperationAdoptionStore(driver)
        store.recordAdoption("proj-1", "github", spec("a", "A"), "2026-08-17T00:00:00Z")

        assertTrue(store.adoptedCatalog("proj-2", "github").isEmpty(), "another project must not inherit adoptions")
        assertTrue(store.adoptedCatalog("proj-1", "gitlab").isEmpty(), "another server must not inherit adoptions")
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

    // ─── Task 3: Adoption-hash migration across canonical / SDK upgrades ───

    @Test
    fun `migrateAdoptionHashes updates legacy non-canonical descriptor_hash from stored columns`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)

        // Simulate a legacy row written before canonical sorted-key hashing or before SDK
        // ToolSchema projection (with top-level additionalProperties / title that SDK ToolSchema
        // omits, plus a stale/legacy descriptor_hash).
        val rawSchemaJson = """{"title":"EchoArgs","type":"object","additionalProperties":false,"properties":{"z":{"type":"string"},"a":{"type":"integer"}},"required":["a"]}"""
        driver.execute(
            identifier = null,
            sql = "INSERT INTO mcp_operation_adoptions " +
                "(project_id, server_name, operation_name, descriptor_hash, description, input_schema_json, adopted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            parameters = 7,
        ) {
            bindString(0, "proj-1")
            bindString(1, "legacy-mcp")
            bindString(2, "echo")
            bindString(3, "legacy-pre-canonical-hash")
            bindString(4, "Echoes structured args")
            bindString(5, rawSchemaJson)
            bindString(6, "2026-08-17T00:00:00Z")
        }

        val migratedCount = store.migrateAdoptionHashes("proj-1")
        assertEquals(1, migratedCount, "legacy row should be migrated in place")
        assertEquals(0, store.migrateAdoptionHashes("proj-1"), "second migration pass is idempotent")

        // When the Kotlin MCP SDK connects to the same server, its ToolSchema representation
        // contains only type/properties/required. It must resolve as adopted without mass un-adoption!
        val sdkLiveSpec = McpToolSpec(
            name = "echo",
            description = "Echoes structured args",
            inputSchema = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"type":"object","properties":{"a":{"type":"integer"},"z":{"type":"string"}},"required":["a"]}"""
            ) as kotlinx.serialization.json.JsonObject,
        )
        val resolution = store.resolve("proj-1", "legacy-mcp", listOf(sdkLiveSpec))
        assertEquals(listOf(sdkLiveSpec), resolution.adopted)
        assertTrue(resolution.unadopted.isEmpty())
    }

    @Test
    fun `resolve migrates legacy row in place even if migrateAdoptionHashes was not called upfront`() {
        val driver = openProjectDb()
        driver.insertProject("proj-1")
        val store = McpOperationAdoptionStore(driver)

        val rawSchemaJson = """{"title":"SearchArgs","type":"object","properties":{"query":{"type":"string"}}}"""
        driver.execute(
            identifier = null,
            sql = "INSERT INTO mcp_operation_adoptions " +
                "(project_id, server_name, operation_name, descriptor_hash, description, input_schema_json, adopted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            parameters = 7,
        ) {
            bindString(0, "proj-1")
            bindString(1, "legacy-mcp")
            bindString(2, "search")
            bindString(3, "stale-pre-upgrade-hash")
            bindString(4, "Searches items")
            bindString(5, rawSchemaJson)
            bindString(6, "2026-08-17T00:00:00Z")
        }

        val sdkLiveSpec = McpToolSpec(
            name = "search",
            description = "Searches items",
            inputSchema = kotlinx.serialization.json.Json.parseToJsonElement(
                """{"type":"object","properties":{"query":{"type":"string"}}}"""
            ) as kotlinx.serialization.json.JsonObject,
        )
        val resolution = store.resolve("proj-1", "legacy-mcp", listOf(sdkLiveSpec))
        assertEquals(listOf(sdkLiveSpec), resolution.adopted)
        assertTrue(resolution.unadopted.isEmpty())
    }
}
