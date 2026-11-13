package dev.aidos.intent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import org.junit.Test
import kotlin.test.assertEquals

class DerivedStatusComputerTest {

    private fun setupDb(): Pair<JdbcSqliteDriver, DerivedStatusComputer> {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val schemaStatements = listOf(
            """
            CREATE TABLE intent_nodes (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL, type TEXT NOT NULL,
                title TEXT NOT NULL, description TEXT, priority INTEGER DEFAULT 100,
                lifecycle TEXT DEFAULT 'ACTIVE', parent_id TEXT,
                asserted_status TEXT, asserted_at TEXT, asserted_by_user_id TEXT,
                assertion_note TEXT, check_kind TEXT, check_spec TEXT,
                verification_met INTEGER, verified_by_kind TEXT, verified_by_id TEXT,
                verified_at TEXT, created_at TEXT, created_by_kind TEXT, created_by_id TEXT,
                modified_at TEXT, modified_by_kind TEXT, modified_by_id TEXT, tags TEXT,
                row_version INTEGER
            )
            """.trimIndent(),
            """
            CREATE TABLE intent_edges (
                id TEXT PRIMARY KEY, project_id TEXT NOT NULL, from_node_id TEXT,
                to_node_id TEXT, edge_kind TEXT NOT NULL, created_at TEXT
            )
            """.trimIndent(),
            """
            CREATE TABLE execution_graph (
                id TEXT PRIMARY KEY, run_id TEXT NOT NULL, target_intent_node_id TEXT,
                edge_kind TEXT NOT NULL, created_at TEXT
            )
            """.trimIndent(),
            "CREATE TABLE runs (id TEXT PRIMARY KEY, state TEXT NOT NULL)",
        )
        schemaStatements.forEach { statement ->
            driver.execute(identifier = null, sql = statement, parameters = 0) {}
        }

        val computer = DerivedStatusComputer(driver)
        return Pair(driver, computer)
    }

    private fun insertNode(
        driver: JdbcSqliteDriver,
        id: String,
        projectId: String = "proj1",
        type: String = "GOAL",
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_nodes " +
                "(id, project_id, type, title, created_at, created_by_kind, created_by_id, " +
                "modified_at, modified_by_kind, modified_by_id, tags) " +
                "VALUES (?, ?, ?, ?, '2025-01-01T00:00:00Z', 'USER', 'user1', " +
                "'2025-01-01T00:00:00Z', 'USER', 'user1', '[]')",
            parameters = 4,
        ) {
            bindString(0, id)
            bindString(1, projectId)
            bindString(2, type)
            bindString(3, "Node $id")
        }
    }

    private fun insertRun(driver: JdbcSqliteDriver, id: String, state: String) {
        driver.execute(
            identifier = null,
            sql = "INSERT INTO runs (id, state) VALUES (?, ?)",
            parameters = 2,
        ) {
            bindString(0, id)
            bindString(1, state)
        }
    }

    @Test
    fun testNotStartedStatus() {
        val (driver, computer) = setupDb()
        insertNode(driver, "goal1")

        // No TARGETED or IMPLEMENTS edges → NOT_STARTED
        val status = computer.derive("goal1", "proj1")
        assertEquals("NOT_STARTED", status)
    }

    @Test
    fun testBlockedStatusUnmetDependency() {
        val (driver, computer) = setupDb()
        insertNode(driver, "goal1")
        insertNode(driver, "goal2")

        // goal1 depends on goal2, which is NOT_STARTED
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_edges (id, project_id, from_node_id, to_node_id, edge_kind, created_at) " +
                "VALUES (?, ?, ?, ?, 'DEPENDS_ON', '2025-01-01T00:00:00Z')",
            parameters = 4,
        ) {
            bindString(0, "edge1")
            bindString(1, "proj1")
            bindString(2, "goal1")
            bindString(3, "goal2")
        }

        val status = computer.derive("goal1", "proj1")
        assertEquals("BLOCKED", status, "goal1 should be BLOCKED because goal2 is not DONE")
    }

    @Test
    fun testBlockedStatusUnmetMetDependency() {
        val (driver, computer) = setupDb()
        insertNode(driver, "goal1")
        insertNode(driver, "goal2")

        // goal1 depends on goal2, but goal2 is DONE (user assertion)
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_edges (id, project_id, from_node_id, to_node_id, edge_kind, created_at) " +
                "VALUES (?, ?, ?, ?, 'DEPENDS_ON', '2025-01-01T00:00:00Z')",
            parameters = 4,
        ) {
            bindString(0, "edge1")
            bindString(1, "proj1")
            bindString(2, "goal1")
            bindString(3, "goal2")
        }

        // Mark goal2 as DONE (user assertion)
        driver.execute(
            identifier = null,
            sql = "UPDATE intent_nodes SET asserted_status = 'DONE' WHERE id = ?",
            parameters = 1,
        ) {
            bindString(0, "goal2")
        }

        val status = computer.derive("goal1", "proj1")
        assertEquals("NOT_STARTED", status, "goal1 should be NOT_STARTED since dependency is satisfied")
    }

    @Test
    fun testDoneStatus() {
        val (driver, computer) = setupDb()
        insertNode(driver, "goal1", type = "GOAL")
        insertRun(driver, "run1", "COMPLETED")

        // Insert confirmed IMPLEMENTS edge
        driver.execute(
            identifier = null,
            sql = "INSERT INTO execution_graph (id, run_id, target_intent_node_id, edge_kind, created_at) " +
                "VALUES (?, ?, ?, 'IMPLEMENTS', '2025-01-01T00:00:00Z')",
            parameters = 4,
        ) {
            bindString(0, "impl1")
            bindString(1, "run1")
            bindString(2, "goal1")
        }

        // Mark as verified by user
        driver.execute(
            identifier = null,
            sql = "UPDATE intent_nodes SET verified_by_kind = 'USER', verification_met = 1 WHERE id = ?",
            parameters = 1,
        ) {
            bindString(0, "goal1")
        }

        val status = computer.derive("goal1", "proj1")
        assertEquals("DONE", status, "goal1 should be DONE with confirmed IMPLEMENTS")
    }

    @Test
    fun testInProgressStatus() {
        val (driver, computer) = setupDb()
        insertNode(driver, "goal1")
        insertRun(driver, "run1", "RUNNING")

        // Insert TARGETED edge from running run
        driver.execute(
            identifier = null,
            sql = "INSERT INTO execution_graph (id, run_id, target_intent_node_id, edge_kind, created_at) " +
                "VALUES (?, ?, ?, 'TARGETED', '2025-01-01T00:00:00Z')",
            parameters = 4,
        ) {
            bindString(0, "targ1")
            bindString(1, "run1")
            bindString(2, "goal1")
        }

        val status = computer.derive("goal1", "proj1")
        assertEquals("IN_PROGRESS", status, "goal1 should be IN_PROGRESS with active TARGETED run")
    }

    @Test
    fun testNeedsReviewStatus() {
        val (driver, computer) = setupDb()
        insertNode(driver, "goal1")
        insertRun(driver, "run1", "COMPLETED")

        // Insert unconfirmed IMPLEMENTS edge
        driver.execute(
            identifier = null,
            sql = "INSERT INTO execution_graph (id, run_id, target_intent_node_id, edge_kind, created_at) " +
                "VALUES (?, ?, ?, 'IMPLEMENTS', '2025-01-01T00:00:00Z')",
            parameters = 4,
        ) {
            bindString(0, "impl1")
            bindString(1, "run1")
            bindString(2, "goal1")
        }

        // Do NOT mark as verified
        val status = computer.derive("goal1", "proj1")
        assertEquals("NEEDS_REVIEW", status, "goal1 should be NEEDS_REVIEW with unconfirmed IMPLEMENTS")
    }
}
