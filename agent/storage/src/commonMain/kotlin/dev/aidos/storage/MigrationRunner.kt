package dev.aidos.storage

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ErrorCodes
import kotlin.time.TimeSource

/** A forward-only schema migration targeting [toVersion] (RFC-0039, RFC-0040). */
data class SchemaMigration(
    val toVersion: Int,
    val statements: List<String>,
)

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
        migrations: List<SchemaMigration> = emptyList(),
    ): OpenResult {
        val existing = readVersion(driver)
        return when {
            existing == null -> {
                bootstrap(driver, schemaSql, kind.currentVersion, runtimeVersion, nowIso())
                OpenResult.Ready(kind.currentVersion)
            }
            existing == kind.currentVersion -> OpenResult.Ready(existing)
            existing < kind.currentVersion -> {
                val appliedVersion = applyForwardMigrations(
                    driver = driver,
                    fromVersion = existing,
                    targetVersion = kind.currentVersion,
                    runtimeVersion = runtimeVersion,
                    nowIso = nowIso,
                    migrations = migrations,
                )
                OpenResult.Ready(appliedVersion)
            }
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

    private fun applyForwardMigrations(
        driver: SqlDriver,
        fromVersion: Int,
        targetVersion: Int,
        runtimeVersion: String,
        nowIso: () -> String,
        migrations: List<SchemaMigration>,
    ): Int {
        val pending = migrations
            .filter { it.toVersion in (fromVersion + 1)..targetVersion }
            .sortedBy { it.toVersion }
        if (pending.isEmpty()) return fromVersion

        val transacter = object : TransacterImpl(driver) {}
        var current = fromVersion
        for (migration in pending) {
            val mark = TimeSource.Monotonic.markNow()
            val appliedAt = nowIso()
            transacter.transaction {
                for (stmt in migration.statements) {
                    driver.execute(identifier = null, sql = stmt, parameters = 0)
                }
                val durationMs = mark.elapsedNow().inWholeMilliseconds
                driver.execute(
                    identifier = null,
                    sql = "UPDATE schema_versions SET version = ?, applied_at = ?, runtime_version = ? WHERE id = 1",
                    parameters = 3,
                ) {
                    bindLong(0, migration.toVersion.toLong())
                    bindString(1, appliedAt)
                    bindString(2, runtimeVersion)
                }
                recordMigrationHistory(driver, migration.toVersion, appliedAt, runtimeVersion, durationMs)
            }
            current = migration.toVersion
        }
        return current
    }

    private fun recordMigrationHistory(
        driver: SqlDriver,
        version: Int,
        appliedAt: String,
        runtimeVersion: String,
        durationMs: Long,
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR IGNORE INTO migration_history (version, applied_at, runtime_version, duration_ms) VALUES (?, ?, ?, ?)",
            parameters = 4,
        ) {
            bindLong(0, version.toLong())
            bindString(1, appliedAt)
            bindString(2, runtimeVersion)
            bindLong(3, durationMs)
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

    private const val AUTO_VACUUM_INCREMENTAL = 2L

    private fun readAutoVacuum(driver: SqlDriver): Long? =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA auto_vacuum",
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getLong(0) else null)
            },
            parameters = 0,
        ).value

    private fun bootstrap(
        driver: SqlDriver,
        schemaSql: String,
        version: Int,
        runtimeVersion: String,
        appliedAt: String,
    ) {
        driver.execute(identifier = null, sql = "PRAGMA auto_vacuum = INCREMENTAL", parameters = 0)
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
        recordMigrationHistory(driver, version, appliedAt, runtimeVersion, 0L)
        // If page 1 was already initialized before PRAGMA auto_vacuum = INCREMENTAL took effect
        // (for instance by readVersion() or WAL mode configuration), SQLite requires VACUUM on
        // the same connection to persist auto_vacuum = INCREMENTAL (2).
        if (readAutoVacuum(driver) != AUTO_VACUUM_INCREMENTAL && driver.currentTransaction() == null) {
            driver.execute(identifier = null, sql = "PRAGMA auto_vacuum = INCREMENTAL", parameters = 0)
            driver.execute(identifier = null, sql = "VACUUM", parameters = 0)
        }
    }
}
