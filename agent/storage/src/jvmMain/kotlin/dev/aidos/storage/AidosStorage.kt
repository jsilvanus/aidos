package dev.aidos.storage

import app.cash.sqldelight.db.SqlDriver

/** An opened database and what `MigrationRunner` found when it opened. */
data class OpenDatabase(val driver: SqlDriver, val result: OpenResult)

/**
 * Fresh-install entry points for the three databases RFC-0040 defines. Desktop paths only --
 * mobile's app-private paths need an Android `Context` and arrive with the app (RFC-0099 Phase 4),
 * same as `androidTarget()` on this module.
 */
object DesktopPaths {
    fun userDb(home: String = System.getProperty("user.home")): String = "$home/.aidos/user.db"

    fun vaultDb(home: String = System.getProperty("user.home")): String =
        "$home/.aidos/secrets/vault.db"

    fun stateDb(projectRoot: String): String = "$projectRoot/.aidos/state.db"
}

object AidosStorage {
    fun openUser(path: String, runtimeVersion: String, nowIso: () -> String): OpenDatabase =
        open(DatabaseKind.USER, path, runtimeVersion, nowIso)

    fun openVault(path: String, runtimeVersion: String, nowIso: () -> String): OpenDatabase =
        open(DatabaseKind.VAULT, path, runtimeVersion, nowIso)

    fun openProject(path: String, runtimeVersion: String, nowIso: () -> String): OpenDatabase =
        open(DatabaseKind.PROJECT, path, runtimeVersion, nowIso)

    private fun open(
        kind: DatabaseKind,
        path: String,
        runtimeVersion: String,
        nowIso: () -> String,
    ): OpenDatabase {
        val driver = createJvmDriver(path)
        val schemaSql = readSchemaResource(kind)
        val result = MigrationRunner.open(driver, kind, schemaSql, runtimeVersion, nowIso)
        return OpenDatabase(driver, result)
    }
}
