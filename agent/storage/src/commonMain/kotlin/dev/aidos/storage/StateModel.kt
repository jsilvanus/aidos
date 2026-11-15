package dev.aidos.storage

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.SqlPreparedStatement
import dev.aidos.kernel.ContentNodeState
import dev.aidos.kernel.SessionState

/**
 * Canonical Project lifecycle states (RFC-0017 "Project Lifecycle").
 *
 * `CREATING -> OPEN -> CLOSING -> CLOSED -> (DELETED)` (and `CLOSED -> OPEN` on reopen).
 */
enum class ProjectState {
    CREATING,
    OPEN,
    CLOSING,
    CLOSED,
    DELETED,
    ;

    fun canTransitionTo(target: ProjectState): Boolean = when (this) {
        CREATING -> target == OPEN || target == CLOSED || target == DELETED
        OPEN -> target == CLOSING || target == CLOSED
        CLOSING -> target == CLOSED || target == OPEN
        CLOSED -> target == OPEN || target == DELETED
        DELETED -> false
    }
}

/** Validated lifecycle transitions for [SessionState] and [ContentNodeState] (RFC-0017). */
object LifecycleTransitions {
    fun isValidSessionTransition(from: SessionState, to: SessionState): Boolean = when (from) {
        SessionState.CREATED -> to == SessionState.SLEEPING || to == SessionState.RUNNING || to == SessionState.ARCHIVED
        SessionState.SLEEPING -> to == SessionState.RUNNING || to == SessionState.ARCHIVED
        SessionState.RUNNING -> to == SessionState.SLEEPING || to == SessionState.ARCHIVED
        SessionState.ARCHIVED -> false
    }

    fun isValidContentNodeTransition(from: ContentNodeState, to: ContentNodeState): Boolean = when (from) {
        ContentNodeState.CREATING -> to == ContentNodeState.ACTIVE || to == ContentNodeState.DELETED || to == ContentNodeState.DANGLING
        ContentNodeState.ACTIVE -> to == ContentNodeState.SUPERSEDED || to == ContentNodeState.ARCHIVED ||
            to == ContentNodeState.DELETED || to == ContentNodeState.DANGLING
        ContentNodeState.SUPERSEDED -> to == ContentNodeState.ARCHIVED || to == ContentNodeState.DELETED || to == ContentNodeState.DANGLING
        ContentNodeState.ARCHIVED -> to == ContentNodeState.DELETED || to == ContentNodeState.DANGLING
        ContentNodeState.DELETED -> false
        ContentNodeState.DANGLING -> to == ContentNodeState.ACTIVE || to == ContentNodeState.ARCHIVED || to == ContentNodeState.DELETED
    }
}

/** A row in `pending_operations` (RFC-0017). */
data class PendingOperation(
    val id: String,
    val objectType: String,
    val objectId: String,
    val operation: String,
    val startedAt: String,
    val details: String?,
    val retryCount: Int,
)

/**
 * Tracks multi-store in-flight operations (`git_commit`, `filesystem_write`, `schema_init`)
 * in `pending_operations` so project-open crash recovery can detect and resolve them (RFC-0017).
 */
class PendingOperationsStore(private val driver: SqlDriver) {

    fun record(
        id: String,
        objectType: String,
        objectId: String,
        operation: String,
        startedAt: String,
        details: String? = null,
    ) {
        driver.execute(
            identifier = null,
            sql = "INSERT OR REPLACE INTO pending_operations " +
                "(id, object_type, object_id, operation, started_at, details, retry_count) " +
                "VALUES (?, ?, ?, ?, ?, ?, 0)",
            parameters = 6,
        ) {
            bindString(0, id)
            bindString(1, objectType)
            bindString(2, objectId)
            bindString(3, operation)
            bindString(4, startedAt)
            bindString(5, details)
        }
    }

    fun listAll(): List<PendingOperation> {
        val rows = mutableListOf<PendingOperation>()
        driver.executeQuery(
            identifier = null,
            sql = "SELECT id, object_type, object_id, operation, started_at, details, retry_count " +
                "FROM pending_operations ORDER BY started_at",
            mapper = { c ->
                while (c.next().value) {
                    rows.add(
                        PendingOperation(
                            id = c.getString(0)!!,
                            objectType = c.getString(1)!!,
                            objectId = c.getString(2)!!,
                            operation = c.getString(3)!!,
                            startedAt = c.getString(4)!!,
                            details = c.getString(5),
                            retryCount = c.getLong(6)?.toInt() ?: 0,
                        )
                    )
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return rows
    }

    fun forObject(objectType: String, objectId: String): List<PendingOperation> =
        listAll().filter { it.objectType == objectType && it.objectId == objectId }

    fun incrementRetry(id: String): Int {
        driver.execute(
            identifier = null,
            sql = "UPDATE pending_operations SET retry_count = retry_count + 1 WHERE id = ?",
            parameters = 1,
        ) { bindString(0, id) }
        return driver.executeQuery(
            identifier = null,
            sql = "SELECT retry_count FROM pending_operations WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0)?.toInt() ?: 0 else 0) },
            parameters = 1,
        ) { bindString(0, id) }.value
    }

    fun complete(id: String) {
        driver.execute(
            identifier = null,
            sql = "DELETE FROM pending_operations WHERE id = ?",
            parameters = 1,
        ) { bindString(0, id) }
    }
}

/** Thrown when an optimistic-concurrency (`row_version`) CAS update loses to another writer. */
class OptimisticConcurrencyException(message: String) : IllegalStateException(message)

/**
 * Optimistic concurrency (`row_version`) compare-and-swap protocol (RFC-0017):
 * `UPDATE <table> SET <setClause>, row_version = row_version + 1 WHERE id = ? AND row_version = ?`.
 * Zero affected rows means another writer won; callers re-read and retry.
 */
object RowVersionCas {
    fun readRowVersion(driver: SqlDriver, table: String, id: String): Long? =
        driver.executeQuery(
            identifier = null,
            sql = "SELECT row_version FROM $table WHERE id = ?",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getLong(0) else null) },
            parameters = 1,
        ) { bindString(0, id) }.value

    fun updateWithCas(
        driver: SqlDriver,
        table: String,
        id: String,
        expectedRowVersion: Long,
        setClause: String,
        setParameterCount: Int,
        bindSetParams: SqlPreparedStatement.() -> Unit,
    ): Boolean {
        val affected = driver.execute(
            identifier = null,
            sql = "UPDATE $table SET $setClause, row_version = row_version + 1 WHERE id = ? AND row_version = ?",
            parameters = setParameterCount + 2,
        ) {
            bindSetParams()
            bindString(setParameterCount, id)
            bindLong(setParameterCount + 1, expectedRowVersion)
        }.value
        return affected == 1L
    }

    inline fun withRetry(maxAttempts: Int = 3, attempt: () -> Boolean) {
        for (i in 1..maxAttempts) {
            if (attempt()) return
        }
        throw OptimisticConcurrencyException("row_version CAS conflict after $maxAttempts attempts")
    }
}

enum class PendingOperationOutcome {
    /** The underlying effect is complete; advance object state and clear pending_operations. */
    COMPLETE,
    /** Still incomplete, but recoverable on retry. */
    RETRY,
    /** Unrecoverable; transition object to failed/dangling and audit. */
    UNRECOVERABLE,
}

fun interface PendingOperationVerifier {
    fun verify(op: PendingOperation): PendingOperationOutcome
}

data class StateRecoveryReport(
    val pendingExamined: Int,
    val resolved: Int,
    val retried: Int,
    val unrecoverable: Int,
    val creatingObjectsAdvanced: Int,
)

/**
 * Open-time crash recovery pass for the state model (RFC-0017 "Crash Recovery", Steps 1-3).
 *
 * Idempotent: running twice produces the same rows.
 */
object StateModelRecovery {

    fun recoverOnOpen(
        driver: SqlDriver,
        nowIso: () -> String,
        idGen: () -> String,
        maxRetries: Int = 3,
        verifier: PendingOperationVerifier = defaultVerifier(driver),
    ): StateRecoveryReport {
        val transacter = object : TransacterImpl(driver) {}
        val pendingStore = PendingOperationsStore(driver)
        val pending = pendingStore.listAll()
        if (pending.isEmpty()) return StateRecoveryReport(0, 0, 0, 0, 0)

        var resolved = 0
        var retried = 0
        var unrecoverable = 0
        var creatingAdvanced = 0

        transacter.transaction {
            for (op in pending) {
                when (verifier.verify(op)) {
                    PendingOperationOutcome.COMPLETE -> {
                        advanceObjectIfCreating(driver, op.objectType, op.objectId, nowIso())
                        pendingStore.complete(op.id)
                        resolved++
                    }
                    PendingOperationOutcome.RETRY -> {
                        if (op.retryCount >= maxRetries) {
                            markObjectUnrecoverable(driver, op.objectType, op.objectId, nowIso())
                            pendingStore.complete(op.id)
                            writeRecoveryAudit(driver, idGen(), nowIso(), "StateRecoveryFailed", op.objectId,
                                """{"object_type":"${op.objectType}","operation":"${op.operation}","reason":"max_retries_exceeded"}""")
                            unrecoverable++
                        } else {
                            pendingStore.incrementRetry(op.id)
                            retried++
                        }
                    }
                    PendingOperationOutcome.UNRECOVERABLE -> {
                        markObjectUnrecoverable(driver, op.objectType, op.objectId, nowIso())
                        pendingStore.complete(op.id)
                        writeRecoveryAudit(driver, idGen(), nowIso(), "StateRecoveryFailed", op.objectId,
                            """{"object_type":"${op.objectType}","operation":"${op.operation}","reason":"unrecoverable"}""")
                        unrecoverable++
                    }
                }
            }

            // Also check for any CREATING content_nodes without a pending_operations row.
            val creatingNodes = mutableListOf<Pair<String, Long>>()
            driver.executeQuery(
                identifier = null,
                sql = "SELECT id, row_version FROM content_nodes WHERE state = 'CREATING'",
                mapper = { c ->
                    while (c.next().value) {
                        creatingNodes.add(c.getString(0)!! to (c.getLong(1) ?: 1L))
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 0,
            )
            for ((nodeId, rowVer) in creatingNodes) {
                if (pending.none { it.objectId == nodeId }) {
                    val now = nowIso()
                    val updated = RowVersionCas.updateWithCas(
                        driver = driver,
                        table = "content_nodes",
                        id = nodeId,
                        expectedRowVersion = rowVer,
                        setClause = "state = 'DANGLING', updated_at = ?",
                        setParameterCount = 1,
                    ) { bindString(0, now) }
                    if (updated) creatingAdvanced++
                }
            }

            if (resolved > 0 || unrecoverable > 0 || creatingAdvanced > 0) {
                writeRecoveryAudit(
                    driver = driver,
                    auditId = idGen(),
                    now = nowIso(),
                    kind = "StateRecoveryExecuted",
                    subjectRef = null,
                    detailJson = """{"examined":${pending.size},"resolved":$resolved,"retried":$retried,"unrecoverable":$unrecoverable,"creating_advanced":$creatingAdvanced}""",
                )
            }
        }

        return StateRecoveryReport(
            pendingExamined = pending.size,
            resolved = resolved,
            retried = retried,
            unrecoverable = unrecoverable,
            creatingObjectsAdvanced = creatingAdvanced,
        )
    }

    private fun defaultVerifier(driver: SqlDriver): PendingOperationVerifier = PendingOperationVerifier { op ->
        when (op.objectType) {
            "artifact", "resource" -> {
                val state = driver.executeQuery(
                    identifier = null,
                    sql = "SELECT state FROM content_nodes WHERE id = ?",
                    mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
                    parameters = 1,
                ) { bindString(0, op.objectId) }.value
                when (state) {
                    null -> PendingOperationOutcome.UNRECOVERABLE
                    "ACTIVE" -> PendingOperationOutcome.COMPLETE
                    else -> PendingOperationOutcome.RETRY
                }
            }
            "intent_snapshot" -> {
                val exists = driver.executeQuery(
                    identifier = null,
                    sql = "SELECT 1 FROM intent_nodes WHERE id = ?",
                    mapper = { c -> QueryResult.Value(c.next().value) },
                    parameters = 1,
                ) { bindString(0, op.objectId) }.value
                if (exists) PendingOperationOutcome.COMPLETE else PendingOperationOutcome.UNRECOVERABLE
            }
            else -> PendingOperationOutcome.COMPLETE
        }
    }

    private fun advanceObjectIfCreating(driver: SqlDriver, objectType: String, objectId: String, now: String) {
        when (objectType) {
            "artifact", "resource" -> {
                val rowVer = RowVersionCas.readRowVersion(driver, "content_nodes", objectId) ?: return
                RowVersionCas.updateWithCas(
                    driver = driver,
                    table = "content_nodes",
                    id = objectId,
                    expectedRowVersion = rowVer,
                    setClause = "state = 'ACTIVE', updated_at = ?",
                    setParameterCount = 1,
                ) { bindString(0, now) }
            }
            "project" -> {
                val rowVer = RowVersionCas.readRowVersion(driver, "projects", objectId) ?: return
                RowVersionCas.updateWithCas(
                    driver = driver,
                    table = "projects",
                    id = objectId,
                    expectedRowVersion = rowVer,
                    setClause = "state = 'OPEN', updated_at = ?, state_updated_at = ?",
                    setParameterCount = 2,
                ) { bindString(0, now); bindString(1, now) }
            }
        }
    }

    private fun markObjectUnrecoverable(driver: SqlDriver, objectType: String, objectId: String, now: String) {
        if (objectType == "artifact" || objectType == "resource") {
            val rowVer = RowVersionCas.readRowVersion(driver, "content_nodes", objectId) ?: return
            RowVersionCas.updateWithCas(
                driver = driver,
                table = "content_nodes",
                id = objectId,
                expectedRowVersion = rowVer,
                setClause = "state = 'DANGLING', updated_at = ?",
                setParameterCount = 1,
            ) { bindString(0, now) }
        }
    }

    private fun writeRecoveryAudit(
        driver: SqlDriver,
        auditId: String,
        now: String,
        kind: String,
        subjectRef: String?,
        detailJson: String,
    ) {
        val projectId = driver.executeQuery(
            identifier = null,
            sql = "SELECT id FROM projects LIMIT 1",
            mapper = { c -> QueryResult.Value(if (c.next().value) c.getString(0) else null) },
            parameters = 0,
        ).value ?: return

        driver.execute(
            identifier = null,
            sql = "INSERT INTO audit_log " +
                "(id, project_id, sequence, occurred_at, kind, actor_kind, actor_id, device_id, subject_ref, detail_json) " +
                "VALUES (?, ?, (SELECT COALESCE(MAX(sequence), 0) + 1 FROM audit_log WHERE project_id = ?), " +
                "?, ?, 'RUNTIME', 'state-recovery', 'local', ?, ?)",
            parameters = 7,
        ) {
            bindString(0, auditId)
            bindString(1, projectId)
            bindString(2, projectId)
            bindString(3, now)
            bindString(4, kind)
            bindString(5, subjectRef)
            bindString(6, detailJson)
        }
    }
}
