package dev.aidos.mcp

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.mcp.policy.McpDescriptorHash
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Reads and writes `mcp_operation_adoptions` (project scope, `schema/project.sql`; RFC-0031 "What
 * the model is shown: fenced prose, adopted per operation"; D31).
 *
 * **The security-critical behavior lives in [resolve]:** an operation whose *current* descriptor
 * hash — computed fresh from the catalog just fetched from the server, via [McpDescriptorHash] —
 * has no matching row for `(project_id, server_name, operation_name, descriptor_hash)` is treated
 * as **not adopted**. That covers three cases identically and on purpose: an operation the user has
 * never seen, one whose description or schema changed since it was adopted (the table's own comment:
 * *"a constant description over a widened parameter is a real attack"*), and one the server stopped
 * advertising and a different one now answers to the same name. Per D31 an unadopted operation is
 * simply **absent** from what [resolve] returns as adopted — [resolve] never throws for this reason,
 * never prompts, and never falls back to admitting an operation "just this once". A Run may be
 * executing unattended or on a phone with the screen off; third-party prose is not allowed a lever
 * that interrupts it (RFC-0031, "Nothing about a descriptor ever interrupts a Run").
 *
 * **Write path.** [recordAdoption] exists so *something* can persist a "the user has seen this" row
 * — RFC-0031 places that action at enable time, in a UI/CLI flow this class does not build. Nothing
 * in this class calls [recordAdoption] on [resolve]'s behalf: auto-adoption would defeat the exact
 * mechanism this table exists to enforce, so the write path is provided and never wired to the read
 * path here.
 *
 * `descriptor_hash` is part of the primary key (`schema/project.sql`), so re-adopting a descriptor
 * whose hash is already on record — a server reverting to a previously adopted version — is a no-op
 * rather than a duplicate-row error; [recordAdoption] uses `INSERT OR IGNORE` for exactly that.
 */
class McpOperationAdoptionStore(private val projectDriver: SqlDriver) {

    private val transacter = object : TransacterImpl(projectDriver) {}

    /**
     * Splits [catalog] — the operation list just fetched from a live server — into the operations
     * that are adopted for `(projectId, serverName)` at their current descriptor hash, and those
     * that are not. Order is preserved from [catalog] within each list.
     *
     * If a stored row predates descriptor-hash canonicalization or the Kotlin MCP SDK's
     * `ToolSchema` 5-key projection (`$schema`, `type`, `properties`, `required`, `$defs`),
     * [resolve] recomputes the hash from the persisted `description` and `input_schema_json`
     * (projected via [McpDescriptorHash.normalizeSdkInputSchema]). When the recomputed hash
     * matches the live [McpToolSpec]'s hash, the stored row is migrated in place rather than
     * un-adopting the operation. Any change to `description` or modeled schema parameters still
     * produces a different hash and remains unadopted.
     */
    fun resolve(projectId: String, serverName: String, catalog: List<McpToolSpec>): McpAdoptionResolution {
        val adopted = mutableListOf<McpToolSpec>()
        val unadopted = mutableListOf<McpToolSpec>()
        for (spec in catalog) {
            val hash = McpDescriptorHash.hash(spec)
            if (isAdoptedAt(projectId, serverName, spec.name, hash) ||
                migrateMatchingRowIfPresent(projectId, serverName, spec.name, hash)
            ) {
                adopted.add(spec)
            } else {
                unadopted.add(spec)
            }
        }
        return McpAdoptionResolution(adopted = adopted, unadopted = unadopted)
    }

    /**
     * Recomputes `descriptor_hash` from the persisted `(operation_name, description, input_schema_json)`
     * for rows stored before descriptor canonicalization or before the Kotlin MCP SDK migration
     * (RFC-0031).
     *
     * When [normalizeSdkSchemas] is true (the default), persisted JSON Schema objects carrying
     * unmodeled top-level keywords (`additionalProperties`, `title`, `$id`, ...) are projected onto
     * the SDK's `ToolSchema` representation via [McpDescriptorHash.normalizeSdkInputSchema] before
     * hashing, preventing mass un-adoption on SDK upgrade while preserving parameter-level integrity.
     *
     * Returns the number of rows whose `descriptor_hash` (or normalized `input_schema_json`) was updated.
     */
    fun migrateAdoptionHashes(
        projectId: String? = null,
        normalizeSdkSchemas: Boolean = true,
    ): Int {
        val rows = readStoredRows(projectId)
        var migrated = 0
        transacter.transaction {
            for (row in rows) {
                val parsedSchema = parseSchemaOrNull(row.inputSchemaJson) ?: continue
                val targetSchema = if (normalizeSdkSchemas) {
                    McpDescriptorHash.normalizeSdkInputSchema(parsedSchema)
                } else {
                    parsedSchema
                }
                val recomputedHash = McpDescriptorHash.hash(row.operationName, row.description, targetSchema)
                val targetSchemaJson = targetSchema.toString()
                if (row.descriptorHash != recomputedHash || row.inputSchemaJson != targetSchemaJson) {
                    replaceRow(row, recomputedHash, targetSchemaJson)
                    migrated++
                }
            }
        }
        return migrated
    }

    /**
     * Records that the user has seen [spec] as it stands right now for `(projectId, serverName)`.
     *
     * Callers: an enable-time flow (not built here) after showing the descriptor to the user, or a
     * test. Never call this to make [resolve] pass without the user having actually seen the prose
     * — that is precisely the adoption mechanism this store exists to enforce, not a formality
     * around it.
     */
    fun recordAdoption(
        projectId: String,
        serverName: String,
        spec: McpToolSpec,
        adoptedAtIso: String,
    ) {
        val hash = McpDescriptorHash.hash(spec)
        projectDriver.execute(
            identifier = null,
            sql = "INSERT OR IGNORE INTO mcp_operation_adoptions " +
                "(project_id, server_name, operation_name, descriptor_hash, " +
                "description, input_schema_json, adopted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            parameters = 7,
        ) {
            bindString(0, projectId)
            bindString(1, serverName)
            bindString(2, spec.name)
            bindString(3, hash)
            // The descriptor itself, not just its hash: this is what the user read and approved,
            // and it is what lets adopted operations be offered without connecting (RFC-0031,
            // "Adopted descriptors are persisted").
            bindString(4, spec.description)
            bindString(5, spec.inputSchema.toString())
            bindString(6, adoptedAtIso)
        }
    }

    fun unadopt(projectId: String, serverName: String, operationName: String) {
        projectDriver.execute(
            identifier = null,
            sql = "DELETE FROM mcp_operation_adoptions WHERE project_id = ? AND server_name = ? AND operation_name = ?",
            parameters = 3,
        ) {
            bindString(0, projectId)
            bindString(1, serverName)
            bindString(2, operationName)
        }
    }

    /**
     * The adopted catalog for `(projectId, serverName)`, rebuilt from storage alone.
     *
     * This is the path that makes D30 satisfiable: descriptors come from here at project open, and
     * a connection happens only when an operation is actually called. It is also what keeps an
     * unreachable server describable — the call fails, the catalog does not vanish.
     *
     * A stored row whose `input_schema_json` no longer parses is skipped rather than failing the
     * whole load, since one corrupt row should not withdraw a server's other adopted operations.
     */
    fun adoptedCatalog(projectId: String, serverName: String): List<McpToolSpec> =
        projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT operation_name, description, input_schema_json " +
                "FROM mcp_operation_adoptions WHERE project_id = ? AND server_name = ? " +
                "ORDER BY operation_name",
            mapper = { cursor ->
                val specs = mutableListOf<McpToolSpec>()
                while (cursor.next().value) {
                    val name = cursor.getString(0) ?: continue
                    val description = cursor.getString(1) ?: continue
                    val schema = parseSchemaOrNull(cursor.getString(2)) ?: continue
                    specs.add(McpToolSpec(name = name, description = description, inputSchema = schema))
                }
                QueryResult.Value(specs)
            },
            parameters = 2,
        ) {
            bindString(0, projectId)
            bindString(1, serverName)
        }.value

    private fun isAdoptedAt(
        projectId: String,
        serverName: String,
        operationName: String,
        descriptorHash: String,
    ): Boolean =
        projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT 1 FROM mcp_operation_adoptions " +
                "WHERE project_id = ? AND server_name = ? AND operation_name = ? AND descriptor_hash = ?",
            mapper = { cursor -> QueryResult.Value(cursor.next().value) },
            parameters = 4,
        ) {
            bindString(0, projectId)
            bindString(1, serverName)
            bindString(2, operationName)
            bindString(3, descriptorHash)
        }.value

    private fun migrateMatchingRowIfPresent(
        projectId: String,
        serverName: String,
        operationName: String,
        expectedHash: String,
    ): Boolean {
        val candidates = readStoredRowsForOperation(projectId, serverName, operationName)
        for (row in candidates) {
            val parsedSchema = parseSchemaOrNull(row.inputSchemaJson) ?: continue
            val directHash = McpDescriptorHash.hash(row.operationName, row.description, parsedSchema)
            if (directHash == expectedHash) {
                transacter.transaction {
                    replaceRow(row, expectedHash, row.inputSchemaJson)
                }
                return true
            }
            val normalizedSchema = McpDescriptorHash.normalizeSdkInputSchema(parsedSchema)
            val normalizedHash = McpDescriptorHash.hash(row.operationName, row.description, normalizedSchema)
            if (normalizedHash == expectedHash) {
                transacter.transaction {
                    replaceRow(row, expectedHash, normalizedSchema.toString())
                }
                return true
            }
        }
        return false
    }

    private fun replaceRow(row: StoredAdoptionRow, newHash: String, newSchemaJson: String) {
        projectDriver.execute(
            identifier = null,
            sql = "DELETE FROM mcp_operation_adoptions " +
                "WHERE project_id = ? AND server_name = ? AND operation_name = ? AND descriptor_hash = ?",
            parameters = 4,
        ) {
            bindString(0, row.projectId)
            bindString(1, row.serverName)
            bindString(2, row.operationName)
            bindString(3, row.descriptorHash)
        }
        projectDriver.execute(
            identifier = null,
            sql = "INSERT OR IGNORE INTO mcp_operation_adoptions " +
                "(project_id, server_name, operation_name, descriptor_hash, " +
                "description, input_schema_json, adopted_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)",
            parameters = 7,
        ) {
            bindString(0, row.projectId)
            bindString(1, row.serverName)
            bindString(2, row.operationName)
            bindString(3, newHash)
            bindString(4, row.description)
            bindString(5, newSchemaJson)
            bindString(6, row.adoptedAt)
        }
    }

    private fun readStoredRows(projectId: String?): List<StoredAdoptionRow> {
        val sql = if (projectId != null) {
            "SELECT project_id, server_name, operation_name, descriptor_hash, description, input_schema_json, adopted_at " +
                "FROM mcp_operation_adoptions WHERE project_id = ?"
        } else {
            "SELECT project_id, server_name, operation_name, descriptor_hash, description, input_schema_json, adopted_at " +
                "FROM mcp_operation_adoptions"
        }
        return projectDriver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = ::mapStoredRows,
            parameters = if (projectId != null) 1 else 0,
        ) {
            if (projectId != null) bindString(0, projectId)
        }.value
    }

    private fun readStoredRowsForOperation(
        projectId: String,
        serverName: String,
        operationName: String,
    ): List<StoredAdoptionRow> =
        projectDriver.executeQuery(
            identifier = null,
            sql = "SELECT project_id, server_name, operation_name, descriptor_hash, description, input_schema_json, adopted_at " +
                "FROM mcp_operation_adoptions WHERE project_id = ? AND server_name = ? AND operation_name = ?",
            mapper = ::mapStoredRows,
            parameters = 3,
        ) {
            bindString(0, projectId)
            bindString(1, serverName)
            bindString(2, operationName)
        }.value

    private fun mapStoredRows(cursor: app.cash.sqldelight.db.SqlCursor): QueryResult.Value<List<StoredAdoptionRow>> {
        val rows = mutableListOf<StoredAdoptionRow>()
        while (cursor.next().value) {
            val proj = cursor.getString(0) ?: continue
            val srv = cursor.getString(1) ?: continue
            val op = cursor.getString(2) ?: continue
            val hash = cursor.getString(3) ?: continue
            val desc = cursor.getString(4) ?: continue
            val schema = cursor.getString(5) ?: continue
            val at = cursor.getString(6) ?: continue
            rows.add(StoredAdoptionRow(proj, srv, op, hash, desc, schema, at))
        }
        return QueryResult.Value(rows)
    }

    private fun parseSchemaOrNull(raw: String?): JsonObject? {
        if (raw.isNullOrBlank()) return null
        return runCatching { Json.parseToJsonElement(raw).jsonObject }.getOrNull()
    }

    private data class StoredAdoptionRow(
        val projectId: String,
        val serverName: String,
        val operationName: String,
        val descriptorHash: String,
        val description: String,
        val inputSchemaJson: String,
        val adoptedAt: String,
    )
}

/** [adopted] is what the model may be offered; [unadopted] must stay off the model's catalog (D31). */
data class McpAdoptionResolution(
    val adopted: List<McpToolSpec>,
    val unadopted: List<McpToolSpec>,
)
