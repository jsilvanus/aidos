package dev.aidos.settings

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef

/** What a [SettingAuditEntry] records. */
enum class SettingAuditKind {
    /** A SECURITY or SPEND value was written (RFC-0036 Security 5). */
    CHANGED,

    /** A project (or session) tried to set a key it may not (RFC-0036 Security 1). */
    SCOPE_VIOLATION,
}

/**
 * One audited settings event: old value, new value, actor (RFC-0036 Security 5).
 *
 * For [SettingAuditKind.SCOPE_VIOLATION] the new value is the *attempted* value, which was not
 * applied — it is kept so "someone tried to turn this on" is reconstructible.
 */
data class SettingAuditEntry(
    val occurredAt: String,
    val kind: SettingAuditKind,
    val scope: String,
    val scopeId: String?,
    val key: String,
    val oldValueJson: String?,
    val newValueJson: String?,
    val actor: ActorRef,
)

/** Where settings audit rows go. Kept an interface so tests and other hosts can substitute. */
fun interface SettingsAuditSink {
    fun record(entry: SettingAuditEntry)
}

/**
 * Append-only audit in `user.db` (`settings_audit`).
 *
 * It is in user.db rather than project.db's `audit_log` because a user-scope SECURITY change
 * belongs to no project and `audit_log.project_id` is NOT NULL.
 */
class UserDbSettingsAudit(private val driver: SqlDriver) : SettingsAuditSink {

    override fun record(entry: SettingAuditEntry) {
        // aidos.toml is re-read every time the runtime builds an executor; an unchanged offending
        // file must not append the same violation each time.
        if (entry.kind == SettingAuditKind.SCOPE_VIOLATION && lastMatches(entry)) return
        driver.execute(
            identifier = null,
            sql = "INSERT INTO settings_audit " +
                "(occurred_at, kind, scope, scope_id, key, old_value_json, new_value_json, actor_kind, actor_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)",
            parameters = 9,
        ) {
            bindString(0, entry.occurredAt)
            bindString(1, entry.kind.name)
            bindString(2, entry.scope)
            bindString(3, entry.scopeId)
            bindString(4, entry.key)
            bindString(5, entry.oldValueJson)
            bindString(6, entry.newValueJson)
            bindString(7, entry.actor.kind.name)
            bindString(8, entry.actor.id)
        }
    }

    /** All rows, oldest first, optionally for one key. */
    fun entries(key: String? = null): List<SettingAuditEntry> {
        val rows = mutableListOf<SettingAuditEntry>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT occurred_at, kind, scope, scope_id, key, old_value_json, new_value_json, actor_kind, actor_id " +
                "FROM settings_audit WHERE (? IS NULL OR key = ?) ORDER BY seq",
            mapper = { c ->
                while (c.next().value) rows.add(readRow(c))
                QueryResult.Value(Unit)
            },
            parameters = 2,
        ) {
            bindString(0, key)
            bindString(1, key)
        }
        return rows
    }

    private fun lastMatches(e: SettingAuditEntry): Boolean {
        var last: SettingAuditEntry? = null
        driver.executeQuery(
            identifier = null,
            sql = "SELECT occurred_at, kind, scope, scope_id, key, old_value_json, new_value_json, actor_kind, actor_id " +
                "FROM settings_audit WHERE kind = ? AND scope = ? AND scope_id IS ? AND key = ? ORDER BY seq DESC LIMIT 1",
            mapper = { c ->
                if (c.next().value) last = readRow(c)
                QueryResult.Value(Unit)
            },
            parameters = 4,
        ) {
            bindString(0, e.kind.name)
            bindString(1, e.scope)
            bindString(2, e.scopeId)
            bindString(3, e.key)
        }
        return last?.newValueJson == e.newValueJson
    }

    private fun readRow(c: app.cash.sqldelight.db.SqlCursor) = SettingAuditEntry(
        occurredAt = c.getString(0)!!,
        kind = SettingAuditKind.valueOf(c.getString(1)!!),
        scope = c.getString(2)!!,
        scopeId = c.getString(3),
        key = c.getString(4)!!,
        oldValueJson = c.getString(5),
        newValueJson = c.getString(6),
        actor = ActorRef(ActorKind.valueOf(c.getString(7)!!), c.getString(8)!!),
    )
}
