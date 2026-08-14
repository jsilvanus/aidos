package dev.aidos.broker

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * Writes rows to the audit_log table (RFC-0003, RFC-0037, M4).
 *
 * The audit log is not a log level — it cannot be turned down, sampled, or dropped under
 * pressure (RFC-0037, §Durability). Every validate call and every effect invocation that
 * touches the broker writes one row here, in the same database transaction where possible.
 *
 * Row anatomy (from schema/project.sql):
 *   id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id,
 *   subject_ref, capability_id, detail_json
 */
class AuditLog(private val driver: SqlDriver, private val deviceId: String = "runtime") {

    /**
     * Writes one audit row. Returns the row ID.
     *
     * [kind] examples: CapabilityValidated, CapabilityDenied, ToolInvoked, ToolCompleted,
     * CapabilityGranted, CapabilityRevoked.
     */
    fun write(
        id: String,
        projectId: String,
        kind: String,
        actorKind: String,
        actorId: String,
        capabilityId: String? = null,
        subjectRef: String? = null,
        detailJson: String = "{}",
        nowIso: String,
    ): String {
        if (projectId.isBlank()) return id  // no project context — skip (e.g. pre-resolve denial)
        val seq = nextSequence(projectId)
        driver.execute(
            identifier = null,
            sql = "INSERT INTO audit_log " +
                "(id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id, " +
                "subject_ref, capability_id, detail_json) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            parameters = 11,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindLong(2, seq)
            bindString(3, nowIso)
            bindString(4, kind)
            bindString(5, actorKind)
            bindString(6, actorId)
            bindString(7, deviceId)
            bindString(8, subjectRef)
            bindString(9, capabilityId)
            bindString(10, detailJson)
        }
        return id
    }

    /** Counts audit rows for a project — used by the broker harness to assert completeness. */
    fun countForProject(projectId: String): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM audit_log WHERE project_id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 0L else 0L) },
            parameters = 1,
        ) { bindString(0, projectId) }.value

    /** Returns all row IDs for a project, ordered by sequence. Used by the broker harness. */
    fun rowsForProject(projectId: String): List<AuditRow> {
        val rows = mutableListOf<AuditRow>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, sequence, kind, actor_kind, actor_id, capability_id, subject_ref " +
                "FROM audit_log WHERE project_id = ? ORDER BY sequence",
            mapper = { cursor ->
                while (cursor.next().value) {
                    rows.add(
                        AuditRow(
                            id = cursor.getString(0)!!,
                            sequence = cursor.getLong(1)!!,
                            kind = cursor.getString(2)!!,
                            actorKind = cursor.getString(3)!!,
                            actorId = cursor.getString(4)!!,
                            capabilityId = cursor.getString(5),
                            subjectRef = cursor.getString(6),
                        )
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, projectId) }
        return rows
    }

    private fun nextSequence(projectId: String): Long =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT COALESCE(MAX(sequence), 0) + 1 FROM audit_log WHERE project_id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) ?: 1L else 1L) },
            parameters = 1,
        ) { bindString(0, projectId) }.value
}

data class AuditRow(
    val id: String,
    val sequence: Long,
    val kind: String,
    val actorKind: String,
    val actorId: String,
    val capabilityId: String?,
    val subjectRef: String?,
)
