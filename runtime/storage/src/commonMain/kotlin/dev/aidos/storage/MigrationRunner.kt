package dev.aidos.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ErrorCodes

/** What `MigrationRunner.open` found. */
sealed class OpenResult {
    /** The database is at [version] and safe to read and write. */
    data class Ready(val version: Int) : OpenResult()

    /**
     * The database was written by a newer runtime. It opens read-only (RFC-0017, RFC-0039) --
     * [error] carries `storage.migration_required` and is not a refusal to open.
     */
    data class ReadOnly(val error: AidosError) : OpenResult()
}

/**
 * RFC-0040's `open(db)` state machine:
 *
 * ```
 * read schema_versions.version
 * if version == current → proceed
 * if version <  current → apply migrations in order, record each, then proceed
 * if version >  current → open READ-ONLY, storage.migration_required
 * ```
 *
 * At schema version 1 (M1, the only version that exists yet) the `<` branch has nothing to do
 * but bootstrap: a database with no `schema_versions` row is created fresh from [schemaSql] and
 * stamped at [DatabaseKind.currentVersion]. Forward migrations beyond v1 are added to this branch
 * as new versions are declared -- there is deliberately no migration list yet, because there is
 * nothing to migrate from.
 */
object MigrationRunner {

    fun open(
        driver: SqlDriver,
        kind: DatabaseKind,
        schemaSql: String,
        runtimeVersion: String,
        nowIso: () -> String,
    ): OpenResult {
        val existing = readVersion(driver)
        return when {
            existing == null -> {
                bootstrap(driver, schemaSql, kind.currentVersion, runtimeVersion, nowIso())
                OpenResult.Ready(kind.currentVersion)
            }
            existing <= kind.currentVersion -> OpenResult.Ready(existing)
            else -> OpenResult.ReadOnly(
                AidosError(
                    code = ErrorCodes.STORAGE_MIGRATION_REQUIRED,
                    errorClass = ErrorClass.CONFLICT,
                    message = "${kind.name.lowercase()} database is at schema version $existing; " +
                        "this runtime supports up to ${kind.currentVersion}. Opened read-only.",
                    detail = mapOf(
                        "found_version" to existing.toString(),
                        "supported_version" to kind.currentVersion.toString(),
                    ),
                ),
            )
        }
    }

    private fun readVersion(driver: SqlDriver): Int? {
        val tableExists = driver.executeQuery(
            identifier = null,
            sql = "SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'schema_versions'",
            mapper = { cursor -> QueryResult.Value(cursor.next().value) },
            parameters = 0,
        ).value
        if (!tableExists) return null

        return driver.executeQuery(
            identifier = null,
            sql = "SELECT version FROM schema_versions WHERE id = 1",
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0)?.toInt() else null)
            },
            parameters = 0,
        ).value
    }

    private fun bootstrap(
        driver: SqlDriver,
        schemaSql: String,
        version: Int,
        runtimeVersion: String,
        appliedAt: String,
    ) {
        for (statement in SqlScript.statements(schemaSql)) {
            driver.execute(identifier = null, sql = statement, parameters = 0)
        }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO schema_versions (id, version, applied_at, runtime_version) VALUES (1, ?, ?, ?)",
            parameters = 3,
        ) {
            bindLong(0, version.toLong())
            bindString(1, appliedAt)
            bindString(2, runtimeVersion)
        }
    }
}
