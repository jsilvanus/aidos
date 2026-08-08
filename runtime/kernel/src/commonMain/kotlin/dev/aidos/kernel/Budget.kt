package dev.aidos.kernel

import kotlinx.serialization.Serializable

/**
 * Bounds on consumption (RFC-0028).
 *
 * A capability constraint, not an accounting figure: exceeding a budget is a *denial*, enforced
 * at the point of spend, not a warning observed afterwards.
 */
@Serializable
data class Budget(
    val modelCalls: Int? = null,
    val inputTokens: Long? = null,
    val outputTokens: Long? = null,

    /** Integer micro-currency. Money in a Double is a rounding complaint waiting to happen. */
    val costUnits: Long? = null,
    val steps: Int? = null,
    val wallClockSeconds: Int? = null,
    val toolInvocations: Int? = null,
) {
    /**
     * Delegation **divides** an allowance; it never multiplies it. A driver with 10,000 units
     * splitting to three workers has 10,000 units in total, not 30,000. Without this, fan-out is
     * an unbounded spend multiplier and orchestration becomes the most expensive way to work.
     */
    fun split(ways: Int): Budget {
        require(ways > 0) { "cannot split a budget $ways ways" }
        return Budget(
            modelCalls = modelCalls?.let { it / ways },
            inputTokens = inputTokens?.let { it / ways },
            outputTokens = outputTokens?.let { it / ways },
            costUnits = costUnits?.let { it / ways },
            steps = steps?.let { it / ways },
            wallClockSeconds = wallClockSeconds,
            toolInvocations = toolInvocations?.let { it / ways },
        )
    }

    companion object {
        val ZERO = Budget(0, 0, 0, 0, 0, 0, 0)

        /** Conservative at Run scope; absent above it (RFC-0028, decision D9). */
        val RUN_DEFAULT = Budget(modelCalls = 8, steps = 24)
    }
}

@Serializable
enum class BudgetScope { RUN, SESSION, PROJECT, USER, CAPABILITY }

/**
 * Reservation before the spend, settlement after — in the same transaction as the Attempt
 * outcome. A model call whose cost was incurred but whose record was lost would otherwise be
 * free, and the budget would drift permanently.
 */
interface BudgetLedger {
    suspend fun reserve(scope: BudgetScope, scopeId: String, estimate: Budget): Result<ReservationId>
    suspend fun settle(reservation: ReservationId, actual: Budget)
    suspend fun release(reservation: ReservationId)
    suspend fun remaining(scope: BudgetScope, scopeId: String): Budget?
}

@Serializable
@kotlin.jvm.JvmInline
value class ReservationId(val value: String)
