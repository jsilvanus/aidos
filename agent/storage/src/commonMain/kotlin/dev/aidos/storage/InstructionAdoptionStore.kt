package dev.aidos.storage

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/**
 * RFC-0016 adoption writer. Adoption is project scope (`instruction_adoptions`, what the user
 * *permitted*); recognition is user scope (`known_instruction_sets`, what the user has *read*).
 * A recognised hash still needs a project adoption row before any text reaches a prompt.
 */
class InstructionAdoptionStore(
    private val projectDriver: SqlDriver,
    private val userDriver: SqlDriver? = null,
) {
    /** `adoptedBy` is `user` or `authored_in_aidos`. [manifestJson] is the ordered (filename, blob_hash) list. */
    fun adopt(
        projectId: String,
        setHash: String,
        manifestJson: String,
        nowIso: String,
        adoptedBy: String = "user",
        projectName: String? = null,
    ) {
        require(adoptedBy == "user" || adoptedBy == "authored_in_aidos") { "adopted_by must be user or authored_in_aidos" }
        projectDriver.execute(
            null,
            "INSERT OR REPLACE INTO instruction_adoptions (project_id, set_hash, adopted_at, adopted_by, source_manifest) VALUES (?, ?, ?, ?, ?)",
            5,
        ) {
            bindString(0, projectId); bindString(1, setHash); bindString(2, nowIso)
            bindString(3, adoptedBy); bindString(4, manifestJson)
        }
        userDriver?.let { recordKnown(it, setHash, nowIso, projectName) }
    }

    fun isAdopted(projectId: String, setHash: String): Boolean =
        projectDriver.executeQuery(
            null, "SELECT 1 FROM instruction_adoptions WHERE project_id = ? AND set_hash = ?",
            { c -> QueryResult.Value(c.next().value) }, 2,
        ) { bindString(0, projectId); bindString(1, setHash) }.value

    /** "Have I read this text before?" Used to skip the review prompt for a set the user already accepted elsewhere. */
    fun isKnown(setHash: String): Boolean =
        userDriver?.executeQuery(
            null, "SELECT 1 FROM known_instruction_sets WHERE set_hash = ?",
            { c -> QueryResult.Value(c.next().value) }, 1,
        ) { bindString(0, setHash) }?.value ?: false

    fun revoke(projectId: String, setHash: String) {
        projectDriver.execute(null, "DELETE FROM instruction_adoptions WHERE project_id = ? AND set_hash = ?", 2) {
            bindString(0, projectId); bindString(1, setHash)
        }
    }

    private fun recordKnown(driver: SqlDriver, setHash: String, nowIso: String, projectName: String?) {
        driver.execute(
            null,
            "INSERT INTO known_instruction_sets (set_hash, first_seen_at, last_accepted_at, accepted_count, first_accepted_in) " +
                "VALUES (?, ?, ?, 1, ?) ON CONFLICT(set_hash) DO UPDATE SET last_accepted_at = excluded.last_accepted_at, " +
                "accepted_count = accepted_count + 1",
            4,
        ) { bindString(0, setHash); bindString(1, nowIso); bindString(2, nowIso); bindString(3, projectName) }
    }
}
