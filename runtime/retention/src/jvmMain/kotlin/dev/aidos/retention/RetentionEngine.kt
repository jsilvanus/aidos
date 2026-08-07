package dev.aidos.retention

import kotlinx.coroutines.yield
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Retention and compaction engine (RFC-0056, RFC-0045, M25).
 *
 * Rules (defaults, all overridable via [RetentionPolicy]):
 *
 * 1. **90-day default retention** — content_nodes whose last update timestamp falls outside the
 *    retention window are eligible for deletion.
 * 2. **512 MB storage cap** — if total `size_bytes` across content_nodes exceeds the cap, the
 *    oldest-updated items are evicted (LRU) until the cap is met.
 * 3. **Interruptible and resumable** — compaction yields on every row so that an Android
 *    execution window can interrupt it mid-pass. On next invocation it resumes naturally because
 *    the query re-runs from the oldest remaining row.
 * 4. **Active-session protection** — rows belonging to a project that has at least one active
 *    session are never evicted, regardless of age or size.
 *
 * The compaction result reports what was deleted and the bytes freed, so callers can decide
 * whether to schedule a further pass.
 */
class RetentionEngine(
    private val connectionUrl: String,
    private val policy: RetentionPolicy = RetentionPolicy(),
) {

    /**
     * Runs a single compaction pass.
     *
     * This is a suspend function so Android callers can yield between batches.
     * It can be called repeatedly and will make incremental progress each time.
     *
     * Returns [CompactionResult] describing what happened.
     */
    suspend fun compact(now: Instant = Instant.now()): CompactionResult {
        val cutoff = now.minus(policy.retentionDays, ChronoUnit.DAYS).toString()
        var deletedNodes = 0
        var bytesFreed = 0L
        var needsAnotherPass = false

        DriverManager.getConnection(connectionUrl).use { conn ->
            conn.autoCommit = false

            // Phase 1: delete expired content_nodes in projects with no active session.
            val expired = conn.prepareStatement("""
                SELECT cn.id, cn.size_bytes FROM content_nodes cn
                WHERE cn.updated_at < ?
                  AND cn.state NOT IN ('DELETED')
                  AND cn.project_id NOT IN (
                      SELECT DISTINCT project_id FROM sessions WHERE state = 'active'
                  )
                ORDER BY cn.updated_at ASC
                LIMIT ?
            """.trimIndent())
            expired.setString(1, cutoff)
            expired.setInt(2, policy.batchSize)
            val expiredRs = expired.executeQuery()

            val toDelete = mutableListOf<Pair<String, Long>>()
            while (expiredRs.next()) {
                toDelete.add(expiredRs.getString("id") to expiredRs.getLong("size_bytes"))
            }
            expiredRs.close()
            expired.close()

            for ((id, size) in toDelete) {
                yield()  // Interruptible — Android can cancel here.
                softDeleteNode(conn, id)
                deletedNodes++
                bytesFreed += size
            }

            conn.commit()

            // Phase 2: LRU eviction if storage cap still exceeded.
            val totalBytes = totalStorageBytes(conn)
            if (totalBytes > policy.storageLimitBytes) {
                var overage = totalBytes - policy.storageLimitBytes

                val lru = conn.prepareStatement("""
                    SELECT cn.id, cn.size_bytes FROM content_nodes cn
                    WHERE cn.state NOT IN ('DELETED')
                      AND cn.project_id NOT IN (
                          SELECT DISTINCT project_id FROM sessions WHERE state = 'active'
                      )
                    ORDER BY cn.updated_at ASC
                    LIMIT ?
                """.trimIndent())
                lru.setInt(1, policy.batchSize)
                val lruRs = lru.executeQuery()
                val lruCandidates = mutableListOf<Pair<String, Long>>()
                while (lruRs.next()) {
                    lruCandidates.add(lruRs.getString("id") to lruRs.getLong("size_bytes"))
                }
                lruRs.close()
                lru.close()

                for ((id, size) in lruCandidates) {
                    if (overage <= 0) break
                    yield()
                    softDeleteNode(conn, id)
                    deletedNodes++
                    bytesFreed += size
                    overage -= size
                }

                // Still over cap? Signal that caller should schedule another pass.
                needsAnotherPass = overage > 0
                conn.commit()
            }
        }

        return CompactionResult(
            deletedNodes = deletedNodes,
            bytesFreed = bytesFreed,
            needsAnotherPass = needsAnotherPass,
        )
    }

    private fun totalStorageBytes(conn: Connection): Long {
        return conn.prepareStatement(
            "SELECT COALESCE(SUM(size_bytes), 0) FROM content_nodes WHERE state NOT IN ('DELETED')"
        ).use { stmt ->
            stmt.executeQuery().use { rs ->
                if (rs.next()) rs.getLong(1) else 0L
            }
        }
    }

    /** Marks the node as DELETED (soft delete) to preserve FK integrity. */
    private fun softDeleteNode(conn: Connection, id: String) {
        conn.prepareStatement(
            "UPDATE content_nodes SET state = 'DELETED' WHERE id = ?"
        ).apply {
            setString(1, id)
            executeUpdate()
            close()
        }
    }
}

/**
 * Configurable retention policy (RFC-0056, M25).
 *
 * @param retentionDays  Default 90 days — items not updated within this window are eligible.
 * @param storageLimitBytes  Default 512 MB cap per active project database.
 * @param batchSize  Max rows to process per pass — keeps each execution window bounded.
 */
data class RetentionPolicy(
    val retentionDays: Long = 90L,
    val storageLimitBytes: Long = 512L * 1024 * 1024,  // 512 MB
    val batchSize: Int = 500,
)

/**
 * Result of a single compaction pass.
 *
 * @param needsAnotherPass  True if storage cap is still exceeded after this pass.
 *                          Callers should schedule another pass (next execution window on mobile).
 */
data class CompactionResult(
    val deletedNodes: Int,
    val bytesFreed: Long,
    val needsAnotherPass: Boolean,
)

