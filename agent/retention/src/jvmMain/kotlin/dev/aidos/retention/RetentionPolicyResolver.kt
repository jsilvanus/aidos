package dev.aidos.retention

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.settings.ProjectSettingsSnapshot
import dev.aidos.settings.SettingError
import dev.aidos.settings.Settings
import dev.aidos.settings.SettingsStore

/**
 * Builds a project's [RetentionPolicy] through the settings layer (RFC-0036), so retention is
 * user-adjustable the same way every other preference is, and an `aidos.toml` in the repository
 * can tighten or loosen it for that project only.
 *
 * Precedence, nearest first:
 * 1. a `retention_policy` row for this project (`scope = 'project'`), per object class;
 * 2. a `retention_policy` row at user scope (`scope = 'user'`, null `scope_id`);
 * 3. the `retention.*` settings (session, project `aidos.toml`/cache, user, declared default).
 *
 * A `retention_policy` row with `retention_days` NULL means "never compact that object".
 */
class RetentionPolicyResolver(
    private val settings: SettingsStore,
    private val projectDriver: SqlDriver,
) {
    fun resolve(
        projectId: String,
        snapshot: ProjectSettingsSnapshot? = null,
        errors: MutableList<SettingError> = mutableListOf(),
    ): RetentionPolicy {
        fun <T> read(d: dev.aidos.settings.SettingDescriptor<T>): T =
            settings.resolve(d, projectId = projectId, project = snapshot, errors = errors).value

        return RetentionPolicy(
            agedDays = read(Settings.retentionAgedDays),
            toolResultDays = read(Settings.retentionToolResultDays),
            softLimitBytes = read(Settings.retentionSoftLimitMb) * RetentionPolicy.MB,
            neverCompact = read(Settings.retentionNeverCompact),
            perObjectDays = tableOverrides(projectId),
        )
    }

    private fun tableOverrides(projectId: String): Map<String, Int?> {
        val out = LinkedHashMap<String, Int?>()
        // User scope first so project rows overwrite it.
        for (scope in listOf("user", "project")) {
            val rows = projectDriver.queryList(
                "SELECT object_class, retention_days FROM retention_policy " +
                    "WHERE scope = ? AND scope_id IS ?",
                parameters = 2,
                bind = {
                    bindString(0, scope)
                    bindString(1, if (scope == "user") null else projectId)
                },
            ) { c -> c.getString(0)!! to c.getLong(1)?.toInt() }
            for ((key, days) in rows) {
                // Only AGED objects can be compacted; ignore rows naming anything else rather than
                // let a stray row weaken a PERMANENT or DURABLE class.
                if (RetainedObject.forKey(key)?.retentionClass == RetentionClass.AGED) out[key] = days
            }
        }
        return out
    }
}
