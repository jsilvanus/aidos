package dev.aidos.storage

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import org.sqlite.SQLiteConfig

/**
 * D35: `sqlite-driver`, the same JDBC driver `gitsema-kotlin` uses on this target, so the process
 * loads one SQLite build rather than two independently-pinned copies.
 *
 * RFC-0040's durability settings -- WAL, `synchronous = NORMAL`, a 5s `busy_timeout`,
 * `foreign_keys = ON` -- are per-connection session state, and `JdbcSqliteDriver` opens a new
 * `Connection` per call rather than holding one open. A `PRAGMA` issued after construction would
 * only ever land on whichever connection happened to run it, not the ones after. Baking the
 * settings into [SQLiteConfig] and handing its `Properties` to the driver is what makes
 * `sqlite-jdbc` apply them to every connection it opens for this database.
 */
fun createJvmDriver(path: String): SqlDriver {
    val config = SQLiteConfig().apply {
        setJournalMode(SQLiteConfig.JournalMode.WAL)
        setSynchronous(SQLiteConfig.SynchronousMode.NORMAL)
        enforceForeignKeys(true)
        setBusyTimeout(5000)
    }
    val url = if (path == ":memory:") {
        JdbcSqliteDriver.IN_MEMORY
    } else {
        File(path).absoluteFile.parentFile?.mkdirs()
        "jdbc:sqlite:$path"
    }
    return JdbcSqliteDriver(url, config.toProperties())
}

/** Reads one of `schema/`'s files, bundled as a classpath resource -- see build.gradle.kts. */
fun readSchemaResource(kind: DatabaseKind): String {
    val resourcePath = "/${kind.schemaResource}"
    val stream = object {}.javaClass.getResourceAsStream(resourcePath)
        ?: error("schema resource not found on classpath: $resourcePath")
    return stream.bufferedReader().use { it.readText() }
}
