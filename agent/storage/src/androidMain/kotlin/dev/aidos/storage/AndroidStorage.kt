package dev.aidos.storage

import android.content.Context
import androidx.sqlite.db.SupportSQLiteDatabase
import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlSchema
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import java.io.File

/**
 * Storage root and path resolution for Android app-private storage (Context.filesDir).
 * Replaces desktop DesktopPaths for on-device persistence.
 */
object AndroidPaths {
    fun userDb(filesDir: File): String = File(filesDir, "aidos/user.db").absolutePath
    fun vaultDb(filesDir: File): String = File(filesDir, "aidos/secrets/vault.db").absolutePath
    fun stateDb(projectRoot: String): String = "$projectRoot/.aidos/state.db"
    fun projectsDir(filesDir: File): String = File(filesDir, "aidos/projects").absolutePath
}

fun createAndroidDriver(
    context: Context,
    path: String,
): SqlDriver {
    if (path != ":memory:") {
        File(path).absoluteFile.parentFile?.mkdirs()
    }
    val schema = object : SqlSchema<QueryResult.Value<Unit>> {
        override val version: Long = 1L
        override fun create(driver: SqlDriver): QueryResult.Value<Unit> = QueryResult.Value(Unit)
        override fun migrate(driver: SqlDriver, oldVersion: Long, newVersion: Long, vararg callbacks: AfterVersion): QueryResult.Value<Unit> = QueryResult.Value(Unit)
    }
    return AndroidSqliteDriver(
        schema = schema,
        context = context,
        name = if (path == ":memory:") null else path,
        callback = object : AndroidSqliteDriver.Callback(schema) {
            override fun onConfigure(db: SupportSQLiteDatabase) {
                super.onConfigure(db)
                db.setForeignKeyConstraintsEnabled(true)
                db.enableWriteAheadLogging()
            }
        }
    )
}

fun readAndroidSchemaResource(kind: DatabaseKind): String {
    val resourcePath = "/${kind.schemaResource}"
    val stream = DatabaseKind::class.java.getResourceAsStream(resourcePath)
        ?: DatabaseKind::class.java.classLoader?.getResourceAsStream(kind.schemaResource)
        ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(kind.schemaResource)
        ?: error("schema resource not found on classpath: $resourcePath")
    return stream.bufferedReader().use { it.readText() }
}

object AndroidStorage {
    fun openUser(context: Context, path: String, runtimeVersion: String = "0.1.0-alpha", nowIso: () -> String): OpenDatabase =
        open(context, DatabaseKind.USER, path, runtimeVersion, nowIso)

    fun openVault(context: Context, path: String, runtimeVersion: String = "0.1.0-alpha", nowIso: () -> String): OpenDatabase =
        open(context, DatabaseKind.VAULT, path, runtimeVersion, nowIso)

    fun openProject(context: Context, path: String, runtimeVersion: String = "0.1.0-alpha", nowIso: () -> String): OpenDatabase =
        open(context, DatabaseKind.PROJECT, path, runtimeVersion, nowIso)

    private fun open(
        context: Context,
        kind: DatabaseKind,
        path: String,
        runtimeVersion: String,
        nowIso: () -> String,
    ): OpenDatabase {
        val driver = createAndroidDriver(context, path)
        val schemaSql = readAndroidSchemaResource(kind)
        val result = MigrationRunner.open(driver, kind, schemaSql, runtimeVersion, nowIso)
        return OpenDatabase(driver, result)
    }
}
