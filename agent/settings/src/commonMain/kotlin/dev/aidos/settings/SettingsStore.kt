package dev.aidos.settings

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

/** True for the classes only the user may set (RFC-0036 scope-class table). */
internal val ScopeClass.userOnly: Boolean
    get() = this == ScopeClass.SECURITY || this == ScopeClass.SPEND

/**
 * Nearest-first settings resolution across session, project, workspace and user scope
 * (RFC-0036, RFC-0054).
 *
 * Resolution order: session → project → workspace → user → declared default.
 *
 * - SECURITY and SPEND settings may only come from user scope. A project or session attempting
 *   one is reported as a [SettingErrorClass.SCOPE_VIOLATION] and skipped; the refusal is carried
 *   on the result as [Resolved.overriddenBy].
 * - PROJECT_SAFE settings may not come from a session (RFC-0036 table).
 * - An invalid value falls back to the setting's **default**, not to the next scope; for a
 *   SECURITY setting it falls back to the most restrictive valid value instead.
 *
 * Project scope comes from the `aidos.toml` snapshot a [ProjectSettingsLoader] produced when one
 * is supplied (it carries line numbers); otherwise from the cached `settings` rows in `state.db`.
 */
class SettingsStore(
    /** Driver for user.db — holds user and workspace scope settings. */
    private val userDriver: SqlDriver,
    /** Driver for state.db — holds the project-scope cache. */
    private val projectDriver: SqlDriver?,
) {

    /**
     * Resolve a setting through the full scope stack.
     *
     * Violations and invalid values are appended to [errors] rather than thrown: resolution
     * continues and the problem is surfaced to the user separately.
     */
    fun <T> resolve(
        descriptor: SettingDescriptor<T>,
        sessionValues: Map<String, String> = emptyMap(),
        projectId: String? = null,
        @Suppress("UNUSED_PARAMETER") sessionId: String? = null,
        errors: MutableList<SettingError> = mutableListOf(),
        project: ProjectSettingsSnapshot? = null,
    ): Resolved<T> {
        var refusedBy: ScopeClass? = null

        // 1. Session scope (in-memory only, never persisted)
        val sessionJson = sessionValues[descriptor.key]
        if (sessionJson != null) {
            if (descriptor.scopeClass != ScopeClass.PREFERENCE) {
                errors.add(SettingError(
                    key = descriptor.key, originPath = "session", line = null,
                    message = "'${descriptor.key}' is ${descriptor.scopeClass} and cannot be set by a session. Ignored.",
                    errorClass = SettingErrorClass.SCOPE_VIOLATION,
                ))
                refusedBy = descriptor.scopeClass
            } else {
                val element = runCatching { Json.parseToJsonElement(sessionJson) }.getOrNull()
                val decoded = element?.let { descriptor.codec.decode(it) }
                if (decoded != null && decoded.isSuccess) {
                    return Resolved(decoded.getOrThrow(), SettingOrigin.SESSION, "session")
                }
                errors.add(SettingError(
                    key = descriptor.key, originPath = "session", line = null,
                    message = "invalid value for '${descriptor.key}': " +
                        (decoded?.exceptionOrNull()?.message ?: "unparseable JSON") + ". Using default.",
                    errorClass = SettingErrorClass.VALIDATION_FAILED,
                ))
                return Resolved(descriptor.default, SettingOrigin.DEFAULT, null)
            }
        }

        // 2. Project scope
        val entry: ProjectEntry? = when {
            project != null -> project.entries[descriptor.key]
            projectDriver != null && projectId != null ->
                readRow(projectDriver, "project", projectId, descriptor.key)
                    ?.let { text ->
                        val el = runCatching { Json.parseToJsonElement(text) }.getOrNull()
                        ProjectEntry(el, line = null, rawText = text)
                    }
            else -> null
        }
        if (entry != null) {
            val path = originPath(project?.sourcePath ?: "aidos.toml", entry.line)
            if (descriptor.scopeClass.userOnly) {
                errors.add(SettingError(
                    key = descriptor.key, originPath = path, line = entry.line,
                    message = "'${descriptor.key}' is ${descriptor.scopeClass} and cannot be set by a project. " +
                        "Ignored. Set it in your own settings if you want it.",
                    errorClass = SettingErrorClass.SCOPE_VIOLATION,
                ))
                refusedBy = descriptor.scopeClass
            } else {
                val decoded = entry.value?.let { descriptor.codec.decode(it) }
                    ?: Result.failure(IllegalArgumentException("unparseable JSON '${entry.rawText}'"))
                if (decoded.isSuccess) {
                    return Resolved(decoded.getOrThrow(), SettingOrigin.PROJECT, path, refusedBy)
                }
                // Invalid: the default, not the next scope (RFC-0036 Fail closed).
                errors.add(SettingError(
                    key = descriptor.key, originPath = path, line = entry.line,
                    message = "invalid value for '${descriptor.key}': ${decoded.exceptionOrNull()?.message}. Using default.",
                    errorClass = SettingErrorClass.VALIDATION_FAILED,
                ))
                return Resolved(descriptor.default, SettingOrigin.DEFAULT, null, refusedBy)
            }
        }

        // 3. Workspace scope — RFC-0036 MVP: a single implicit workspace, no workspace rows.

        // 4. User scope
        val userText = readRow(userDriver, "user", null, descriptor.key)
        if (userText != null) {
            val decoded = runCatching { Json.parseToJsonElement(userText) }
                .fold({ descriptor.codec.decode(it) }, { Result.failure(it) })
            if (decoded.isSuccess) {
                return Resolved(decoded.getOrThrow(), SettingOrigin.USER, "user.db", refusedBy)
            }
            val closed = if (descriptor.scopeClass == ScopeClass.SECURITY) descriptor.mostRestrictive else null
            errors.add(SettingError(
                key = descriptor.key, originPath = "user.db", line = null,
                message = "invalid value for '${descriptor.key}': ${decoded.exceptionOrNull()?.message}. " +
                    if (closed != null) "Using most restrictive value." else "Using default.",
                errorClass = SettingErrorClass.VALIDATION_FAILED,
            ))
            return if (closed != null) Resolved(closed, SettingOrigin.USER, "user.db", refusedBy)
            else Resolved(descriptor.default, SettingOrigin.DEFAULT, null, refusedBy)
        }

        // 5. Declared default
        return Resolved(descriptor.default, SettingOrigin.DEFAULT, null, refusedBy)
    }

    private fun originPath(source: String, line: Int?) = if (line != null) "$source:$line" else source

    private fun readRow(driver: SqlDriver, scope: String, scopeId: String?, key: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT value_json FROM settings WHERE scope = ? AND scope_id IS ? AND key = ?",
            mapper = { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getString(0) else null) },
            parameters = 3,
        ) {
            bindString(0, scope)
            bindString(1, scopeId)
            bindString(2, key)
        }.value
}

/** One project-scope value as read from `aidos.toml`. [value] is null when the cached text did not parse. */
data class ProjectEntry(val value: JsonElement?, val line: Int?, val rawText: String? = null)

/**
 * Writes settings, enforcing the same scope rules as resolution at write time and auditing
 * SECURITY/SPEND changes (RFC-0036 Security 1 and 5).
 */
class SettingsWriter(
    private val userDriver: SqlDriver,
    private val projectDriver: SqlDriver? = null,
    private val audit: SettingsAuditSink? = null,
) {

    /**
     * Write a setting to user scope — the only write path for SECURITY and SPEND settings.
     *
     * The value is validated with the descriptor's codec first; an invalid value is rejected and
     * nothing is written. A SECURITY/SPEND change is audited with old value, new value and actor.
     */
    fun writeUser(
        descriptor: SettingDescriptor<*>,
        value: JsonElement,
        setByKind: SettingSetByKind,
        nowIso: String,
        actor: ActorRef = defaultActor(setByKind),
    ): Result<Unit> {
        descriptor.codec.decode(value).onFailure {
            return Result.failure(IllegalArgumentException(
                "invalid value for '${descriptor.key}': ${it.message}"))
        }
        val old = readValue(userDriver, "user", null, descriptor.key)
        val new = value.toString()
        upsert(userDriver, "user", null, descriptor.key, new, setByKind, nowIso)
        if (descriptor.scopeClass.userOnly && old != new) {
            audit?.record(SettingAuditEntry(
                occurredAt = nowIso, kind = SettingAuditKind.CHANGED, scope = "user", scopeId = null,
                key = descriptor.key, oldValueJson = old, newValueJson = new, actor = actor,
            ))
        }
        return Result.success(Unit)
    }

    /**
     * Write a setting to the project-scope cache.
     *
     * Rejects SECURITY and SPEND settings — returns an error rather than writing — and audits the
     * attempt.
     */
    fun writeProject(
        descriptor: SettingDescriptor<*>,
        projectId: String,
        value: JsonElement,
        setByKind: SettingSetByKind,
        nowIso: String,
        actor: ActorRef = defaultActor(setByKind),
    ): Result<Unit> {
        if (descriptor.scopeClass.userOnly) {
            audit?.record(SettingAuditEntry(
                occurredAt = nowIso, kind = SettingAuditKind.SCOPE_VIOLATION, scope = "project",
                scopeId = projectId, key = descriptor.key, oldValueJson = null,
                newValueJson = value.toString(), actor = actor,
            ))
            return Result.failure(
                IllegalArgumentException(
                    "'${descriptor.key}' is ${descriptor.scopeClass} and cannot be set at project scope."
                )
            )
        }
        descriptor.codec.decode(value).onFailure {
            return Result.failure(IllegalArgumentException(
                "invalid value for '${descriptor.key}': ${it.message}"))
        }
        if (projectDriver == null) return Result.failure(IllegalStateException("no project driver"))
        upsert(projectDriver, "project", projectId, descriptor.key, value.toString(), setByKind, nowIso)
        return Result.success(Unit)
    }

    private fun defaultActor(kind: SettingSetByKind) = when (kind) {
        SettingSetByKind.USER -> ActorRef(ActorKind.USER, "local")
        SettingSetByKind.RUNTIME -> ActorRef(ActorKind.RUNTIME, "runtime")
    }

    private fun readValue(driver: SqlDriver, scope: String, scopeId: String?, key: String): String? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT value_json FROM settings WHERE scope = ? AND scope_id IS ? AND key = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 3,
        ) {
            bindString(0, scope)
            bindString(1, scopeId)
            bindString(2, key)
        }.value

    private fun upsert(
        driver: SqlDriver, scope: String, scopeId: String?, key: String,
        valueJson: String, setByKind: SettingSetByKind, nowIso: String,
    ) {
        // The primary key (scope, scope_id, key) does not conflict on a NULL scope_id in SQLite, so
        // an ON CONFLICT upsert would insert a duplicate user row each time. Delete-then-insert,
        // in one transaction so a crash between the two cannot lose the setting.
        driver.inTransaction {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM settings WHERE scope = ? AND scope_id IS ? AND key = ?",
            parameters = 3,
        ) {
            bindString(0, scope)
            bindString(1, scopeId)
            bindString(2, key)
        }
        driver.execute(
            identifier = null,
            sql = "INSERT INTO settings (scope, scope_id, key, value_json, set_at, set_by_kind) " +
                "VALUES (?, ?, ?, ?, ?, ?)",
            parameters = 6,
        ) {
            bindString(0, scope)
            bindString(1, scopeId)
            bindString(2, key)
            bindString(3, valueJson)
            bindString(4, nowIso)
            bindString(5, setByKind.name)
        }
        }
    }
}

/**
 * Run [block] in one SQLite transaction, rolling back if it throws. A bare [SqlDriver] has no
 * public transaction entry point (`Transaction.endTransaction` is protected), so this reaches it
 * through a `TransacterImpl`, as the executor does.
 */
internal fun <R> SqlDriver.inTransaction(block: () -> R): R =
    object : TransacterImpl(this) {}.transactionWithResult { block() }
