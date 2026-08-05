package dev.aidos.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** M1's done-when: a fresh install creates all three databases from schema/. */
class AidosStorageTest {

    private fun pragma(driver: JdbcSqliteDriver, name: String): String =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA $name",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0)!! else "") },
            parameters = 0,
        ).value

    @Test
    fun `fresh install creates all three databases from schema`() {
        val root = Files.createTempDirectory("aidos-storage-test").toFile()
        val projectRoot = root.resolve("project").apply { mkdirs() }
        val runtimeVersion = "test-1.0"
        val now = { "2026-08-05T00:00:00Z" }

        val user = AidosStorage.openUser(DesktopPaths.userDb(root.path), runtimeVersion, now)
        val vault = AidosStorage.openVault(DesktopPaths.vaultDb(root.path), runtimeVersion, now)
        val project = AidosStorage.openProject(
            DesktopPaths.stateDb(projectRoot.path),
            runtimeVersion,
            now,
        )

        assertIs<OpenResult.Ready>(user.result)
        assertIs<OpenResult.Ready>(vault.result)
        assertIs<OpenResult.Ready>(project.result)

        assertTrue(root.resolve(".aidos/user.db").exists())
        assertTrue(root.resolve(".aidos/secrets/vault.db").exists())
        assertTrue(projectRoot.resolve(".aidos/state.db").exists())
    }

    @Test
    fun `durability pragmas are applied on every open`() {
        val root = Files.createTempDirectory("aidos-storage-pragma-test").toFile()
        val path = root.resolve(".aidos/state.db").path
        val opened = AidosStorage.openProject(path, "test-1.0") { "2026-08-05T00:00:00Z" }
        val driver = opened.driver as JdbcSqliteDriver

        assertEquals("wal", pragma(driver, "journal_mode").lowercase())
        assertEquals("1", pragma(driver, "synchronous"))
        assertEquals("1", pragma(driver, "foreign_keys"))
    }
}
