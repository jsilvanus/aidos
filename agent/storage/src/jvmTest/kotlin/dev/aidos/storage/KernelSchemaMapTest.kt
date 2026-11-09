package dev.aidos.storage

import dev.aidos.kernel.Attempt
import dev.aidos.kernel.Budget
import dev.aidos.kernel.Capability
import dev.aidos.kernel.ContentNode
import dev.aidos.kernel.ContextItem
import dev.aidos.kernel.DegradationEvent
import dev.aidos.kernel.ModelDescriptor
import dev.aidos.kernel.ProjectSummary
import dev.aidos.kernel.ProvenanceEdge
import dev.aidos.kernel.ProviderRetention
import dev.aidos.kernel.Run
import dev.aidos.kernel.ScheduledJob
import dev.aidos.kernel.SessionSummary
import dev.aidos.kernel.Task
import dev.aidos.kernel.ToolCall
import java.lang.reflect.Modifier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * M1 / PIPELINE.md §6 done-when: every non-derived kernel field has a schema column (RFC-0038).
 *
 * This is the third leg of the CI tripod: schema/check.py asserts structural correctness,
 * runtime/kernel tests assert type-level contracts, and this test asserts that the two are
 * connected — every field on a persisted kernel data class is explicitly mapped to a column in
 * the canonical DDL (`project.sql`, `user.sql`, `vault.sql`) or declared as derived with its
 * derivation rule.
 *
 * Reflection over `Class.declaredFields` ensures that adding a field to any persisted kernel
 * data class without updating the schema and this contract immediately fails the build.
 */
class KernelSchemaMapTest {

    private data class ColumnRef(
        val db: DatabaseKind,
        val table: String,
        val column: String,
    )

    private sealed interface FieldMapping {
        /** Backed by one or more canonical schema columns. */
        data class Columns(val refs: List<ColumnRef>) : FieldMapping {
            constructor(db: DatabaseKind, table: String, vararg columns: String) :
                this(columns.map { ColumnRef(db, table, it) })
        }

        /** Computed at query/projection time rather than stored in its own column. */
        data class Derived(val rationale: String) : FieldMapping
    }

    /** Columns present in each canonical schema file (`user.sql`, `vault.sql`, `project.sql`). */
    private val schemaColumnsByDb: Map<DatabaseKind, Map<String, Set<String>>> by lazy {
        DatabaseKind.entries.associateWith { kind ->
            parseColumns(readSchemaResource(kind))
        }
    }

    private fun projectCols(table: String, vararg columns: String): FieldMapping =
        FieldMapping.Columns(DatabaseKind.PROJECT, table, *columns)

    private fun userCols(table: String, vararg columns: String): FieldMapping =
        FieldMapping.Columns(DatabaseKind.USER, table, *columns)

    private fun derived(rationale: String): FieldMapping =
        FieldMapping.Derived(rationale)

    private fun assertColumn(ref: ColumnRef) {
        val dbTables = schemaColumnsByDb[ref.db]
            ?: fail("No parsed schema for ${ref.db}")
        val cols = dbTables[ref.table]
            ?: fail("Table '${ref.table}' not found in ${ref.db}. Tables found: ${dbTables.keys.sorted()}")
        assertTrue(
            ref.column in cols,
            "Column '${ref.column}' not found in ${ref.db} table '${ref.table}'. Columns: ${cols.sorted()}",
        )
    }

    /**
     * Asserts that:
     * 1. Every non-static, non-synthetic field declared on [clazz] is present in [mappings].
     * 2. No stale field names exist in [mappings] that were removed from [clazz].
     * 3. Every [FieldMapping.Columns] target exists in the canonical SQL DDL.
     */
    private fun <T : Any> assertKernelClassMapped(
        clazz: Class<T>,
        mappings: Map<String, FieldMapping>,
    ) {
        val actualFields = clazz.declaredFields
            .filterNot { Modifier.isStatic(it.modifiers) || it.isSynthetic }
            .map { it.name }
            .toSet()

        val unmapped = actualFields - mappings.keys
        val stale = mappings.keys - actualFields

        assertEquals(
            emptySet(),
            unmapped,
            "${clazz.name} has unmapped kernel fields: $unmapped. " +
                "Every non-derived kernel field must map to a canonical column in schema/.",
        )
        assertEquals(
            emptySet(),
            stale,
            "${clazz.name} mapping references fields that no longer exist on the class: $stale",
        )

        for ((fieldName, mapping) in mappings) {
            when (mapping) {
                is FieldMapping.Columns -> {
                    assertTrue(
                        mapping.refs.isNotEmpty(),
                        "${clazz.simpleName}.$fieldName has an empty column mapping",
                    )
                    mapping.refs.forEach(::assertColumn)
                }
                is FieldMapping.Derived -> {
                    assertTrue(
                        mapping.rationale.isNotBlank(),
                        "${clazz.simpleName}.$fieldName is marked derived but has no rationale",
                    )
                }
            }
        }
    }

    // ─── dev.aidos.kernel.Run ────────────────────────────────────────────────

    @Test
    fun `Run fields are exhaustively covered by runs table columns`() {
        assertKernelClassMapped(
            Run::class.java,
            mapOf(
                "id" to projectCols("runs", "id"),
                "sessionId" to projectCols("runs", "session_id"),
                "projectId" to projectCols("runs", "project_id"),
                "triggerEventId" to projectCols("runs", "trigger_event_id"),
                "startedAt" to projectCols("runs", "started_at"),
                "endedAt" to projectCols("runs", "ended_at"),
                "state" to projectCols("runs", "state"),
                // error → three columns: error_code, error_class, error_detail_json (RFC-0029)
                "error" to projectCols("runs", "error_code", "error_class", "error_detail_json"),
                "userMessageSummary" to projectCols("runs", "user_message_summary"),
                "retryPolicy" to projectCols("runs", "retry_policy_json"),
                "stepIndex" to projectCols("runs", "step_index"),
                "maxSteps" to projectCols("runs", "max_steps"),
                "taintLevel" to projectCols("runs", "taint_level"),
                "taintSourceNodeId" to projectCols("runs", "taint_source_node_id"),
                "platformProfile" to projectCols("runs", "platform_profile"),
                "networkAvailable" to projectCols("runs", "network_available"),
                "degradedTools" to projectCols("runs", "degraded_tools"),
            ),
        )
    }

    // ─── dev.aidos.kernel.Task ───────────────────────────────────────────────

    @Test
    fun `Task fields are exhaustively covered by tasks table columns`() {
        assertKernelClassMapped(
            Task::class.java,
            mapOf(
                "id" to projectCols("tasks", "id"),
                "runId" to projectCols("tasks", "run_id"),
                "planId" to projectCols("tasks", "plan_id"),
                "sessionId" to projectCols("tasks", "session_id"),
                "projectId" to projectCols("tasks", "project_id"),
                "ordinal" to projectCols("tasks", "ordinal"),
                "kind" to projectCols("tasks", "kind"),
                "description" to projectCols("tasks", "description"),
                "toolName" to projectCols("tasks", "tool_name"),
                // modelKind → model_capability (RFC-0020)
                "modelKind" to projectCols("tasks", "model_capability"),
                "state" to projectCols("tasks", "state"),
                "startedAt" to projectCols("tasks", "started_at"),
                "endedAt" to projectCols("tasks", "ended_at"),
                "awaitingRunId" to projectCols("tasks", "awaiting_run_id"),
                "retryPolicy" to projectCols("tasks", "retry_policy_json"),
                "approvalChannel" to projectCols("tasks", "approval_channel"),
                "approvalPhrase" to projectCols("tasks", "approval_phrase"),
            ),
        )
    }

    // ─── dev.aidos.kernel.Attempt ────────────────────────────────────────────

    @Test
    fun `Attempt fields are exhaustively covered by attempts table columns`() {
        assertKernelClassMapped(
            Attempt::class.java,
            mapOf(
                "id" to projectCols("attempts", "id"),
                "taskId" to projectCols("attempts", "task_id"),
                "attemptNumber" to projectCols("attempts", "attempt_number"),
                "startedAt" to projectCols("attempts", "started_at"),
                "endedAt" to projectCols("attempts", "ended_at"),
                "state" to projectCols("attempts", "state"),
                // error → error_code, error_class, error_detail_json (RFC-0029)
                "error" to projectCols("attempts", "error_code", "error_class", "error_detail_json"),
                "modelProvider" to projectCols("attempts", "model_provider"),
                "modelVersion" to projectCols("attempts", "model_version"),
                "tokensInput" to projectCols("attempts", "tokens_input"),
                "tokensOutput" to projectCols("attempts", "tokens_output"),
                "costUnits" to projectCols("attempts", "cost_units"),
                "capabilityId" to projectCols("attempts", "capability_id"),
                "idempotencyKey" to projectCols("attempts", "idempotency_key"),
                "recoveryClass" to projectCols("attempts", "recovery_class"),
                "auditRef" to projectCols("attempts", "audit_ref"),
            ),
        )
    }

    // ─── dev.aidos.kernel.Capability ─────────────────────────────────────────

    @Test
    fun `Capability fields are exhaustively covered by capabilities table columns`() {
        assertKernelClassMapped(
            Capability::class.java,
            mapOf(
                "id" to projectCols("capabilities", "id"),
                "projectId" to projectCols("capabilities", "project_id"),
                "permission" to projectCols("capabilities", "permission"),
                "subjectId" to projectCols("capabilities", "subject_id"),
                "subjectKind" to projectCols("capabilities", "subject_kind"),
                "scope" to projectCols("capabilities", "scope_json"),
                "constraints" to projectCols("capabilities", "constraints_json"),
                "issuedAt" to projectCols("capabilities", "issued_at"),
                // issuedBy → issued_by_kind + issued_by_id (RFC-0046, "two fields, never one")
                "issuedBy" to projectCols("capabilities", "issued_by_kind", "issued_by_id"),
                "parentCapabilityId" to projectCols("capabilities", "parent_capability_id"),
                "allowsDelegation" to projectCols("capabilities", "allows_delegation"),
                "expiresAt" to projectCols("capabilities", "expires_at"),
                "revokedAt" to projectCols("capabilities", "revoked_at"),
                "revocationEpoch" to projectCols("capabilities", "revocation_epoch"),
                "auditRef" to projectCols("capabilities", "audit_ref"),
            ),
        )
    }

    // ─── dev.aidos.kernel.ContentNode & ProvenanceEdge ───────────────────────

    @Test
    fun `ContentNode fields are exhaustively covered by content_nodes table columns`() {
        assertKernelClassMapped(
            ContentNode::class.java,
            mapOf(
                "id" to projectCols("content_nodes", "id"),
                "projectId" to projectCols("content_nodes", "project_id"),
                "kind" to projectCols("content_nodes", "kind"),
                "name" to projectCols("content_nodes", "name"),
                "description" to projectCols("content_nodes", "description"),
                "mutabilityPolicy" to projectCols("content_nodes", "mutability_policy"),
                "sensitivityLevel" to projectCols("content_nodes", "sensitivity_level"),
                "egressEligibility" to projectCols("content_nodes", "egress_eligibility"),
                "trustLevel" to projectCols("content_nodes", "trust_level"),
                "storageLocation" to projectCols("content_nodes", "storage_location_json"),
                "contentHash" to projectCols("content_nodes", "content_hash"),
                "contentType" to projectCols("content_nodes", "content_type"),
                "sizeBytes" to projectCols("content_nodes", "size_bytes"),
                "createdAt" to projectCols("content_nodes", "created_at"),
                "createdByKind" to projectCols("content_nodes", "created_by_kind"),
                "createdById" to projectCols("content_nodes", "created_by_id"),
                "updatedAt" to projectCols("content_nodes", "updated_at"),
                "updatedByKind" to projectCols("content_nodes", "updated_by_kind"),
                "updatedById" to projectCols("content_nodes", "updated_by_id"),
                "contentVersion" to projectCols("content_nodes", "content_version"),
                "rowVersion" to projectCols("content_nodes", "row_version"),
                "state" to projectCols("content_nodes", "state"),
                "tags" to projectCols("content_nodes", "tags"),
            ),
        )
    }

    @Test
    fun `ProvenanceEdge fields are exhaustively covered by provenance_edges table columns`() {
        assertKernelClassMapped(
            ProvenanceEdge::class.java,
            mapOf(
                "id" to projectCols("provenance_edges", "id"),
                "fromNodeId" to projectCols("provenance_edges", "from_node_id"),
                "toNodeId" to projectCols("provenance_edges", "to_node_id"),
                "edgeKind" to projectCols("provenance_edges", "edge_kind"),
                "createdAt" to projectCols("provenance_edges", "created_at"),
                "createdByRunId" to projectCols("provenance_edges", "created_by_run_id"),
            ),
        )
    }

    // ─── dev.aidos.kernel.DegradationEvent ───────────────────────────────────

    @Test
    fun `DegradationEvent fields are exhaustively covered by degradation_events table columns`() {
        assertKernelClassMapped(
            DegradationEvent::class.java,
            mapOf(
                "id" to projectCols("degradation_events", "id"),
                "rung" to projectCols("degradation_events", "rung"),
                "trigger" to projectCols("degradation_events", "trigger"),
                "enteredAt" to projectCols("degradation_events", "entered_at"),
                "exitedAt" to projectCols("degradation_events", "exited_at"),
                "projectId" to projectCols("degradation_events", "project_id"),
            ),
        )
    }

    // ─── dev.aidos.kernel.ScheduledJob ───────────────────────────────────────

    @Test
    fun `ScheduledJob fields are exhaustively covered by scheduled_jobs table columns`() {
        assertKernelClassMapped(
            ScheduledJob::class.java,
            mapOf(
                "id" to projectCols("scheduled_jobs", "id"),
                "projectId" to projectCols("scheduled_jobs", "project_id"),
                "sessionId" to projectCols("scheduled_jobs", "session_id"),
                "name" to projectCols("scheduled_jobs", "name"),
                "trigger" to projectCols("scheduled_jobs", "trigger_json"),
                "guaranteeClass" to projectCols("scheduled_jobs", "guarantee_class"),
                "workClass" to projectCols("scheduled_jobs", "work_class"),
                "constraintsJson" to projectCols("scheduled_jobs", "constraints_json"),
                "enabled" to projectCols("scheduled_jobs", "enabled"),
                "nextRunAt" to projectCols("scheduled_jobs", "next_run_at"),
                "lastRunAt" to projectCols("scheduled_jobs", "last_run_at"),
                "lastOutcome" to projectCols("scheduled_jobs", "last_outcome"),
                "consecutiveFailures" to projectCols("scheduled_jobs", "consecutive_failures"),
                "missedOccurrences" to projectCols("scheduled_jobs", "missed_occurrences"),
                "createdAt" to projectCols("scheduled_jobs", "created_at"),
            ),
        )
    }

    // ─── dev.aidos.kernel.Budget ─────────────────────────────────────────────

    @Test
    fun `Budget fields are exhaustively covered by budget_ledger table columns`() {
        assertKernelClassMapped(
            Budget::class.java,
            mapOf(
                "modelCalls" to projectCols("budget_ledger", "model_calls"),
                "inputTokens" to projectCols("budget_ledger", "input_tokens"),
                "outputTokens" to projectCols("budget_ledger", "output_tokens"),
                "costUnits" to projectCols("budget_ledger", "cost_units"),
                "steps" to projectCols("budget_ledger", "steps"),
                "toolInvocations" to projectCols("budget_ledger", "tool_invocations"),
                // wallClockSeconds is a ceiling inside limit_json / constraints_json, not an additive ledger counter
                "wallClockSeconds" to projectCols("budget_ledger", "limit_json"),
            ),
        )
    }

    // ─── dev.aidos.kernel.ContextItem ────────────────────────────────────────

    @Test
    fun `ContextItem fields are exhaustively covered by prompt_provenance table columns`() {
        assertKernelClassMapped(
            ContextItem::class.java,
            mapOf(
                "contentNodeId" to projectCols("prompt_provenance", "content_node_id"),
                "kind" to projectCols("prompt_provenance", "role"),
                "content" to derived("Resolved from content_nodes storage; prompt_provenance records references, never bulk text (RFC-0025)"),
                "relevanceScore" to projectCols("prompt_provenance", "relevance_score"),
                "tokenCount" to projectCols("prompt_provenance", "token_count"),
                "trustLevel" to projectCols("prompt_provenance", "trust_level"),
                // dropped is persisted as included = !dropped
                "dropped" to projectCols("prompt_provenance", "included"),
            ),
        )
    }

    // ─── dev.aidos.kernel.ProjectSummary & SessionSummary ────────────────────

    @Test
    fun `ProjectSummary fields are exhaustively covered by projects table columns and derived aggregates`() {
        assertKernelClassMapped(
            ProjectSummary::class.java,
            mapOf(
                "id" to projectCols("projects", "id"),
                "name" to projectCols("projects", "name"),
                "description" to projectCols("projects", "description"),
                "createdAt" to projectCols("projects", "created_at"),
                "lastActiveAt" to projectCols("projects", "updated_at"),
                "sessionCount" to derived("Computed via COUNT(*) FROM sessions WHERE project_id = projects.id"),
            ),
        )
    }

    @Test
    fun `SessionSummary and Session fields are exhaustively covered by sessions table columns`() {
        assertKernelClassMapped(
            SessionSummary::class.java,
            mapOf(
                "id" to projectCols("sessions", "id"),
                "projectId" to projectCols("sessions", "project_id"),
                "name" to projectCols("sessions", "name"),
                "role" to projectCols("sessions", "role"),
                "state" to projectCols("sessions", "state"),
                "createdAt" to projectCols("sessions", "created_at"),
                "lastActiveAt" to projectCols("sessions", "last_active_at"),
                "runCount" to derived("Computed via COUNT(*) FROM runs WHERE session_id = sessions.id"),
            ),
        )
        // Also assert worker/hierarchy columns on sessions table used by runtime Session records
        assertColumn(ColumnRef(DatabaseKind.PROJECT, "sessions", "parent_session_id"))
        assertColumn(ColumnRef(DatabaseKind.PROJECT, "sessions", "worker_ref"))
        assertColumn(ColumnRef(DatabaseKind.PROJECT, "sessions", "archived_at"))
    }

    // ─── dev.aidos.kernel.ModelDescriptor & ProviderRetention ────────────────

    @Test
    fun `ModelDescriptor fields are exhaustively covered by user schema model_catalog and installed_models`() {
        assertKernelClassMapped(
            ModelDescriptor::class.java,
            mapOf(
                "id" to userCols("model_catalog", "id"),
                "name" to userCols("model_catalog", "name"),
                "kind" to userCols("model_catalog", "model_kind"),
                "providerId" to userCols("model_catalog", "provider"),
                "isLocal" to derived("Derived from presence in installed_models vs model_catalog.remote_url"),
                "contextWindow" to userCols("model_catalog", "properties_json"),
                "sizeBytes" to userCols("installed_models", "size_bytes"),
                "digest" to userCols("installed_models", "digest"),
                "format" to userCols("model_catalog", "properties_json"),
                "quantization" to userCols("installed_models", "quantization"),
                "metadata" to userCols("model_catalog", "properties_json"),
            ),
        )
    }

    @Test
    fun `ProviderRetention and ToolCall fields are covered by attempts and tool_calls columns`() {
        assertKernelClassMapped(
            ProviderRetention::class.java,
            mapOf(
                "policy" to projectCols("attempts", "provider_retention_json"),
                "statedDurationDays" to projectCols("attempts", "provider_retention_json"),
                "trainingUse" to projectCols("attempts", "provider_retention_json"),
                "recordedAt" to projectCols("attempts", "provider_retention_json"),
            ),
        )
        assertKernelClassMapped(
            ToolCall::class.java,
            mapOf(
                "callId" to projectCols("tool_calls", "call_id"),
                "toolName" to projectCols("tool_calls", "tool_name"),
                "arguments" to projectCols("tool_calls", "arguments_json"),
                "capabilityId" to projectCols("attempts", "capability_id"),
                "rawText" to derived("Transient heuristic parse input; snapshot persisted in attempts.output_snapshot"),
            ),
        )
    }

    // ─── Vault schema coverage check ─────────────────────────────────────────

    @Test
    fun `Vault secrets table columns are present in vault schema`() {
        assertColumn(ColumnRef(DatabaseKind.VAULT, "secrets", "id"))
        assertColumn(ColumnRef(DatabaseKind.VAULT, "secrets", "name"))
        assertColumn(ColumnRef(DatabaseKind.VAULT, "secrets", "kind"))
        assertColumn(ColumnRef(DatabaseKind.VAULT, "secrets", "ciphertext"))
        assertColumn(ColumnRef(DatabaseKind.VAULT, "secrets", "nonce"))
        assertColumn(ColumnRef(DatabaseKind.VAULT, "secrets", "allowed_consumers_json"))
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
