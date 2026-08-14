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
}
