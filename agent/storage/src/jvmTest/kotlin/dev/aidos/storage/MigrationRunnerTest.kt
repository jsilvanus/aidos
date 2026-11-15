package dev.aidos.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ErrorCodes
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** RFC-0040's open(db) state machine: fresh, current, and newer-than-supported. */
class MigrationRunnerTest {

    private val schema = readSchemaResource(DatabaseKind.PROJECT)
    private val now = { "2026-08-05T00:00:00Z" }

    @Test
    fun `fresh database bootstraps and stamps the current version`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val result = MigrationRunner.open(driver, DatabaseKind.PROJECT, schema, "test-1.0", now)

        val ready = assertIs<OpenResult.Ready>(result)
        assertEquals(DatabaseKind.PROJECT.currentVersion, ready.version)

        val stamped = driver.executeQuery(
            identifier = null,
            sql = "SELECT version, runtime_version FROM schema_versions WHERE id = 1",
            mapper = { cursor ->
                assertTrue(cursor.next().value)
                QueryResult.Value(cursor.getLong(0) to cursor.getString(1))
            },
            parameters = 0,
        ).value
        assertEquals(1L, stamped.first)
        assertEquals("test-1.0", stamped.second)
    }

    @Test
    fun `opening an already-current database is a no-op`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MigrationRunner.open(driver, DatabaseKind.PROJECT, schema, "test-1.0", now)

        val second = MigrationRunner.open(driver, DatabaseKind.PROJECT, schema, "test-1.1", now)

        val ready = assertIs<OpenResult.Ready>(second)
        assertEquals(1, ready.version)
        // Re-opening must not touch schema_versions -- the row still names the runtime that
        // bootstrapped it, not the one that merely reopened it.
        val runtimeVersion = driver.executeQuery(
            identifier = null,
            sql = "SELECT runtime_version FROM schema_versions WHERE id = 1",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            parameters = 0,
        ).value
        assertEquals("test-1.0", runtimeVersion)
    }

    @Test
    fun `a database written by a newer runtime opens read-only rather than refusing`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        for (statement in SqlScript.statements(schema)) {
            driver.execute(identifier = null, sql = statement, parameters = 0)
        }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO schema_versions (id, version, applied_at, runtime_version) " +
                "VALUES (1, 999, '2026-08-05T00:00:00Z', 'future-runtime')",
            parameters = 0,
        )

        val result = MigrationRunner.open(driver, DatabaseKind.PROJECT, schema, "test-1.0", now)

        val readOnly = assertIs<OpenResult.ReadOnly>(result)
        assertEquals(ErrorCodes.STORAGE_MIGRATION_REQUIRED, readOnly.error.code)
        assertEquals(ErrorClass.CONFLICT, readOnly.error.errorClass)
        assertEquals("999", readOnly.error.detail["found_version"])
    }

    @Test
    fun `fresh database enables incremental auto_vacuum and wal_autocheckpoint 1000`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            val result = MigrationRunner.open(driver, DatabaseKind.PROJECT, schema, "test-1.0", now)
            assertIs<OpenResult.Ready>(result)

            val autoVacuum = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA auto_vacuum",
                mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                parameters = 0,
            ).value
            val walAutocheckpoint = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA wal_autocheckpoint",
                mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                parameters = 0,
            ).value

            assertEquals(2L, autoVacuum)
            assertEquals(1000L, walAutocheckpoint)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `opening newer read-only database does not rewrite auto_vacuum`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        try {
            driver.execute(
                identifier = null,
                sql = "CREATE TABLE schema_versions (id INTEGER PRIMARY KEY, version INTEGER NOT NULL, applied_at TEXT NOT NULL, runtime_version TEXT NOT NULL)",
                parameters = 0,
            )
            driver.execute(
                identifier = null,
                sql = "INSERT INTO schema_versions (id, version, applied_at, runtime_version) VALUES (1, 999, '2026-08-05T00:00:00Z', 'future-runtime')",
                parameters = 0,
            )

            val result = MigrationRunner.open(driver, DatabaseKind.PROJECT, schema, "test-1.0", now)
            assertIs<OpenResult.ReadOnly>(result)

            val autoVacuum = driver.executeQuery(
                identifier = null,
                sql = "PRAGMA auto_vacuum",
                mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null) },
                parameters = 0,
            ).value
            assertEquals(0L, autoVacuum)
        } finally {
            driver.close()
        }
    }

    @Test
    fun `older database is migrated forward in a transaction and recorded in migration_history`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MigrationRunner.open(driver, DatabaseKind.PROJECT, schema, "test-1.0", now)
        // Simulate a database stamped one version behind the runtime.
        driver.execute(null, "UPDATE schema_versions SET version = 0 WHERE id = 1", 0)
        driver.execute(null, "DELETE FROM migration_history", 0)

        val result = MigrationRunner.open(
            driver, DatabaseKind.PROJECT, schema, "test-2.0", now,
            migrations = listOf(SchemaMigration(1, listOf("CREATE TABLE migrated_probe (id INTEGER PRIMARY KEY)"))),
        )
        assertEquals(1, assertIs<OpenResult.Ready>(result).version)
        val history = driver.executeQuery(null, "SELECT version, runtime_version FROM migration_history", { c ->
            QueryResult.Value(if (c.next().value) c.getLong(0) to c.getString(1) else null)
        }, 0).value
        assertEquals(1L to "test-2.0", history)
    }

    @Test
    fun `fresh bootstrap writes a migration_history row`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        MigrationRunner.open(driver, DatabaseKind.PROJECT, schema, "test-1.0", now)
        val n = driver.executeQuery(null, "SELECT COUNT(*) FROM migration_history", { c ->
            QueryResult.Value(if (c.next().value) c.getLong(0) else 0L)
        }, 0).value
        assertEquals(1L, n)
    }

    @Test
    fun `historical v1 schema fixture opens as current without migration`() {
        val fixture = java.io.File("../../tests/fixtures/schemas/v1.sql").takeIf { it.exists() }
            ?: java.io.File("tests/fixtures/schemas/v1.sql")
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val result = MigrationRunner.open(driver, DatabaseKind.PROJECT, fixture.readText(), "fixture", now)
        assertEquals(1, assertIs<OpenResult.Ready>(result).version)
    }
}
