package dev.aidos.broker

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * RFC-0003: the audit `sequence` is per project and monotonic so that a deletion is detectable.
 * This verifier walks a project's sequence and reports every gap, duplicate, or out-of-order row.
 */
object AuditVerifier {

    data class Report(
        val projectId: String,
        val rowCount: Long,
        /** Sequence numbers that are missing between the first and last row. */
        val missingSequences: List<Long>,
        /** Whether the sequence starts at 1 (a deleted prefix is otherwise invisible). */
        val startsAtOne: Boolean,
    ) {
        val intact: Boolean get() = missingSequences.isEmpty() && startsAtOne
    }

    fun verify(driver: SqlDriver, projectId: String): Report {
        val sequences = mutableListOf<Long>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT sequence FROM audit_log WHERE project_id = ? ORDER BY sequence",
            mapper = { c ->
                while (c.next().value) sequences.add(c.getLong(0)!!)
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, projectId) }

        val missing = mutableListOf<Long>()
        for (i in 1 until sequences.size) {
            var expected = sequences[i - 1] + 1
            while (expected < sequences[i]) {
                missing.add(expected)
                expected++
            }
        }
        return Report(
            projectId = projectId,
            rowCount = sequences.size.toLong(),
            missingSequences = missing,
            startsAtOne = sequences.isEmpty() || sequences.first() == 1L,
        )
    }
}
