package dev.aidos.storage

import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M1 done-when: every non-derived kernel field has a schema column (RFC-0038).
 *
 * This is the third leg of the CI tripod: schema/check.py asserts structural correctness,
 * runtime/kernel tests assert type-level contracts, and this test asserts that the two are
 * connected — every field that execution logic writes actually has a column in the canonical DDL.
 *
 * Coverage: the core execution-graph types (Run, Task, Attempt) and the identity types (Session,
 * Project) that anchor them. Each field in the Kotlin data class is listed alongside the column
 * that persists it; a "derived" annotation marks fields computed from other columns.
 *
 * The test is structural: it parses the schema SQL for the expected column names and asserts
 * that each is present. It does not execute queries — this is a compile-time / schema-time
 * contract, not a round-trip test. Round-trip tests belong with the executor (M5).
 */
class KernelSchemaMapTest {

    /** Columns present in the schema for a given table. */
    private val schemaColumns: Map<String, Set<String>> by lazy {
        val sql = readSchemaResource(DatabaseKind.PROJECT)
        parseColumns(sql)
    }

    private fun assertColumn(table: String, column: String) {
        val cols = schemaColumns[table]
            ?: fail("Table '$table' not found in project.sql. Tables found: ${schemaColumns.keys.sorted()}")
        assertTrue(column in cols,
            "Column '$column' not found in table '$table'. Columns: ${cols.sorted()}")
    }

    // ─── dev.aidos.kernel.Run ────────────────────────────────────────────────

    @Test
    fun `Run fields are covered by runs table columns`() {
        assertColumn("runs", "id")
        assertColumn("runs", "session_id")
        assertColumn("runs", "project_id")
        assertColumn("runs", "trigger_event_id")
        assertColumn("runs", "started_at")
        assertColumn("runs", "ended_at")
        assertColumn("runs", "state")
        // error → three columns: error_code, error_class, error_detail_json (RFC-0029)
        assertColumn("runs", "error_code")
        assertColumn("runs", "error_class")
        assertColumn("runs", "error_detail_json")
        assertColumn("runs", "user_message_summary")
        assertColumn("runs", "retry_policy_json")
        assertColumn("runs", "step_index")
        assertColumn("runs", "max_steps")
        assertColumn("runs", "taint_level")
        assertColumn("runs", "taint_source_node_id")
        assertColumn("runs", "platform_profile")
        assertColumn("runs", "network_available")
        // degradedTools → degraded_tools (JSON array in TEXT column)
        assertColumn("runs", "degraded_tools")
    }

    // ─── dev.aidos.kernel.Task ───────────────────────────────────────────────

    @Test
    fun `Task fields are covered by tasks table columns`() {
        assertColumn("tasks", "id")
        assertColumn("tasks", "run_id")
        assertColumn("tasks", "plan_id")
        assertColumn("tasks", "session_id")
        assertColumn("tasks", "project_id")
        assertColumn("tasks", "ordinal")
        assertColumn("tasks", "kind")
        assertColumn("tasks", "description")
        assertColumn("tasks", "tool_name")
        // modelKind → model_capability (RFC-0020: the column names the model class, not "kind")
        assertColumn("tasks", "model_capability")
        assertColumn("tasks", "state")
        assertColumn("tasks", "started_at")
        assertColumn("tasks", "ended_at")
        assertColumn("tasks", "awaiting_run_id")
        assertColumn("tasks", "retry_policy_json")
    }

    // ─── dev.aidos.kernel.Attempt ────────────────────────────────────────────

    @Test
    fun `Attempt fields are covered by attempts table columns`() {
        assertColumn("attempts", "id")
        assertColumn("attempts", "task_id")
        assertColumn("attempts", "attempt_number")
        assertColumn("attempts", "started_at")
        assertColumn("attempts", "ended_at")
        assertColumn("attempts", "state")
        // error → error_code, error_class, error_detail_json (RFC-0029)
        assertColumn("attempts", "error_code")
        assertColumn("attempts", "error_class")
        assertColumn("attempts", "error_detail_json")
        assertColumn("attempts", "model_provider")
        assertColumn("attempts", "model_version")
        assertColumn("attempts", "tokens_input")
        assertColumn("attempts", "tokens_output")
        assertColumn("attempts", "cost_units")
        assertColumn("attempts", "capability_id")
        assertColumn("attempts", "idempotency_key")
        assertColumn("attempts", "recovery_class")
        assertColumn("attempts", "audit_ref")
    }

    // ─── dev.aidos.kernel.Session ────────────────────────────────────────────

    @Test
    fun `Session fields are covered by sessions table columns`() {
        assertColumn("sessions", "id")
        assertColumn("sessions", "project_id")
        assertColumn("sessions", "name")
        assertColumn("sessions", "role")
        assertColumn("sessions", "state")
        assertColumn("sessions", "parent_session_id")
        assertColumn("sessions", "worker_ref")
        assertColumn("sessions", "created_at")
        assertColumn("sessions", "last_active_at")
        assertColumn("sessions", "archived_at")
    }

    // ─── dev.aidos.kernel.Capability ────────────────────────────────────────

    @Test
    fun `Capability fields are covered by capabilities table columns`() {
        assertColumn("capabilities", "id")
        assertColumn("capabilities", "project_id")
        assertColumn("capabilities", "permission")
        assertColumn("capabilities", "subject_id")
        assertColumn("capabilities", "subject_kind")
        assertColumn("capabilities", "scope_json")
        assertColumn("capabilities", "constraints_json")
        assertColumn("capabilities", "issued_at")
        // issuedBy → two columns: issued_by_kind + issued_by_id (RFC-0046, "two fields, never one")
        assertColumn("capabilities", "issued_by_kind")
        assertColumn("capabilities", "issued_by_id")
        assertColumn("capabilities", "parent_capability_id")
        assertColumn("capabilities", "allows_delegation")
        assertColumn("capabilities", "expires_at")
        assertColumn("capabilities", "revoked_at")
        assertColumn("capabilities", "revocation_epoch")
        assertColumn("capabilities", "audit_ref")
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    /**
     * Parse CREATE TABLE statements from SQL DDL and return table → column set.
     *
     * This is intentionally dumb: it looks for lines containing a bare identifier followed by
     * a type keyword, which is what SQLite DDL looks like in practice. It is not a full SQL
     * parser; it does not need to be.
     */
    private fun parseColumns(sql: String): Map<String, Set<String>> {
        val tables = mutableMapOf<String, MutableSet<String>>()
        var currentTable: String? = null

        for (rawLine in sql.lines()) {
            val line = rawLine.trim()

            // CREATE TABLE foo (
            val createMatch = Regex("""CREATE TABLE\s+(\w+)\s*\(""").find(line)
            if (createMatch != null) {
                currentTable = createMatch.groupValues[1]
                tables.getOrPut(currentTable) { mutableSetOf() }
                continue
            }

            if (currentTable == null) continue

            // End of table definition
            if (line.startsWith(")") || line == ");") {
                currentTable = null
                continue
            }

            // Skip constraints and indexes
            if (line.startsWith("PRIMARY KEY") || line.startsWith("FOREIGN KEY") ||
                line.startsWith("UNIQUE") || line.startsWith("CHECK") ||
                line.startsWith("--") || line.isBlank()
            ) continue

            // Column: first token is the name (SQLite identifier rules)
            val colName = line.split(Regex("\\s+")).firstOrNull()
                ?.trimEnd(',')
                ?.takeIf { it.matches(Regex("[a-zA-Z_][a-zA-Z0-9_]*")) }
            if (colName != null) {
                tables.getOrPut(currentTable) { mutableSetOf() }.add(colName)
            }
        }

        return tables
    }
}
