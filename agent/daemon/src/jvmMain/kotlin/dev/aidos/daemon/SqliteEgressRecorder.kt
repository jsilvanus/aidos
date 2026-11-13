package dev.aidos.daemon

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.http.EgressRecord

/** Persists RFC-0042 egress audit rows into project `egress_records`. */
class SqliteEgressRecorder(
    private val projectDriver: SqlDriver,
) : (EgressRecord) -> Unit {
    override fun invoke(record: EgressRecord) {
        if (record.projectId.isNullOrBlank()) return
        runCatching {
            projectDriver.execute(
                identifier = null,
                sql = "INSERT INTO egress_records (" +
                    "id, project_id, attempt_id, capability_id, destination_host, resolved_address_class, method, " +
                    "request_bytes, response_bytes, payload_hash, content_sensitivity, run_taint, duration_ms, " +
                    "outcome, cost_units, occurred_at" +
                    ") VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                parameters = 16,
            ) {
                bindString(0, record.id)
                bindString(1, record.projectId)
                bindString(2, record.attemptId)
                bindString(3, record.capabilityId)
                bindString(4, record.destinationHost)
                bindString(5, record.resolvedAddressClass.name)
                bindString(6, record.method)
                bindLong(7, record.requestBytes)
                bindLong(8, record.responseBytes)
                bindString(9, record.payloadHash)
                bindString(10, record.contentSensitivity)
                bindString(11, record.runTaint)
                bindLong(12, record.durationMs)
                bindString(13, record.outcome.name)
                bindLong(14, record.costUnits)
                bindString(15, record.occurredAt)
            }
        }
    }
}
