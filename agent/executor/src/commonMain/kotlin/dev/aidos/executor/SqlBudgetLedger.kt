package dev.aidos.executor

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import dev.aidos.kernel.Budget
import dev.aidos.kernel.BudgetLedger
import dev.aidos.kernel.BudgetScope
import dev.aidos.kernel.ReservationId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * SQLite-backed budget ledger (RFC-0028).
 *
 * Manages budget reservations and settlement:
 * - Reserves estimated cost before spending (prevents overspend, deducting active reservations).
 * - Settles actual cost into `budget_ledger` and releases unspent reserved remainder.
 * - Releases reservations on cancellation.
 * - Tracks remaining budget per scope, supporting both snake_case and camelCase JSON.
 */
class SqlBudgetLedger(
    private val driver: SqlDriver,
    private val idGen: () -> String,
    private val nowIso: () -> String,
) : BudgetLedger {

    private val transacter = object : TransacterImpl(driver) {}
    private val json = Json { ignoreUnknownKeys = true }

    private fun <T> inTransaction(block: () -> T): T =
        transacter.transactionWithResult { block() }

    override suspend fun reserve(
        scope: BudgetScope,
        scopeId: String,
        estimate: Budget,
    ): Result<ReservationId> = reserveInternal(scope, scopeId, estimate, explicitAttemptId = null)

    /**
     * Reserves [estimate] for [scope]:[scopeId] bound to an explicit [attemptId] referencing
     * `attempts(id)` in `project.sql`.
     */
    suspend fun reserve(
        scope: BudgetScope,
        scopeId: String,
        estimate: Budget,
        attemptId: String,
    ): Result<ReservationId> = reserveInternal(scope, scopeId, estimate, explicitAttemptId = attemptId)

    private fun reserveInternal(
        scope: BudgetScope,
        scopeId: String,
        estimate: Budget,
        explicitAttemptId: String?,
    ): Result<ReservationId> {
        return try {
            inTransaction {
                val resolvedAttemptId = resolveAttemptId(scope, scopeId, explicitAttemptId)
                    ?: return@inTransaction Result.failure(
                        IllegalStateException(
                            "No valid attempt row found for $scope:$scopeId" +
                                (explicitAttemptId?.let { " (attemptId=$it)" } ?: "")
                        )
                    )

                val remaining = computeRemainingInTx(scope, scopeId)
                if (!canAfford(remaining, estimate)) {
                    return@inTransaction Result.failure(
                        IllegalStateException(
                            "Budget exhausted for $scope:$scopeId. " +
                                "Estimated $estimate, but only $remaining remaining"
                        )
                    )
                }

                val reservationId = ReservationId(idGen())
                val envelope = buildJsonObject {
                    put("status", STATUS_RESERVED)
                    put("scope", scope.name)
                    put("scope_id", scopeId)
                    put("reserved", encodeBudgetJson(estimate))
                }.toString()

                driver.execute(
                    identifier = null,
                    sql = """
                    INSERT INTO budget_reservations (id, attempt_id, reserved_json, created_at)
                    VALUES (?, ?, ?, ?)
                    """.trimIndent(),
                    parameters = 4,
                ) {
                    bindString(0, reservationId.value)
                    bindString(1, resolvedAttemptId)
                    bindString(2, envelope)
                    bindString(3, nowIso())
                }

                Result.success(reservationId)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun settle(reservation: ReservationId, actual: Budget) {
        inTransaction {
            var currentPayload: String? = null
            var attemptId: String? = null
            driver.executeQuery(
                identifier = null,
                sql = "SELECT attempt_id, reserved_json FROM budget_reservations WHERE id = ? LIMIT 1",
                mapper = { cursor ->
                    if (cursor.next().value) {
                        attemptId = cursor.getString(0)
                        currentPayload = cursor.getString(1)
                    }
                    QueryResult.Value(Unit)
                },
                parameters = 1,
                binders = { bindString(0, reservation.value) },
            )

            val rawJson = currentPayload ?: return@inTransaction
            val envelope = parseEnvelope(rawJson)
            if (envelope.status != STATUS_RESERVED) {
                // Idempotent: already settled.
                return@inTransaction
            }

            val scopeName = envelope.scope ?: attemptId?.let { inferRunIdForAttempt(it)?.let { BudgetScope.RUN.name } }
            val scopeId = envelope.scopeId ?: attemptId?.let { inferRunIdForAttempt(it) }
            val now = nowIso()

            if (scopeName != null && scopeId != null) {
                var existingLedgerId: String? = null
                driver.executeQuery(
                    identifier = null,
                    sql = """
                    SELECT id FROM budget_ledger
                    WHERE UPPER(scope) = ? AND scope_id = ?
                    ORDER BY period_start DESC
                    LIMIT 1
                    """.trimIndent(),
                    mapper = { cursor ->
                        if (cursor.next().value) {
                            existingLedgerId = cursor.getString(0)
                        }
                        QueryResult.Value(Unit)
                    },
                    parameters = 2,
                    binders = {
                        bindString(0, scopeName.uppercase())
                        bindString(1, scopeId)
                    },
                )

                val addModelCalls = (actual.modelCalls ?: 0).coerceAtLeast(0).toLong()
                val addInputTokens = (actual.inputTokens ?: 0L).coerceAtLeast(0L)
                val addOutputTokens = (actual.outputTokens ?: 0L).coerceAtLeast(0L)
                val addCostUnits = (actual.costUnits ?: 0L).coerceAtLeast(0L)
                val addSteps = (actual.steps ?: 0).coerceAtLeast(0).toLong()
                val addToolInvocations = (actual.toolInvocations ?: 0).coerceAtLeast(0).toLong()

                val ledgerId = existingLedgerId
                if (ledgerId != null) {
                    driver.execute(
                        identifier = null,
                        sql = """
                        UPDATE budget_ledger
                        SET model_calls = model_calls + ?,
                            input_tokens = input_tokens + ?,
                            output_tokens = output_tokens + ?,
                            cost_units = cost_units + ?,
                            steps = steps + ?,
                            tool_invocations = tool_invocations + ?,
                            updated_at = ?
                        WHERE id = ?
                        """.trimIndent(),
                        parameters = 8,
                    ) {
                        bindLong(0, addModelCalls)
                        bindLong(1, addInputTokens)
                        bindLong(2, addOutputTokens)
                        bindLong(3, addCostUnits)
                        bindLong(4, addSteps)
                        bindLong(5, addToolInvocations)
                        bindString(6, now)
                        bindString(7, ledgerId)
                    }
                } else {
                    driver.execute(
                        identifier = null,
                        sql = """
                        INSERT INTO budget_ledger (
                            id, scope, scope_id, period_start,
                            model_calls, input_tokens, output_tokens,
                            cost_units, steps, tool_invocations,
                            limit_json, updated_at
                        ) VALUES (?, ?, ?, NULL, ?, ?, ?, ?, ?, ?, NULL, ?)
                        """.trimIndent(),
                        parameters = 10,
                    ) {
                        bindString(0, idGen())
                        bindString(1, scopeName)
                        bindString(2, scopeId)
                        bindLong(3, addModelCalls)
                        bindLong(4, addInputTokens)
                        bindLong(5, addOutputTokens)
                        bindLong(6, addCostUnits)
                        bindLong(7, addSteps)
                        bindLong(8, addToolInvocations)
                        bindString(9, now)
                    }
                }
            }

            val settledJson = buildJsonObject {
                put("status", STATUS_SETTLED)
                scopeName?.let { put("scope", it) }
                scopeId?.let { put("scope_id", it) }
                put("reserved", encodeBudgetJson(envelope.reserved))
                put("actual", encodeBudgetJson(actual))
                put("settled_at", now)
            }.toString()

            driver.execute(
                identifier = null,
                sql = "UPDATE budget_reservations SET reserved_json = ? WHERE id = ?",
                parameters = 2,
            ) {
                bindString(0, settledJson)
                bindString(1, reservation.value)
            }
            Unit
        }
    }

    override suspend fun release(reservation: ReservationId) {
        inTransaction {
            driver.execute(
                identifier = null,
                sql = "DELETE FROM budget_reservations WHERE id = ?",
                parameters = 1,
            ) {
                bindString(0, reservation.value)
            }
            Unit
        }
    }

    override suspend fun remaining(scope: BudgetScope, scopeId: String): Budget? {
        return inTransaction {
            computeRemainingInTx(scope, scopeId)
        }
    }

    private fun computeRemainingInTx(scope: BudgetScope, scopeId: String): Budget? {
        var foundLedgerRow = false
        var consumed = Budget.ZERO
        var limits: Budget? = null

        driver.executeQuery(
            identifier = null,
            sql = """
            SELECT model_calls, input_tokens, output_tokens, cost_units, steps, tool_invocations, limit_json
            FROM budget_ledger
            WHERE UPPER(scope) = ? AND scope_id = ?
            ORDER BY period_start DESC
            LIMIT 1
            """.trimIndent(),
            mapper = { cursor ->
                if (cursor.next().value) {
                    foundLedgerRow = true
                    consumed = Budget(
                        modelCalls = cursor.getLong(0)?.toInt(),
                        inputTokens = cursor.getLong(1),
                        outputTokens = cursor.getLong(2),
                        costUnits = cursor.getLong(3),
                        steps = cursor.getLong(4)?.toInt(),
                        toolInvocations = cursor.getLong(5)?.toInt(),
                        wallClockSeconds = null,
                    )
                    val limitJson = cursor.getString(6)
                    limits = if (limitJson != null) decodeBudget(limitJson) else null
                }
                QueryResult.Value(Unit)
            },
            parameters = 2,
            binders = {
                bindString(0, scope.name)
                bindString(1, scopeId)
            },
        )

        val resolvedLimits = limits
        if (!foundLedgerRow || resolvedLimits == null) {
            return null
        }

        val activeReserved = sumActiveReservations(scope, scopeId)
        return calculateRemaining(resolvedLimits, consumed, activeReserved)
    }

    private fun sumActiveReservations(scope: BudgetScope, scopeId: String): Budget {
        var modelCalls = 0L
        var inputTokens = 0L
        var outputTokens = 0L
        var costUnits = 0L
        var steps = 0L
        var toolInvocations = 0L

        driver.executeQuery(
            identifier = null,
            sql = "SELECT reserved_json FROM budget_reservations",
            mapper = { cursor ->
                while (cursor.next().value) {
                    val raw = cursor.getString(0) ?: continue
                    val env = parseEnvelope(raw)
                    if (env.status != STATUS_RESERVED) continue
                    if (!env.scope.equals(scope.name, ignoreCase = true) || env.scopeId != scopeId) continue
                    modelCalls += (env.reserved.modelCalls ?: 0).coerceAtLeast(0).toLong()
                    inputTokens += (env.reserved.inputTokens ?: 0L).coerceAtLeast(0L)
                    outputTokens += (env.reserved.outputTokens ?: 0L).coerceAtLeast(0L)
                    costUnits += (env.reserved.costUnits ?: 0L).coerceAtLeast(0L)
                    steps += (env.reserved.steps ?: 0).coerceAtLeast(0).toLong()
                    toolInvocations += (env.reserved.toolInvocations ?: 0).coerceAtLeast(0).toLong()
                }
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )

        return Budget(
            modelCalls = modelCalls.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            inputTokens = inputTokens,
            outputTokens = outputTokens,
            costUnits = costUnits,
            steps = steps.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            toolInvocations = toolInvocations.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),
            wallClockSeconds = null,
        )
    }

    private fun resolveAttemptId(
        scope: BudgetScope,
        scopeId: String,
        explicitAttemptId: String?,
    ): String? {
        if (explicitAttemptId != null) {
            return querySingleString(
                "SELECT id FROM attempts WHERE id = ? LIMIT 1",
                explicitAttemptId,
            )
        }

        // Direct attempt ID match (e.g. legacy caller where attempt id == scopeId).
        querySingleString("SELECT id FROM attempts WHERE id = ? LIMIT 1", scopeId)?.let { return it }

        val sql = when (scope) {
            BudgetScope.RUN -> """
                SELECT a.id FROM attempts a
                JOIN tasks t ON a.task_id = t.id
                WHERE t.run_id = ?
                ORDER BY CASE WHEN a.state = 'RUNNING' THEN 0 ELSE 1 END, a.started_at DESC, a.attempt_number DESC
                LIMIT 1
            """.trimIndent()
            BudgetScope.SESSION -> """
                SELECT a.id FROM attempts a
                JOIN tasks t ON a.task_id = t.id
                WHERE t.session_id = ?
                ORDER BY CASE WHEN a.state = 'RUNNING' THEN 0 ELSE 1 END, a.started_at DESC, a.attempt_number DESC
                LIMIT 1
            """.trimIndent()
            BudgetScope.PROJECT -> """
                SELECT a.id FROM attempts a
                JOIN tasks t ON a.task_id = t.id
                WHERE t.project_id = ?
                ORDER BY CASE WHEN a.state = 'RUNNING' THEN 0 ELSE 1 END, a.started_at DESC, a.attempt_number DESC
                LIMIT 1
            """.trimIndent()
            BudgetScope.CAPABILITY -> """
                SELECT a.id FROM attempts a
                WHERE a.capability_id = ?
                ORDER BY CASE WHEN a.state = 'RUNNING' THEN 0 ELSE 1 END, a.started_at DESC, a.attempt_number DESC
                LIMIT 1
            """.trimIndent()
            BudgetScope.USER -> {
                return querySingleStringNoBind(
                    """
                    SELECT id FROM attempts
                    ORDER BY CASE WHEN state = 'RUNNING' THEN 0 ELSE 1 END, started_at DESC, attempt_number DESC
                    LIMIT 1
                    """.trimIndent()
                )
            }
        }
        return querySingleString(sql, scopeId)
    }

    private fun inferRunIdForAttempt(attemptId: String): String? = querySingleString(
        "SELECT t.run_id FROM attempts a JOIN tasks t ON a.task_id = t.id WHERE a.id = ? LIMIT 1",
        attemptId,
    )

    private fun querySingleString(sql: String, param: String): String? {
        var found: String? = null
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) found = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 1,
            binders = { bindString(0, param) },
        )
        return found
    }

    private fun querySingleStringNoBind(sql: String): String? {
        var found: String? = null
        driver.executeQuery(
            identifier = null,
            sql = sql,
            mapper = { cursor ->
                if (cursor.next().value) found = cursor.getString(0)
                QueryResult.Value(Unit)
            },
            parameters = 0,
        )
        return found
    }

    private fun calculateRemaining(limits: Budget, consumed: Budget, reserved: Budget): Budget = Budget(
        modelCalls = limits.modelCalls?.let {
            (it.toLong() - (consumed.modelCalls ?: 0).toLong() - (reserved.modelCalls ?: 0).toLong())
                .coerceAtLeast(0L)
                .toInt()
        },
        inputTokens = limits.inputTokens?.let {
            (it - (consumed.inputTokens ?: 0L) - (reserved.inputTokens ?: 0L)).coerceAtLeast(0L)
        },
        outputTokens = limits.outputTokens?.let {
            (it - (consumed.outputTokens ?: 0L) - (reserved.outputTokens ?: 0L)).coerceAtLeast(0L)
        },
        costUnits = limits.costUnits?.let {
            (it - (consumed.costUnits ?: 0L) - (reserved.costUnits ?: 0L)).coerceAtLeast(0L)
        },
        steps = limits.steps?.let {
            (it.toLong() - (consumed.steps ?: 0).toLong() - (reserved.steps ?: 0).toLong())
                .coerceAtLeast(0L)
                .toInt()
        },
        wallClockSeconds = limits.wallClockSeconds,
        toolInvocations = limits.toolInvocations?.let {
            (it.toLong() - (consumed.toolInvocations ?: 0).toLong() - (reserved.toolInvocations ?: 0).toLong())
                .coerceAtLeast(0L)
                .toInt()
        },
    )

    private fun canAfford(remaining: Budget?, estimate: Budget): Boolean {
        if (remaining == null) return true
        val remModelCalls = remaining.modelCalls
        val remInputTokens = remaining.inputTokens
        val remOutputTokens = remaining.outputTokens
        val remCostUnits = remaining.costUnits
        val remSteps = remaining.steps
        val remToolInvocations = remaining.toolInvocations

        val estModelCalls = estimate.modelCalls
        val estInputTokens = estimate.inputTokens
        val estOutputTokens = estimate.outputTokens
        val estCostUnits = estimate.costUnits
        val estSteps = estimate.steps
        val estToolInvocations = estimate.toolInvocations

        if (estModelCalls != null && remModelCalls != null && estModelCalls > remModelCalls) return false
        if (estInputTokens != null && remInputTokens != null && estInputTokens > remInputTokens) return false
        if (estOutputTokens != null && remOutputTokens != null && estOutputTokens > remOutputTokens) return false
        if (estCostUnits != null && remCostUnits != null && estCostUnits > remCostUnits) return false
        if (estSteps != null && remSteps != null && estSteps > remSteps) return false
        if (estToolInvocations != null && remToolInvocations != null && estToolInvocations > remToolInvocations) return false
        return true
    }

    private data class ReservationEnvelope(
        val status: String,
        val scope: String?,
        val scopeId: String?,
        val reserved: Budget,
    )

    private fun parseEnvelope(raw: String): ReservationEnvelope {
        val obj = json.parseToJsonElement(raw).jsonObject
        val reservedElement = obj["reserved"]
        return if (reservedElement != null) {
            ReservationEnvelope(
                status = obj["status"]?.jsonPrimitive?.contentOrNull ?: STATUS_RESERVED,
                scope = obj["scope"]?.jsonPrimitive?.contentOrNull,
                scopeId = obj["scope_id"]?.jsonPrimitive?.contentOrNull
                    ?: obj["scopeId"]?.jsonPrimitive?.contentOrNull,
                reserved = decodeBudgetObject(reservedElement.jsonObject),
            )
        } else {
            ReservationEnvelope(
                status = STATUS_RESERVED,
                scope = null,
                scopeId = null,
                reserved = decodeBudgetObject(obj),
            )
        }
    }

    private fun decodeBudget(raw: String): Budget =
        decodeBudgetObject(json.parseToJsonElement(raw).jsonObject)

    private fun decodeBudgetObject(obj: JsonObject): Budget = Budget(
        modelCalls = (obj["model_calls"] ?: obj["modelCalls"])?.jsonPrimitive?.intOrNull,
        inputTokens = (obj["input_tokens"] ?: obj["inputTokens"])?.jsonPrimitive?.longOrNull,
        outputTokens = (obj["output_tokens"] ?: obj["outputTokens"])?.jsonPrimitive?.longOrNull,
        costUnits = (obj["cost_units"] ?: obj["costUnits"])?.jsonPrimitive?.longOrNull,
        steps = obj["steps"]?.jsonPrimitive?.intOrNull,
        wallClockSeconds = (obj["wall_clock_seconds"] ?: obj["wallClockSeconds"])?.jsonPrimitive?.intOrNull,
        toolInvocations = (obj["tool_invocations"] ?: obj["toolInvocations"])?.jsonPrimitive?.intOrNull,
    )

    private fun encodeBudgetJson(budget: Budget): JsonObject = buildJsonObject {
        budget.modelCalls?.let { put("model_calls", it) }
        budget.inputTokens?.let { put("input_tokens", it) }
        budget.outputTokens?.let { put("output_tokens", it) }
        budget.costUnits?.let { put("cost_units", it) }
        budget.steps?.let { put("steps", it) }
        budget.wallClockSeconds?.let { put("wall_clock_seconds", it) }
        budget.toolInvocations?.let { put("tool_invocations", it) }
    }

    private companion object {
        const val STATUS_RESERVED = "RESERVED"
        const val STATUS_SETTLED = "SETTLED"
    }
}
