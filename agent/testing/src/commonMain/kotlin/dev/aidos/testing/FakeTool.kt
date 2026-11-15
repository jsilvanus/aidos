package dev.aidos.testing

import dev.aidos.kernel.AidosError
import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.ErrorClass
import dev.aidos.kernel.ErrorCodes
import dev.aidos.kernel.Permission
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolAvailability
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Scriptable [Tool] test double with injectable latency and failures (RFC-0038).
 */
class FakeTool(
    override val id: String = "fake",
    override val version: String = "1.0",
    private val descriptors: List<ToolDescriptor> = listOf(defaultDescriptor()),
) : Tool {

    private var nextLatencyMillis: Long = 0L
    private var nextFailure: AidosError? = null
    private var nextResultText: String = "ok"
    private var nextTrustLevel: TrustLevel = TrustLevel.TRUSTED
    val executedCalls = mutableListOf<Pair<String, JsonObject>>()
    val cancelledOperations = mutableListOf<String>()

    fun withLatency(millis: Long): FakeTool {
        nextLatencyMillis = millis
        return this
    }

    fun failNext(code: String = ErrorCodes.TOOL_EXECUTION_FAILED, message: String = "injected failure"): FakeTool {
        nextFailure = AidosError(code = code, errorClass = ErrorClass.TRANSIENT, message = message)
        return this
    }

    fun respondWith(text: String, trustLevel: TrustLevel = TrustLevel.TRUSTED): FakeTool {
        nextResultText = text
        nextTrustLevel = trustLevel
        return this
    }

    override fun operations(): List<ToolDescriptor> = descriptors

    override suspend fun execute(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): ToolCallResult {
        if (nextLatencyMillis > 0L) {
            delay(nextLatencyMillis)
        }
        executedCalls.add(operation to arguments)
        val failure = nextFailure
        if (failure != null) {
            nextFailure = null
            return ToolCallResult(
                callId = "fake-${executedCalls.size}",
                outcome = ToolOutcome.Failed(failure),
                content = listOf(ContentBlock.Text(failure.message)),
                trustLevel = TrustLevel.TRUSTED,
            )
        }
        return ToolCallResult(
            callId = "fake-${executedCalls.size}",
            outcome = ToolOutcome.Ok,
            content = listOf(ContentBlock.Text(nextResultText)),
            trustLevel = nextTrustLevel,
        )
    }

    override suspend fun preview(
        handle: ResourceHandle,
        operation: String,
        arguments: JsonObject,
    ): Result<Preview> = Result.success(Preview.Summary("fake preview for $operation"))

    override suspend fun cancel(operationId: String) {
        cancelledOperations.add(operationId)
    }

    companion object {
        fun defaultDescriptor(
            name: String = "fake.echo",
            effect: EffectKind = EffectKind.Read,
            permission: Permission = Permission.FS_READ,
            availability: ToolAvailability = ToolAvailability(tier = AvailabilityTier.ALWAYS),
        ): ToolDescriptor = ToolDescriptor(
            name = name,
            description = "Fake test tool operation",
            inputSchema = buildJsonObject {
                put("type", "object")
            },
            effect = effect,
            recoveryClass = RecoveryClass.IDEMPOTENT,
            requiredPermission = permission,
            availability = availability,
        )
    }
}
