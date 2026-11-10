package dev.aidos.settings

import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import kotlinx.serialization.json.JsonElement

/**
 * The declared-key entries of one `aidos.toml`, with the line each came from (RFC-0036).
 *
 * Unknown, removed and refused keys are not here: they are never interpreted.
 */
data class ProjectSettingsSnapshot(
    val sourcePath: String,
    val entries: Map<String, ProjectEntry>,
) {
    companion object {
        val EMPTY = ProjectSettingsSnapshot("aidos.toml", emptyMap())
    }
}

/** What loading a project file produced: the snapshot to resolve against, and what to tell the user. */
data class ProjectSettingsLoad(
    val snapshot: ProjectSettingsSnapshot,
    /** Parse errors, scope violations, validation failures, unknown/renamed/removed keys — each with file and line. */
    val diagnostics: List<SettingError>,
)

/**
 * Loads a project's Git-tracked `aidos.toml` (RFC-0036, RFC-0010).
 *
 * A project file arrives with the repository, so anything in it is untrusted input:
 *
 * - a SECURITY/SPEND key is a visible [SettingErrorClass.SCOPE_VIOLATION] with file and line, is
 *   audited, and is never cached or applied;
 * - an invalid value is reported with its line and resolves to the default;
 * - an unknown key is a warning and is **preserved** in the cache, never interpreted, so an older
 *   runtime does not erase a newer runtime's setting (RFC-0039);
 * - a renamed key is honoured under its new name with a warning; a removed key is kept but
 *   ignored with a warning (RFC-0036 Migration).
 *
 * The `settings` rows in `state.db` are a cache of what this loader saw; the file is the source.
 * File IO is the caller's: pass the text, or null when the project has no `aidos.toml`.
 */
class ProjectSettingsLoader(
    private val projectDriver: SqlDriver?,
    private val audit: SettingsAuditSink? = null,
    private val renamedKeys: Map<String, String> = Settings.renamedKeys,
    private val removedKeys: Set<String> = Settings.removedKeys,
) {

    fun load(projectId: String, content: String?, nowIso: String, sourcePath: String = "aidos.toml"): ProjectSettingsLoad {
        val diagnostics = mutableListOf<SettingError>()
        val entries = mutableMapOf<String, ProjectEntry>()
        val preserved = mutableMapOf<String, JsonElement>()   // keys cached but never interpreted

        if (content != null) {
            val parsed = TomlParser.parse(content, sourcePath)
            diagnostics += parsed.errors

            // Explicitly-new keys beat an old alias for the same setting.
            val ordered = parsed.values.keys.sortedBy { it in renamedKeys }
            for (rawKey in ordered) {
                val value = parsed.values.getValue(rawKey)
                val line = parsed.lines[rawKey]
                val path = if (line != null) "$sourcePath:$line" else sourcePath
                var key = rawKey

                if (key in removedKeys) {
                    diagnostics += SettingError(key, path, line,
                        "'$key' has been removed and is ignored.", SettingErrorClass.DEPRECATED_KEY)
                    preserved[key] = value
                    continue
                }
                val newKey = renamedKeys[rawKey]
                if (newKey != null) {
                    diagnostics += SettingError(rawKey, path, line,
                        "'$rawKey' was renamed to '$newKey'; honouring it under the new name.",
                        SettingErrorClass.DEPRECATED_KEY)
                    if (newKey in parsed.values) continue  // the explicit new key wins
                    key = newKey
                }

                val descriptor = Settings.forKey(key)
                if (descriptor == null) {
                    diagnostics += SettingError(key, path, line,
                        "unknown setting '$key'. Kept, not interpreted.", SettingErrorClass.UNKNOWN_KEY)
                    preserved[key] = value
                    continue
                }
                if (descriptor.scopeClass.userOnly) {
                    diagnostics += SettingError(key, path, line,
                        "$sourcePath sets ${descriptor.key}, which cannot be set by a project. Ignored. " +
                            "Set it in your own settings if you want it.",
                        SettingErrorClass.SCOPE_VIOLATION)
                    audit?.record(SettingAuditEntry(
                        occurredAt = nowIso, kind = SettingAuditKind.SCOPE_VIOLATION, scope = "project",
                        scopeId = projectId, key = descriptor.key, oldValueJson = null,
                        newValueJson = value.toString(), actor = ActorRef(ActorKind.RUNTIME, "aidos.toml"),
                    ))
                    continue
                }
                descriptor.codec.decode(value).onFailure {
                    diagnostics += SettingError(key, path, line,
                        "invalid value for '$key': ${it.message}. Using default.",
                        SettingErrorClass.VALIDATION_FAILED)
                }
                // Invalid values stay in the snapshot so resolution returns the default rather
                // than falling through to the user's value (RFC-0036 Fail closed).
                entries[key] = ProjectEntry(value, line)
            }
        }

        cache(projectId, entries, preserved, nowIso)
        return ProjectSettingsLoad(ProjectSettingsSnapshot(sourcePath, entries), diagnostics)
    }

    /** Replace the runtime-written cache rows for this project; rows a user wrote by hand stay. */
    private fun cache(projectId: String, entries: Map<String, ProjectEntry>, preserved: Map<String, JsonElement>, nowIso: String) {
        val driver = projectDriver ?: return
        driver.inTransaction {
            driver.execute(null,
                "DELETE FROM settings WHERE scope = 'project' AND scope_id = ? AND set_by_kind = 'RUNTIME'", 1) {
                bindString(0, projectId)
            }
            val all = entries.mapValues { it.value.value!! } + preserved
            for ((key, value) in all) {
                driver.execute(null,
                    "INSERT OR REPLACE INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) " +
                        "VALUES ('project', ?, ?, ?, ?, 'RUNTIME')", 4) {
                    bindString(0, projectId)
                    bindString(1, key)
                    bindString(2, value.toString())
                    bindString(3, nowIso)
                }
            }
        }
    }
}
