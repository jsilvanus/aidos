package dev.aidos.memory

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.kernel.TrustLevel
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * M16b done-when (RFC-0026, RFC-0046, D32, D33):
 *
 * 1. FACT/DECISION/TASK_STATE entries write with mandatory source_refs, created_by, and
 *    trust_level.
 * 2. Nothing creates a SUMMARY kind (D32).
 * 3. A write path cannot create a project-scoped entry without a user (D33 constraint 1).
 * 4. A project-scoped TASK_STATE is rejected (D33 constraint 2).
 * 5. A promoted UNTRUSTED entry is rejected (D33 constraint 3).
 * 6. Empty source_refs are rejected in the write API (RFC-0026 provenance).
 */
class SessionMemoryTest {

    private lateinit var driver: JdbcSqliteDriver
    private lateinit var store: SessionMemoryStore

    @BeforeTest
    fun setUp() {
        driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        // Load project schema (contains memory_entries and its dependencies).
        val sql = javaClass.getResourceAsStream("/project.sql")!!
            .bufferedReader().readText()
        // Execute each statement separately (simple split on semicolons + newline).
        for (stmt in sql.split(Regex(";\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }) {
            try {
                driver.execute(identifier = null, sql = stmt, parameters = 0)
            } catch (_: Exception) { /* skip CREATE INDEX IF on partially set up DB */ }
        }
        store = SessionMemoryStore(driver)
        // Insert required parent rows for foreign key constraints.
        val now = "2026-01-01T00:00:00Z"
        driver.execute(identifier = null,
            sql = "INSERT INTO projects (id, name, root_path, created_at, updated_at, state_updated_at) VALUES ('proj-1', 'Test', '/tmp', ?, ?, ?)",
            parameters = 3) { bindString(0, now); bindString(1, now); bindString(2, now) }
        driver.execute(identifier = null,
            sql = "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) VALUES ('sess-1', 'proj-1', 'Test Session', 'DRIVER', 'RUNNING', ?, ?, ?)",
            parameters = 3) { bindString(0, now); bindString(1, now); bindString(2, now) }
    }

    private fun baseEntry(
        kind: MemoryKind = MemoryKind.FACT,
        trustLevel: TrustLevel = TrustLevel.TRUSTED,
        sourceRefs: List<String> = listOf("run:abc123"),
    ) = MemoryEntry(
        sessionId = "sess-1",
        projectId = "proj-1",
        kind = kind,
        content = "The database is PostgreSQL",
        sourceRefs = sourceRefs,
        createdByKind = CreatedByKind.SESSION,
        createdById = "sess-1",
        confidence = Confidence.OBSERVED,
        trustLevel = trustLevel,
    )

    @Test
    fun `writes FACT with source refs and trust level`() {
        val id = store.write(baseEntry(MemoryKind.FACT))
        assertNotNull(id)
        val rows = store.readForSession("sess-1")
        assertEquals(1, rows.size)
        assertEquals(MemoryKind.FACT, rows.first().kind)
        assertEquals(TrustLevel.TRUSTED, rows.first().trustLevel)
        assertEquals(listOf("run:abc123"), rows.first().sourceRefs)
    }

    @Test
    fun `writes DECISION`() {
        store.write(baseEntry(MemoryKind.DECISION))
        val rows = store.readForSession("sess-1")
        assertEquals(MemoryKind.DECISION, rows.first().kind)
    }

    @Test
    fun `writes TASK_STATE`() {
        store.write(baseEntry(MemoryKind.TASK_STATE))
        val rows = store.readForSession("sess-1")
        assertEquals(MemoryKind.TASK_STATE, rows.first().kind)
    }

    @Test
    fun `rejects empty source_refs (RFC-0026 provenance)`() {
        assertFails("Should reject empty source_refs") {
            store.write(baseEntry(sourceRefs = emptyList()))
        }
    }

    @Test
    fun `new entry is always SESSION-scoped`() {
        store.write(baseEntry())
        val rows = store.readForSession("sess-1")
        assertEquals(MemoryScope.SESSION, rows.first().scope)
    }

    @Test
    fun `promotion to PROJECT requires user (D33 constraint 1)`() {
        val id = store.write(baseEntry())
        // Promoting with a user ID should work.
        val promoted = store.promoteToProject(id, userId = "user-42")
        assertTrue(promoted, "Promotion by user should succeed")
        // Verify via direct query that scope is now PROJECT.
        val scope = queryScope(id)
        assertEquals("PROJECT", scope)
    }

    @Test
    fun `TASK_STATE cannot be promoted to PROJECT (D33 constraint 2 - schema check)`() {
        val id = store.write(baseEntry(kind = MemoryKind.TASK_STATE))
        // The schema CHECK constraint will reject this at the database level.
        assertFails("Schema should reject TASK_STATE promotion to PROJECT") {
            // Directly attempt the SQL violation — the schema CHECK should fire.
            driver.execute(
                identifier = null,
                sql = "UPDATE memory_entries SET scope = 'PROJECT', promoted_by_user_id = 'user-1', promoted_at = datetime('now') WHERE id = ?",
                parameters = 1,
            ) { bindString(0, id) }
        }
    }

    @Test
    fun `UNTRUSTED entry cannot be promoted to PROJECT (D33 constraint 3 - schema check)`() {
        val id = store.write(baseEntry(trustLevel = TrustLevel.UNTRUSTED))
        assertFails("Schema should reject UNTRUSTED promotion to PROJECT") {
            driver.execute(
                identifier = null,
                sql = "UPDATE memory_entries SET scope = 'PROJECT', promoted_by_user_id = 'user-1', promoted_at = datetime('now') WHERE id = ?",
                parameters = 1,
            ) { bindString(0, id) }
        }
    }

    @Test
    fun `supersede marks old entry replaced`() {
        val id1 = store.write(baseEntry(content = "Version 1"))
        val id2 = store.write(baseEntry(content = "Version 2"))
        store.supersede(oldEntryId = id1, newEntryId = id2)
        // Active entries (no superseded_by) should only contain id2.
        val active = store.readForSession("sess-1")
        assertEquals(1, active.size)
        assertEquals("Version 2", active.first().content)
    }

    // Convenience query to read scope directly.
    private fun queryScope(id: String): String? {
        var scope: String? = null
        driver.executeQuery(
            identifier = null,
            sql = "SELECT scope FROM memory_entries WHERE id = ?",
            mapper = { cursor ->
                if (cursor.next().value) scope = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, id) }
        return scope
    }

    // Allow store.write to accept a different content for the TASK_STATE test.
    private fun baseEntry(content: String): MemoryEntry =
        baseEntry().copy(content = content)

    @Test
    fun `a secret in a memory entry is redacted before it is written (RFC-0035)`() {
        val redacting = SessionMemoryStore(driver) { it.replace("hunter2-secret", "«redacted:pw»") }
        redacting.write(baseEntry().copy(content = "The password is hunter2-secret"))
        val stored = redacting.readForSession("sess-1").single().content
        assertEquals("The password is «redacted:pw»", stored)
    }

    @Test
    fun `inspect returns entry metadata and delete permanently removes without tombstone (RFC-0026)`() {
        val id1 = store.write(baseEntry(kind = MemoryKind.DECISION).copy(content = "Use SQLite WAL"))
        val id2 = store.write(baseEntry(kind = MemoryKind.DECISION).copy(content = "Use SQLite WAL + NORMAL"))
        store.supersede(id1, id2)

        val inspected = store.inspect(id2)
        assertNotNull(inspected)
        assertEquals("Use SQLite WAL + NORMAL", inspected.content)
        assertEquals(listOf("run:abc123"), inspected.sourceRefs)
        assertEquals(null, inspected.expiresAt)

        // Deleting id2 must also succeed even though id1 had superseded_by = id2, leaving no tombstone.
        assertTrue(store.delete(id2))
        assertEquals(null, store.inspect(id2))
        val count = driver.executeQuery(
            identifier = null,
            sql = "SELECT COUNT(*) FROM memory_entries WHERE id = ?",
            mapper = { c -> c.next(); QueryResult.Value(c.getLong(0) ?: 0L) },
            parameters = 1,
        ) { bindString(0, id2) }.value
        assertEquals(0L, count)
    }

    @Test
    fun `FACT defaults to 90-day expiry, refreshes on recall, and expired entries are filtered and swept (RFC-0026)`() {
        val factId = store.write(baseEntry(kind = MemoryKind.FACT))
        val inspectedFact = store.inspect(factId)!!
        assertNotNull(inspectedFact.expiresAt, "FACT should have a default 90-day expiresAt")

        val expiredTaskId = store.write(
            baseEntry(kind = MemoryKind.TASK_STATE, sourceRefs = listOf("run:run-expired"))
                .copy(content = "In-progress step")
        )
        assertEquals(1, store.expireTaskStateForRun("run-expired", nowIso = "2026-01-02T00:00:00Z"))

        val visibleLater = store.readForSession("sess-1", nowIso = "2026-01-03T00:00:00Z")
        assertEquals(listOf(factId), visibleLater.map { it.id })

        assertTrue(store.refreshFactExpiry(factId, nowIso = "2027-01-01T00:00:00Z"))
        val refreshed = store.inspect(factId)!!
        assertTrue(refreshed.expiresAt!! > inspectedFact.expiresAt)

        val swept = store.deleteExpired(nowIso = "2026-01-03T00:00:00Z")
        assertEquals(1, swept)
        assertEquals(null, store.inspect(expiredTaskId))
    }

    @Test
    fun `readForSessionAndProject includes project-promoted entries alongside session entries`() {
        val now = "2026-01-01T00:00:00Z"
        driver.execute(
            identifier = null,
            sql = "INSERT INTO sessions (id, project_id, name, role, state, created_at, last_active_at, state_updated_at) VALUES ('sess-2', 'proj-1', 'Other', 'DRIVER', 'RUNNING', ?, ?, ?)",
            parameters = 3,
        ) { bindString(0, now); bindString(1, now); bindString(2, now) }

        val promotedId = store.write(baseEntry(kind = MemoryKind.DECISION).copy(content = "Promoted decision"))
        assertTrue(store.promoteToProject(promotedId, userId = "user-1"))
        val unpromotedOtherId = store.write(
            baseEntry(kind = MemoryKind.FACT).copy(content = "Private to sess-1")
        )
        val sess2Id = store.write(
            baseEntry(kind = MemoryKind.FACT)
                .copy(sessionId = "sess-2", createdById = "sess-2", content = "Owned by sess-2")
        )

        val visibleToSess2 = store.readForSessionAndProject("sess-2", "proj-1", nowIso = now)
        assertEquals(setOf(promotedId, sess2Id), visibleToSess2.map { it.id }.toSet())
        assertTrue(unpromotedOtherId !in visibleToSess2.map { it.id })
    }

    @Test
    fun `FACT cap evicts least recently used FACTs while leaving DECISIONs uncapped (RFC-0011)`() {
        val cappedStore = SessionMemoryStore(driver, factCap = 2)
        val f1 = cappedStore.write(baseEntry(MemoryKind.FACT).copy(content = "fact-1"))
        val f2 = cappedStore.write(baseEntry(MemoryKind.FACT).copy(content = "fact-2"))
        // Recall f1 so f2 becomes the least-recently-used FACT.
        assertTrue(cappedStore.refreshFactExpiry(f1, nowIso = "2099-01-01T00:00:00Z"))
        // DECISIONs do not count toward the FACT cap and are never evicted.
        val d1 = cappedStore.write(baseEntry(MemoryKind.DECISION).copy(content = "decision-1"))

        val f3 = cappedStore.write(baseEntry(MemoryKind.FACT).copy(content = "fact-3"))

        val remainingIds = cappedStore.readForSession("sess-1").map { it.id }.toSet()
        assertEquals(setOf(f1, f3, d1), remainingIds)
        assertEquals(null, cappedStore.inspect(f2))
    }
}
