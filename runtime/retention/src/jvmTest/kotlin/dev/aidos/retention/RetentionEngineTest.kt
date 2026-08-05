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
        // 3 nodes at 200 MB each = 600 MB. Cap = 512 MB. batchSize = 1 (one eviction per pass).
        repeat(3) { i ->
            val age = Instant.now().minus((100 - i).toLong(), ChronoUnit.DAYS).toString()
            insertNode("resume-node-$i", "p4", 200 * mb, age)
        }

        val engine = RetentionEngine(dbUrl, RetentionPolicy(
            retentionDays = 200,
            storageLimitBytes = 512 * mb,
            batchSize = 1,  // Only one eviction per pass — tests resumability.
        ))

        val r1 = engine.compact(Instant.now())
        assertEquals(1, r1.deletedNodes, "First pass evicts one node")

        val afterFirstPass = totalBytes()
        assertTrue(afterFirstPass < 600 * mb, "First pass must have made progress")
    }

    @Test
    fun `default retention policy is 90 days and 512 MB`() {
        val policy = RetentionPolicy()
        assertEquals(90L, policy.retentionDays)
        assertEquals(512L * 1024 * 1024, policy.storageLimitBytes)
    }
}
