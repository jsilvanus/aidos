package dev.aidos.models

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ModelKind
import java.time.Instant

/**
 * Database-backed implementation of ModelCatalogManager (RFC-0022).
 *
 * Manages model_catalog and installed_models tables in storage using SqlDelight's SqlDriver.
 * Lives in commonMain (not jvmMain, where it lived before being deleted in 7d2c9ea without a
 * replacement -- restored here since EngineService references it and the app cannot build
 * without it) so both Aidos Engine's Android app and any JVM host can use it. `java.time.Instant`
 * is safe in commonMain here because this module's targets are jvm()+androidTarget() only, both
 * of which have it (see dev.aidos.api.ProjectLocker's doc comment for the same reasoning about
 * java.io.File).
 */
class DatabaseModelCatalogManager(
    private val userDriver: SqlDriver,
) : ModelCatalogManager {

    override suspend fun addToCatalog(model: CatalogEntry): Result<Unit> = runCatching {
        userDriver.execute(
            identifier = null,
            sql = """
                INSERT OR REPLACE INTO model_catalog (id, name, kind, provider, remote_url, properties_json, discovered_at)
                VALUES (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            parameters = 7,
        ) {
            bindString(0, model.id)
            bindString(1, model.name)
            bindString(2, model.kind.name)
            bindString(3, model.provider)
            bindString(4, model.remoteUrl)
            bindString(5, model.propertiesJson)
            bindString(6, model.discoveredAt)
        }
    }

    override suspend fun listCatalog(): Result<List<CatalogEntry>> = runCatching {
        userDriver.executeQuery(
            identifier = null,
            sql = "SELECT id, name, kind, provider, remote_url, properties_json, discovered_at FROM model_catalog ORDER BY name",
            mapper = { c ->
                val results = mutableListOf<CatalogEntry>()
                while (c.next().value) {
                    results.add(
                        CatalogEntry(
                            id = c.getString(0)!!,
                            name = c.getString(1)!!,
                            kind = ModelKind.valueOf(c.getString(2)!!),
                            provider = c.getString(3)!!,
                            remoteUrl = c.getString(4),
                            propertiesJson = c.getString(5) ?: "{}",
                            discoveredAt = c.getString(6)!!,
                        )
                    )
                }
                QueryResult.Value(results)
            },
            parameters = 0,
        ) {
            // No parameters
        }.value
    }

    override suspend fun getCatalog(modelId: String): Result<CatalogEntry?> = runCatching {
        userDriver.executeQuery(
            identifier = null,
            sql = "SELECT id, name, kind, provider, remote_url, properties_json, discovered_at FROM model_catalog WHERE id = ?",
            mapper = { c ->
                if (c.next().value) {
                    QueryResult.Value(
                        CatalogEntry(
                            id = c.getString(0)!!,
                            name = c.getString(1)!!,
                            kind = ModelKind.valueOf(c.getString(2)!!),
                            provider = c.getString(3)!!,
                            remoteUrl = c.getString(4),
                            propertiesJson = c.getString(5) ?: "{}",
                            discoveredAt = c.getString(6)!!,
                        )
                    )
                } else {
                    QueryResult.Value(null)
                }
            },
            parameters = 1,
        ) {
            bindString(0, modelId)
        }.value
    }

    override suspend fun listInstalled(): Result<List<InstalledModel>> = runCatching {
        userDriver.executeQuery(
            identifier = null,
            sql = """
                SELECT model_id, digest, path, size_bytes, quantization, installed_at, last_loaded_at, user_label, properties_json
                FROM installed_models ORDER BY installed_at DESC
            """.trimIndent(),
            mapper = { c ->
                val results = mutableListOf<InstalledModel>()
                while (c.next().value) {
                    results.add(
                        InstalledModel(
                            modelId = c.getString(0)!!,
                            digest = c.getString(1)!!,
                            path = c.getString(2)!!,
                            sizeBytes = c.getLong(3)!!,
                            quantization = c.getString(4),
                            installedAt = c.getString(5)!!,
                            lastLoadedAt = c.getString(6),
                            userLabel = c.getString(7),
                            propertiesJson = c.getString(8) ?: "{}",
                        )
                    )
                }
                QueryResult.Value(results)
            },
            parameters = 0,
        ) {
            // No parameters
        }.value
    }

    override suspend fun markInstalled(
        modelId: String,
        digest: String,
        path: String,
        sizeBytes: Long,
        quantization: String?,
    ): Result<Unit> = runCatching {
        val now = Instant.now().toString()
        userDriver.execute(
            identifier = null,
            sql = """
                INSERT OR REPLACE INTO installed_models
                (model_id, digest, path, size_bytes, quantization, installed_at, properties_json)
                VALUES (?, ?, ?, ?, ?, ?, '{}')
            """.trimIndent(),
            parameters = 6,
        ) {
            bindString(0, modelId)
            bindString(1, digest)
            bindString(2, path)
            bindLong(3, sizeBytes)
            bindString(4, quantization)
            bindString(5, now)
        }
    }

    override suspend fun uninstall(modelId: String): Result<Unit> = runCatching {
        userDriver.execute(
            identifier = null,
            sql = "DELETE FROM installed_models WHERE model_id = ?",
            parameters = 1,
        ) {
            bindString(0, modelId)
        }
    }

    override suspend fun updateInstalledMetadata(
        modelId: String,
        userLabel: String?,
        propertiesJson: String?,
    ): Result<Unit> = runCatching {
        // Build dynamic UPDATE statement based on what's being updated
        when {
            userLabel != null && propertiesJson != null -> {
                // Update both fields
                userDriver.execute(
                    identifier = null,
                    sql = "UPDATE installed_models SET user_label = ?, properties_json = ? WHERE model_id = ?",
                    parameters = 3,
                ) {
                    bindString(0, userLabel)
                    bindString(1, propertiesJson)
                    bindString(2, modelId)
                }
            }
            userLabel != null -> {
                // Update only user_label
                userDriver.execute(
                    identifier = null,
                    sql = "UPDATE installed_models SET user_label = ? WHERE model_id = ?",
                    parameters = 2,
                ) {
                    bindString(0, userLabel)
                    bindString(1, modelId)
                }
            }
            propertiesJson != null -> {
                // Update only properties_json
                userDriver.execute(
                    identifier = null,
                    sql = "UPDATE installed_models SET properties_json = ? WHERE model_id = ?",
                    parameters = 2,
                ) {
                    bindString(0, propertiesJson)
                    bindString(1, modelId)
                }
            }
            // else: nothing to update, just return success
        }
    }

    companion object {
        /**
         * Creates model_catalog and installed_models if they don't exist yet. No migration
         * runner exists for this module (unlike agent/storage's MigrationRunner) -- this is the
         * schema's one and only version, called from the SqlDriver's SqlSchema.create() at first
         * open.
         */
        fun createTables(driver: SqlDriver) {
            driver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE IF NOT EXISTS model_catalog (
                        id TEXT PRIMARY KEY,
                        name TEXT NOT NULL,
                        kind TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        remote_url TEXT,
                        properties_json TEXT NOT NULL DEFAULT '{}',
                        discovered_at TEXT NOT NULL
                    )
                """.trimIndent(),
                parameters = 0,
            )
            driver.execute(
                identifier = null,
                sql = """
                    CREATE TABLE IF NOT EXISTS installed_models (
                        model_id TEXT PRIMARY KEY,
                        digest TEXT NOT NULL,
                        path TEXT NOT NULL,
                        size_bytes INTEGER NOT NULL,
                        quantization TEXT,
                        installed_at TEXT NOT NULL,
                        last_loaded_at TEXT,
                        user_label TEXT,
                        properties_json TEXT NOT NULL DEFAULT '{}'
                    )
                """.trimIndent(),
                parameters = 0,
            )
        }
    }
}
