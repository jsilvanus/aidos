package dev.aidos.settings

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/**
 * Nearest-first settings resolution across user, workspace, project, and session scopes
 * (RFC-0036, RFC-0054).
 *
 * Resolution order: session → project → workspace → user → declared default.
 *
 * SECURITY and SPEND settings may only exist at user or workspace scope. A project or session
 * attempting one produces a visible error and, for project attempts, an audit row.
 *
 * [SettingsStore] is the single read path. Writes go through [SettingsWriter], which enforces
 * the same scope rules at write time.
 */
class SettingsStore(
    /** Driver for user.db — holds user and workspace scope settings. */
    private val userDriver: SqlDriver,
    /** Driver for state.db — holds project and session scope settings. */
    private val projectDriver: SqlDriver?,
) {

    /**
     * Resolve a setting through the full scope stack (RFC-0036).
     *
     * The caller receives the effective value and its provenance. A scope violation (project
     * attempting SECURITY/SPEND) is returned in [errors] rather than thrown — the resolution
     * continues and the violation is surfaced to the user separately.
     */
    fun <T> resolve(
        descriptor: SettingDescriptor<T>,
        sessionValues: Map<String, String> = emptyMap(),
        projectId: String? = null,
        sessionId: String? = null,
        errors: MutableList<SettingError> = mutableListOf(),
    ): Resolved<T> {
        // 1. Session scope (in-memory only, never persisted)
        val sessionJson = sessionValues[descriptor.key]
        if (sessionJson != null) {
            if (descriptor.scopeClass == ScopeClass.SECURITY || descriptor.scopeClass == ScopeClass.SPEND) {
                errors.add(SettingError(
                    key = descriptor.key,
                    originPath = "session",
                    line = null,
                    message = "'${descriptor.key}' is ${descriptor.scopeClass} and cannot be set by a session. Ignored.",
                    errorClass = SettingErrorClass.SCOPE_VIOLATION,
                ))
                // fall through to lower scopes
            } else {
                val element = runCatching { Json.parseToJsonElement(sessionJson) }.getOrNull()
                if (element != null) {
                    val decoded = descriptor.codec.decode(element)
                    if (decoded.isSuccess) {
                        return Resolved(decoded.getOrThrow(), SettingOrigin.SESSION, "session")
                    } else {
                        errors.add(SettingError(
                            key = descriptor.key,
                            originPath = "session",
                            line = null,
                            message = "invalid value for '${descriptor.key}': ${decoded.exceptionOrNull()?.message}",
                            errorClass = SettingErrorClass.VALIDATION_FAILED,
                        ))
                    }
                }
            }
        }

        // 2. Project scope (from state.db)
        if (projectDriver != null && projectId != null) {
            val row = readRow(projectDriver, "project", projectId, descriptor.key)
            if (row != null) {
                if (descriptor.scopeClass == ScopeClass.SECURITY || descriptor.scopeClass == ScopeClass.SPEND) {
                    errors.add(SettingError(
                        key = descriptor.key,
                        originPath = "aidos.toml",
                        line = null,
                        message = "'${descriptor.key}' is ${descriptor.scopeClass} and cannot be set by a project. " +
                            "Ignored. Set it in your own settings if you want it.",
                        errorClass = SettingErrorClass.SCOPE_VIOLATION,
                    ))
                    // fall through — scope violation does not use the value
                } else {
                    val decoded = decodeValue(descriptor, row, "aidos.toml", errors)
                    if (decoded != null) {
                        return Resolved(decoded, SettingOrigin.PROJECT, "aidos.toml",
                            overriddenBy = null)
                    }
                }
            }
        }

        // 3. Workspace scope (from user.db)
        // MVP: single implicit workspace. workspace_id is omitted for user scope; for workspace
        // scope scope_id is the workspace id. Workspace support arrives with M2 — for now, skip.

        // 4. User scope (from user.db)
        val userRow = readRow(userDriver, "user", null, descriptor.key)
        if (userRow != null) {
            val decoded = decodeValue(descriptor, userRow, "user.db", errors)
            if (decoded != null) {
                return Resolved(decoded, SettingOrigin.USER, "user.db")
            }
        }

        // 5. Declared default
        return Resolved(descriptor.default, SettingOrigin.DEFAULT, null)
    }

    private fun readRow(driver: SqlDriver, scope: String, scopeId: String?, key: String): String? {
        val sql = if (scopeId != null) {
            "SELECT value_json FROM settings WHERE scope = '$scope' AND scope_id = '$scopeId' AND key = '$key'"
        } else {
            "SELECT value_json FROM settings WHERE scope = '$scope' AND scope_id IS NULL AND key = '$key'"
        }
        return driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null)
            },
            parameters = 0,
        ).value
    }

    private fun <T> decodeValue(
        descriptor: SettingDescriptor<T>,
        jsonText: String,
        originPath: String,
        errors: MutableList<SettingError>,
    ): T? {
        val element = runCatching { Json.parseToJsonElement(jsonText) }.getOrElse {
            errors.add(SettingError(descriptor.key, originPath, null,
                "unparseable JSON for '${descriptor.key}': ${it.message}",
                SettingErrorClass.VALIDATION_FAILED))
            return null
        }
        val result = descriptor.codec.decode(element)
        if (result.isSuccess) return result.getOrThrow()

        val failClosedValue: T? = if (descriptor.scopeClass == ScopeClass.SECURITY) {
            descriptor.mostRestrictive
        } else {
            null  // fall through to lower scope / default
        }
        errors.add(SettingError(descriptor.key, originPath, null,
            "invalid value for '${descriptor.key}': ${result.exceptionOrNull()?.message}. " +
                if (failClosedValue != null) "Using most restrictive value." else "Using default.",
            SettingErrorClass.VALIDATION_FAILED))
        return failClosedValue
    }
}

/**
 * Writes a setting to user.db (user scope) or state.db (project scope).
 *
 * Write-time enforcement mirrors read-time: SECURITY and SPEND keys are rejected at project scope
 * with a visible error.
 */
class SettingsWriter(
    private val userDriver: SqlDriver,
    private val projectDriver: SqlDriver? = null,
) {

    /**
     * Write a setting to user scope.
     *
     * This is the only write path for SECURITY and SPEND settings. It does not validate against
     * possible values — the caller is responsible for supplying a valid encoded value.
     */
    fun writeUser(descriptor: SettingDescriptor<*>, value: JsonElement, setByKind: SettingSetByKind, nowIso: String) {
        upsert(userDriver, "user", null, descriptor.key, value.toString(), setByKind, nowIso)
    }

    /**
     * Write a setting to project scope.
     *
     * Rejects SECURITY and SPEND settings — returns an error rather than writing.
     */
    fun writeProject(
        descriptor: SettingDescriptor<*>,
        projectId: String,
        value: JsonElement,
        setByKind: SettingSetByKind,
        nowIso: String,
    ): Result<Unit> {
        if (descriptor.scopeClass == ScopeClass.SECURITY || descriptor.scopeClass == ScopeClass.SPEND) {
            return Result.failure(
                IllegalArgumentException(
                    "'${descriptor.key}' is ${descriptor.scopeClass} and cannot be set at project scope."
                )
            )
        }
        if (projectDriver == null) return Result.failure(IllegalStateException("no project driver"))
        upsert(projectDriver, "project", projectId, descriptor.key, value.toString(), setByKind, nowIso)
        return Result.success(Unit)
    }

    private fun upsert(
        driver: SqlDriver,
        scope: String,
        scopeId: String?,
        key: String,
        valueJson: String,
        setByKind: SettingSetByKind,
        nowIso: String,
    ) {
        val scopeIdSql = if (scopeId != null) "'$scopeId'" else "NULL"
        driver.execute(
            identifier = null,
            sql = "INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) " +
                "VALUES ('$scope', $scopeIdSql, '$key', ?, '$nowIso', '${setByKind.name}') " +
                "ON CONFLICT(scope, scope_id, key) DO UPDATE SET " +
                "value_json = excluded.value_json, set_at = excluded.set_at, set_by_kind = excluded.set_by_kind",
            parameters = 1,
        ) {
            bindString(0, valueJson)
        }
    }
}
