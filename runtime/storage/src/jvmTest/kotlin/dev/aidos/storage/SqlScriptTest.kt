package dev.aidos.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Executes every schema SQL file through the same statement splitter and driver the migration
 * runner uses, and checks the resulting table count against schema/check.py's own count -- the
 * two tools reading the same canonical file should never disagree.
 */
class SqlScriptTest {

    private fun tableCount(driver: JdbcSqliteDriver): Int =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT count(*) FROM sqlite_master WHERE type = 'table' AND name NOT LIKE 'sqlite_%'",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0)!!.toInt() else 0) },
            parameters = 0,
        ).value

    @Test
    fun `user schema executes and creates every table`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val schema = readSchemaResource(DatabaseKind.USER)
        for (statement in SqlScript.statements(schema)) {
            driver.execute(identifier = null, sql = statement, parameters = 0)
        }
        assertEquals(13, tableCount(driver))
    }

    @Test
    fun `vault schema executes and creates every table`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val schema = readSchemaResource(DatabaseKind.VAULT)
        for (statement in SqlScript.statements(schema)) {
            driver.execute(identifier = null, sql = statement, parameters = 0)
        }
        assertEquals(3, tableCount(driver))
    }

    @Test
    fun `project schema executes and creates every table`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        val schema = readSchemaResource(DatabaseKind.PROJECT)
        for (statement in SqlScript.statements(schema)) {
            driver.execute(identifier = null, sql = statement, parameters = 0)
        }
        assertEquals(43, tableCount(driver))
    }
}
