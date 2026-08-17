package dev.aidos.mcp

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.mcp.core.McpToolSpec
import dev.aidos.mcp.policy.McpDescriptorHash
import kotlinx.serialization.json.Json
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

    /**
     * Splits [catalog] — the operation list just fetched from a live server — into the operations
     * that are adopted for `(projectId, serverName)` at their current descriptor hash, and those
     * that are not. Order is preserved from [catalog] within each list.
     */
    fun resolve(projectId: String, serverName: String, catalog: List<McpToolSpec>): McpAdoptionResolution {
        val adopted = mutableListOf<McpToolSpec>()
        val unadopted = mutableListOf<McpToolSpec>()
        for (spec in catalog) {
            val hash = McpDescriptorHash.hash(spec)
            if (isAdoptedAt(projectId, serverName, spec.name, hash)) {
                adopted.add(spec)
            } else {
                unadopted.add(spec)
            }
        }
        return McpAdoptionResolution(adopted = adopted, unadopted = unadopted)
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
                    val schema = runCatching {
                        Json.parseToJsonElement(cursor.getString(2) ?: "{}").jsonObject
                    }.getOrNull() ?: continue
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
}

/** [adopted] is what the model may be offered; [unadopted] must stay off the model's catalog (D31). */
data class McpAdoptionResolution(
    val adopted: List<McpToolSpec>,
    val unadopted: List<McpToolSpec>,
)
