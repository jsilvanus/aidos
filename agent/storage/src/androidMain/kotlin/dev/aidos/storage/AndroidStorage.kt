package dev.aidos.storage

import android.content.Context
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

/**
 * Android counterpart to [AidosStorage].
 *
 * The canonical SQL lives in the repository-level `schema/` directory and is packaged as Android
 * assets. SQLDelight owns the SQLite connection; [MigrationRunner] remains the single bootstrap
 * and migration state machine for the actual schema.
 */
object AndroidAidosStorage {
    private const val RUNTIME_VERSION = "0.1.0-alpha"

    fun openUser(context: Context, nowIso: () -> String): SqlDriver =
        open(context, DatabaseKind.USER, File(context.filesDir, "user.db"), nowIso)

    fun openProject(context: Context, projectRoot: String, nowIso: () -> String): SqlDriver =
        open(context, DatabaseKind.PROJECT, File(projectRoot, ".aidos/state.db"), nowIso)

    private fun open(
        context: Context,
        kind: DatabaseKind,
        path: File,
        nowIso: () -> String,
    ): SqlDriver {
        path.parentFile?.mkdirs()
        val driver = AndroidSqliteDriver(
            schema = RawSchema(kind.currentVersion),
            context = context,
            name = path.absolutePath,
        )
        val schemaSql = context.assets.open(kind.schemaResource).bufferedReader().use { it.readText() }
        MigrationRunner.open(driver, kind, schemaSql, RUNTIME_VERSION, nowIso)
        return driver
    }

    /** SQLDelight lifecycle hook is intentionally empty; MigrationRunner owns the real DDL. */
    private class RawSchema(override val version: Long) : SqlSchema<QueryResult.Value<Unit>> {
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Value(Unit)

        override fun migrate(
            driver: SqlDriver,
            oldVersion: Long,
            newVersion: Long,
            vararg callbacks: AfterVersion,
        ): QueryResult.Value<Unit> = QueryResult.Value(Unit)
    }
}
