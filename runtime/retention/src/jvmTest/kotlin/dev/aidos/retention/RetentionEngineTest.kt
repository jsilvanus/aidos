package dev.aidos.retention

import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * M25 done-when (RFC-0056, RFC-0045):
 *
 * 1. Storage per active project stays under 512 MB after 90 simulated days of use.
 * 2. Compaction is interruptible (yields on each row) and resumes — each call makes
 *    incremental progress from where it left off.
 * 3. Active-session projects are never evicted.
 */
class RetentionEngineTest {

    private lateinit var dbFile: File
    private lateinit var dbUrl: String

    @BeforeTest
    fun setUp() {
        dbFile = Files.createTempFile("retention-test", ".db").toFile()
        dbUrl = "jdbc:sqlite:${dbFile.absolutePath}"
        DriverManager.getConnection(dbUrl).use { conn ->
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS projects (
                    id TEXT PRIMARY KEY,
                    name TEXT NOT NULL
                )
            """)
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS sessions (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    state TEXT NOT NULL
                )
            """)
            conn.createStatement().executeUpdate("""
                CREATE TABLE IF NOT EXISTS content_nodes (
                    id TEXT PRIMARY KEY,
                    project_id TEXT NOT NULL,
                    kind TEXT NOT NULL,
                    name TEXT NOT NULL,
                    description TEXT,
                    mutability_policy TEXT NOT NULL,
                    sensitivity_level TEXT NOT NULL,
                    egress_eligibility TEXT NOT NULL,
                    trust_level TEXT NOT NULL,
                    storage_location_json TEXT NOT NULL,
                    content_hash TEXT NOT NULL,
                    content_type TEXT NOT NULL,
                    size_bytes INTEGER NOT NULL,
                    created_at TEXT NOT NULL,
                    created_by_kind TEXT NOT NULL,
                    created_by_id TEXT NOT NULL,
                    updated_at TEXT NOT NULL,
                    updated_by_kind TEXT,
                    updated_by_id TEXT,
                    content_version INTEGER NOT NULL DEFAULT 1,
                    row_version INTEGER NOT NULL DEFAULT 1,
                    state TEXT NOT NULL DEFAULT 'ACTIVE',
                    tags TEXT NOT NULL DEFAULT '[]'
                )
            """)
        }
    }

    @AfterTest
    fun tearDown() {
        dbFile.delete()
    }

    private fun clearNodes() {
        DriverManager.getConnection(dbUrl).use { conn ->
            conn.createStatement().executeUpdate("DELETE FROM content_nodes")
            conn.createStatement().executeUpdate("DELETE FROM sessions")
            conn.createStatement().executeUpdate("DELETE FROM projects")
        }
    }

    private fun insertProject(id: String) {
        DriverManager.getConnection(dbUrl).use { conn ->
            conn.prepareStatement("INSERT OR IGNORE INTO projects(id, name) VALUES (?,?)").apply {
                setString(1, id); setString(2, id); executeUpdate(); close()
            }
        }
    }

    private fun insertSession(id: String, projectId: String, state: String) {
        DriverManager.getConnection(dbUrl).use { conn ->
            conn.prepareStatement(
                "INSERT OR IGNORE INTO sessions(id, project_id, state) VALUES (?,?,?)"
            ).apply {
                setString(1, id); setString(2, projectId); setString(3, state)
                executeUpdate(); close()
            }
        }
    }

    private fun insertNode(id: String, projectId: String, sizeBytes: Long, updatedAt: String) {
        DriverManager.getConnection(dbUrl).use { conn ->
            conn.prepareStatement("""
                INSERT OR IGNORE INTO content_nodes
                (id,project_id,kind,name,mutability_policy,sensitivity_level,
                 egress_eligibility,trust_level,storage_location_json,content_hash,
                 content_type,size_bytes,created_at,created_by_kind,created_by_id,updated_at)
                VALUES (?,?,'FILE',?,'IMMUTABLE','INTERNAL','NEVER','TRUSTED','{}','hash','text/plain',?,?,
                        'SESSION','s1',?)
            """.trimIndent()).apply {
                setString(1, id)
                setString(2, projectId)
                setString(3, id)
                setLong(4, sizeBytes)
                setString(5, updatedAt)
                setString(6, updatedAt)
                executeUpdate(); close()
            }
        }
    }

    private fun countActiveNodes(): Int {
        return DriverManager.getConnection(dbUrl).use { conn ->
            val rs = conn.createStatement().executeQuery(
                "SELECT COUNT(*) FROM content_nodes WHERE state != 'DELETED'"
            )
            if (rs.next()) rs.getInt(1) else 0
        }
    }

    private fun totalBytes(): Long {
        return DriverManager.getConnection(dbUrl).use { conn ->
            val rs = conn.createStatement().executeQuery(
                "SELECT COALESCE(SUM(size_bytes),0) FROM content_nodes WHERE state != 'DELETED'"
            )
            if (rs.next()) rs.getLong(1) else 0L
        }
    }

    @Test
    fun `expired nodes are soft-deleted after 90 days`() = runTest {
        clearNodes()
        insertProject("p1")
        val old = Instant.now().minus(100, ChronoUnit.DAYS).toString()
        val recent = Instant.now().minus(10, ChronoUnit.DAYS).toString()
        insertNode("old-1", "p1", 1024, old)
        insertNode("recent-1", "p1", 1024, recent)

        val engine = RetentionEngine(dbUrl, RetentionPolicy(retentionDays = 90))
        val result = engine.compact(Instant.now())

        assertEquals(1, result.deletedNodes, "Only the old node should be deleted")
        assertEquals(1, countActiveNodes(), "Recent node must remain active")
    }

    @Test
    fun `active session project nodes are never evicted`() = runTest {
        clearNodes()
        insertProject("p-active")
        insertSession("s-active", "p-active", "active")
        val old = Instant.now().minus(100, ChronoUnit.DAYS).toString()
        insertNode("protected-1", "p-active", 1024 * 1024 * 600L, old)  // 600 MB, old

        val engine = RetentionEngine(dbUrl, RetentionPolicy(
            retentionDays = 90,
            storageLimitBytes = 512L * 1024 * 1024,
        ))
        val result = engine.compact(Instant.now())

        assertEquals(0, result.deletedNodes, "Active session nodes must never be evicted")
        assertEquals(1, countActiveNodes())
    }

    @Test
    fun `storage cap evicts LRU nodes when over limit`() = runTest {
        clearNodes()
        insertProject("p2")
        // No active session for p2 — LRU eviction is allowed.
        val mb = 1024L * 1024
        // Insert nodes totaling 600 MB (over 512 MB cap).
        repeat(6) { i ->
            val age = Instant.now().minus((100 - i).toLong(), ChronoUnit.DAYS).toString()
            insertNode("node-$i", "p2", 100 * mb, age)
        }
        assertEquals(600 * mb, totalBytes())

        val engine = RetentionEngine(dbUrl, RetentionPolicy(
            retentionDays = 200,  // No expiry — only cap matters.
            storageLimitBytes = 512 * mb,
            batchSize = 10,
        ))
        val result = engine.compact(Instant.now())

        assertTrue(result.deletedNodes > 0, "Some nodes must be evicted to meet the cap")
        assertTrue(totalBytes() <= 512 * mb, "Total bytes must not exceed cap after compaction")
    }

    @Test
    fun `needsAnotherPass false when fully within cap`() = runTest {
        clearNodes()
        insertProject("p3")
        val recent = Instant.now().minus(10, ChronoUnit.DAYS).toString()
        insertNode("small-1", "p3", 1024, recent)

        val engine = RetentionEngine(dbUrl, RetentionPolicy(
            retentionDays = 90,
            storageLimitBytes = 512L * 1024 * 1024,
        ))
        val result = engine.compact(Instant.now())

        assertFalse(result.needsAnotherPass, "No further pass needed when under cap")
    }

    @Test
    fun `compaction is resumable - second pass evicts remaining`() = runTest {
        clearNodes()
        insertProject("p4")
        val mb = 1024L * 1024
        // 7 nodes at 100 MB each = 700 MB. Cap = 512 MB, so overage is 188 MB -- more than any
        // single node's 100 MB, so with batchSize = 1 (LIMIT 1 candidate fetched per pass) no
        // single pass can close the gap: this genuinely requires a second compact() call, not
        // just a batch big enough to evict everything needed in one shot.
        repeat(7) { i ->
            val age = Instant.now().minus((100 - i).toLong(), ChronoUnit.DAYS).toString()
            insertNode("resume-node-$i", "p4", 100 * mb, age)
        }

        val engine = RetentionEngine(dbUrl, RetentionPolicy(
            retentionDays = 200,
            storageLimitBytes = 512 * mb,
            batchSize = 1,  // Only one candidate fetched per pass — forces multiple passes.
        ))

        val r1 = engine.compact(Instant.now())
        assertEquals(1, r1.deletedNodes, "First pass evicts one node (batchSize = 1)")
        assertTrue(r1.needsAnotherPass, "700 MB - 100 MB = 600 MB is still over the 512 MB cap")
        assertEquals(600 * mb, totalBytes(), "First pass must have made progress")

        // The audit's Part 3 finding: this test never actually called compact() a second time,
        // so "second pass evicts remaining" was asserted by the test's own name, not its body.
        // A genuine second call must pick up from the oldest *remaining* row -- not redo the one
        // already evicted -- and must converge the cap, the same way an interrupted-and-resumed
        // real Run would (see the class doc comment's "on next invocation it resumes naturally"
        // claim).
        val r2 = engine.compact(Instant.now())
        assertEquals(1, r2.deletedNodes, "Second pass evicts the next-oldest remaining node, not a repeat")
        assertFalse(r2.needsAnotherPass, "600 MB - 100 MB = 500 MB is now within the 512 MB cap")
        assertEquals(500 * mb, totalBytes(), "Second pass must have made further real progress")
        assertEquals(5, countActiveNodes(), "Two distinct nodes evicted across the two passes")
    }

    /**
     * M25's done-when headline claim: "storage per active project stays under 512 MB after 90
     * simulated days of use." The audit's Part 3 finding was that no test actually simulated
     * accumulation over time — the only prior coverage backdated two rows' timestamps to a single
     * point, never a day-by-day buildup. This inserts one node per day for 120 days (30 days past
     * the default retention window, so both age-based expiry and cap-based LRU eviction are
     * genuinely exercised together, not just one in isolation) and asserts the exact converged
     * state with the *default* [RetentionPolicy] — 90 days, 512 MB — not a policy tuned to make
     * the test pass trivially.
     */
    @Test
    fun `storage stays within the 512 MB cap after 120 days of simulated daily accumulation`() = runTest {
        clearNodes()
        insertProject("p-accum")
        // No active session for p-accum — both expiry and cap eviction are allowed to act.
        val mb = 1024L * 1024
        val dailyBytes = 6 * mb
        val totalDays = 120
        val now = Instant.now()
        repeat(totalDays) { day ->
            // day 0 is the oldest (120 days old), day 119 is the newest (1 day old) — a real
            // day-by-day accumulation, not two rows backdated to one point.
            val age = now.minus((totalDays - day).toLong(), ChronoUnit.DAYS).toString()
            insertNode("day-node-$day", "p-accum", dailyBytes, age)
        }
        assertEquals(totalDays * dailyBytes, totalBytes(), "sanity: 120 days' worth of data inserted")

        // Default policy: 90-day retention, 512 MB cap (RFC-0056's own MVP numbers).
        val engine = RetentionEngine(dbUrl)
        val result = engine.compact(now)

        // Age expiry alone removes the 30 oldest days (120 - 90): 30 * 6 MB = 180 MB freed,
        // leaving 90 days' worth = 540 MB, still 28 MB over the 512 MB cap. LRU eviction then
        // removes 5 more of the next-oldest days (30 MB) to close that gap. Both mechanisms
        // must actually run in the same pass for these numbers to hold.
        assertEquals(35, result.deletedNodes, "30 expired by age + 5 more evicted by the cap")
        assertEquals(210 * mb, result.bytesFreed)
        assertFalse(result.needsAnotherPass, "batchSize=150 default easily covers this in one pass")
        assertEquals(85, countActiveNodes(), "120 - 30 (expired) - 5 (LRU) = 85 days' worth remain")
        assertEquals(510 * mb, totalBytes())
        assertTrue(totalBytes() <= 512 * mb, "the 512 MB ceiling must hold after 120 days of accumulation")
    }

    @Test
    fun `default retention policy is 90 days, 512 MB, and a 150-row batch`() {
        val policy = RetentionPolicy()
        assertEquals(90L, policy.retentionDays)
        assertEquals(512L * 1024 * 1024, policy.storageLimitBytes)
        // 150, not the original 500 -- tuned down with the project owner to shrink the
        // redo-window on interruption (see RetentionPolicy's own doc comment for the cost model).
        assertEquals(150, policy.batchSize)
    }
}
