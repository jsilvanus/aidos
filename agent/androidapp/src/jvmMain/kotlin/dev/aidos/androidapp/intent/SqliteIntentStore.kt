package dev.aidos.androidapp.intent

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.ActorRef

/**
 * Persists flat intent items to `intent_nodes` (RFC-0012, D20: "task list only... built last
 * and small" — flat, no hierarchy, no dependencies). Every row is written with
 * `type = 'GOAL'`; the schema's hierarchy columns (`parent_id`) and acceptance-criteria columns
 * (`check_kind`, `check_spec`, `verification_met`, `verified_by_*`) are nullable and
 * intentionally left unset — D20 decided against building that scope, not this store against
 * using it.
 *
 * Two things this store does **not** do, left for whoever picks them up next (see PIPELINE.md):
 * - **`intent_proposals`**: `proposed_by_run_id` and `audit_ref` are foreign keys into `runs`
 *   and `audit_log`. `IntentProposal` (this package) doesn't carry a real run ID, taint, or an
 *   audit row to reference — persisting proposals needs that integration first, not a plausible
 *   placeholder value invented here.
 * - **`intent_edges`**: unused by construction (D20: no dependencies in this RFC's decided scope).
 * - **`targetedByRunId`**: derived from `execution_edges` (`edge_kind = 'TARGETED'`), which
 *   nothing currently writes. [listActive] always returns this field as `null`.
 */
class SqliteIntentStore(private val driver: SqlDriver) {

    fun create(item: IntentItem, projectId: String, actor: ActorRef, nowIso: String) {
        val override = item.userOverride
        driver.execute(
            identifier = null,
            sql = "INSERT INTO intent_nodes " +
                "(id, project_id, type, title, description, priority, lifecycle, " +
                "asserted_status, asserted_at, asserted_by_user_id, " +
                "created_at, created_by_kind, created_by_id, modified_at, modified_by_kind, modified_by_id) " +
                "VALUES (?, ?, 'GOAL', ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            parameters = 14,
        ) {
            bindString(0, item.id)
            bindString(1, projectId)
            bindString(2, item.title)
            bindString(3, item.description)
            bindLong(4, priorityToInt(item.priority).toLong())
            bindString(5, override?.claimedStatus?.name)
            bindString(6, override?.overriddenAt)
            bindString(7, override?.overriddenByUserId)
            bindString(8, nowIso)
            bindString(9, actor.kind.name)
            bindString(10, actor.id)
            bindString(11, nowIso)
            bindString(12, actor.kind.name)
            bindString(13, actor.id)
        }
    }

    /** Active (non-archived) items for a project, ordered by priority then creation time. */
    fun listActive(projectId: String): List<IntentItem> {
        val results = mutableListOf<IntentItem>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, title, description, priority, asserted_status, asserted_at, asserted_by_user_id " +
                "FROM intent_nodes WHERE project_id = ? AND lifecycle = 'ACTIVE' ORDER BY priority, created_at",
            mapper = { cursor ->
                while (cursor.next().value) {
                    val assertedStatus = cursor.getString(4)
                    val override = if (assertedStatus != null) {
                        UserStatusOverride(
                            claimedStatus = DerivedIntentStatus.valueOf(assertedStatus),
                            overriddenAt = cursor.getString(5) ?: "",
                            overriddenByUserId = cursor.getString(6) ?: "",
                        )
                    } else null
                    results.add(
                        IntentItem(
                            id = cursor.getString(0)!!,
                            title = cursor.getString(1)!!,
                            description = cursor.getString(2) ?: "",
                            priority = intToPriority(cursor.getLong(3)!!.toInt()),
                            targetedByRunId = null, // not wired — see class doc
                            userOverride = override,
                        )
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 1,
        ) { bindString(0, projectId) }
        return results
    }

    /** Archives an item — authorship removal, not progress (RFC-0012: `lifecycle`, not status). */
    fun archive(id: String, actor: ActorRef, nowIso: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE intent_nodes SET lifecycle = 'ARCHIVED', modified_at = ?, modified_by_kind = ?, modified_by_id = ? WHERE id = ?",
            parameters = 4,
        ) {
            bindString(0, nowIso)
            bindString(1, actor.kind.name)
            bindString(2, actor.id)
            bindString(3, id)
        }
    }

    /** Records or clears a user's override claim (D20: "a timestamped claim shown alongside the derived value"). */
    fun setUserOverride(id: String, override: UserStatusOverride?, actor: ActorRef, nowIso: String) {
        driver.execute(
            identifier = null,
            sql = "UPDATE intent_nodes SET asserted_status = ?, asserted_at = ?, asserted_by_user_id = ?, " +
                "modified_at = ?, modified_by_kind = ?, modified_by_id = ? WHERE id = ?",
            parameters = 7,
        ) {
            bindString(0, override?.claimedStatus?.name)
            bindString(1, override?.overriddenAt)
            bindString(2, override?.overriddenByUserId)
            bindString(3, nowIso)
            bindString(4, actor.kind.name)
            bindString(5, actor.id)
            bindString(6, id)
        }
    }

    private fun priorityToInt(p: IntentPriority): Int = when (p) {
        IntentPriority.HIGH -> 10
        IntentPriority.MEDIUM -> 50
        IntentPriority.LOW -> 100
    }

    private fun intToPriority(v: Int): IntentPriority = when {
        v <= 25 -> IntentPriority.HIGH
        v <= 75 -> IntentPriority.MEDIUM
        else -> IntentPriority.LOW
    }
}
