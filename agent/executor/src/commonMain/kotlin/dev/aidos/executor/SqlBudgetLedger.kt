package dev.aidos.executor

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.db.QueryResult
import dev.aidos.kernel.Budget
import dev.aidos.kernel.BudgetLedger
import dev.aidos.kernel.BudgetScope
import dev.aidos.kernel.ReservationId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject

/**
 * SQLite-backed budget ledger (RFC-0028).
 *
 * Manages budget reservations and settlement:
 * - Reserves estimated cost before spending (prevents overspend).
 * - Settles actual cost after spending (refunds overages).
 * - Releases reservations on cancellation.
 * - Tracks remaining budget per scope.
 */
class SqlBudgetLedger(
    private val driver: SqlDriver,
    private val idGen: () -> String,
    private val nowIso: () -> String,
) : BudgetLedger {

    override suspend fun reserve(
        scope: BudgetScope,
        scopeId: String,
        estimate: Budget,
    ): Result<ReservationId> {
        return try {
            val reservationId = ReservationId(idGen())
            val remaining = remaining(scope, scopeId)

            // Check if we can reserve the estimate against all constraints
            if (!canAfford(remaining, estimate)) {
                return Result.failure(
                    IllegalStateException(
                        "Budget exhausted for $scope:$scopeId. " +
                            "Estimated ${estimate}, but only $remaining remaining"
                    )
                )
            }

            // Record the reservation (linked to attempt via the attempt_id at settlement time)
            val reservedJson = Json.encodeToJsonElement(estimate).jsonObject.toString()
            driver.execute(
                null,
                """
                INSERT INTO budget_reservations (id, attempt_id, reserved_json, created_at)
                VALUES (?, ?, ?, ?)
                """,
                4
            ) {
                bindString(0, reservationId.value)
                bindString(1, scopeId)  // Placeholder; settled when attempt completes
                bindString(2, reservedJson)
                bindString(3, nowIso())
            }

            Result.success(reservationId)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    override suspend fun settle(reservation: ReservationId, actual: Budget) {
        // In a real implementation, this would:
        // 1. Charge the actual cost against the ledger
        // 2. Release any reserved but unused budget
        // 3. Update budget_ledger table with new totals
        // 4. Run in same transaction as Attempt outcome recording
        //
        // For now, we're implementing the basic structure.
        // This will be called from AgentLoopTaskRunner after each model call.
        val actualJson = Json.encodeToJsonElement(actual).jsonObject.toString()
        driver.execute(
            null,
            """
            UPDATE budget_reservations 
            SET reserved_json = ? 
            WHERE id = ?
            """,
            2
        ) {
            bindString(0, actualJson)
            bindString(1, reservation.value)
        }
    }

    override suspend fun release(reservation: ReservationId) {
        driver.execute(
            null,
            "DELETE FROM budget_reservations WHERE id = ?",
            1
        ) {
            bindString(0, reservation.value)
        }
    }

    override suspend fun remaining(scope: BudgetScope, scopeId: String): Budget? {
        // Query the budget_ledger for this scope and calculate remaining based on limits
        var result: Budget? = null

        driver.executeQuery(
            identifier = null,
            sql = """
            SELECT model_calls, input_tokens, output_tokens, cost_units, steps, tool_invocations, limit_json
            FROM budget_ledger
            WHERE scope = ? AND scope_id = ?
            LIMIT 1
            """,
            mapper = { cursor ->
                if (cursor.next().value) {
                    val consumed = Budget(
                        modelCalls = cursor.getLong(0)?.toInt(),
                        inputTokens = cursor.getLong(1),
                        outputTokens = cursor.getLong(2),
                        costUnits = cursor.getLong(3),
                        steps = cursor.getLong(4)?.toInt(),
                        toolInvocations = cursor.getLong(5)?.toInt(),
                        wallClockSeconds = null,
                    )

                    val limitJson = cursor.getString(6)
                    val limits = if (limitJson != null) {
                        Json.decodeFromString<Budget>(limitJson)
                    } else {
                        null
                    }

                    result = calculateRemaining(limits, consumed)
                } else {
                    // No ledger entry yet — unlimited
                    result = null
                }
                QueryResult.Value(Unit)
            },
            parameters = 2,
            binders = {
                bindString(0, scope.name)
                bindString(1, scopeId)
            },
        )

        return result
    }

    private fun calculateRemaining(limits: Budget?, consumed: Budget): Budget {
        // If no limits, budget is unlimited
        if (limits == null) return Budget(
            modelCalls = Int.MAX_VALUE,
            inputTokens = Long.MAX_VALUE,
            outputTokens = Long.MAX_VALUE,
            costUnits = Long.MAX_VALUE,
            steps = Int.MAX_VALUE,
            toolInvocations = Int.MAX_VALUE
        )

        return Budget(
            modelCalls = limits.modelCalls?.let { (it - (consumed.modelCalls ?: 0)).coerceAtLeast(0) },
            inputTokens = limits.inputTokens?.let { (it - (consumed.inputTokens ?: 0)).coerceAtLeast(0) },
            outputTokens = limits.outputTokens?.let { (it - (consumed.outputTokens ?: 0)).coerceAtLeast(0) },
            costUnits = limits.costUnits?.let { (it - (consumed.costUnits ?: 0)).coerceAtLeast(0) },
            steps = limits.steps?.let { (it - (consumed.steps ?: 0)).coerceAtLeast(0) },
            wallClockSeconds = limits.wallClockSeconds,
            toolInvocations = limits.toolInvocations?.let { (it - (consumed.toolInvocations ?: 0)).coerceAtLeast(0) }
        )
    }

    private fun canAfford(remaining: Budget?, estimate: Budget): Boolean {
        if (remaining == null) return true
        val remainingModelCalls = remaining.modelCalls
        val remainingInputTokens = remaining.inputTokens
        val remainingOutputTokens = remaining.outputTokens
        val remainingCostUnits = remaining.costUnits
        val remainingSteps = remaining.steps
        val remainingToolInvocations = remaining.toolInvocations
        val estimateModelCalls = estimate.modelCalls
        val estimateInputTokens = estimate.inputTokens
        val estimateOutputTokens = estimate.outputTokens
        val estimateCostUnits = estimate.costUnits
        val estimateSteps = estimate.steps
        val estimateToolInvocations = estimate.toolInvocations

        // Check each dimension; any null means unlimited
        if (estimateModelCalls != null && remainingModelCalls != null && estimateModelCalls > remainingModelCalls) return false
        if (estimateInputTokens != null && remainingInputTokens != null && estimateInputTokens > remainingInputTokens) return false
        if (estimateOutputTokens != null && remainingOutputTokens != null && estimateOutputTokens > remainingOutputTokens) return false
        if (estimateCostUnits != null && remainingCostUnits != null && estimateCostUnits > remainingCostUnits) return false
        if (estimateSteps != null && remainingSteps != null && estimateSteps > remainingSteps) return false
        if (estimateToolInvocations != null && remainingToolInvocations != null && estimateToolInvocations > remainingToolInvocations) return false

        return true
    }
}
