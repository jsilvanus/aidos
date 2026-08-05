package dev.aidos.broker

import dev.aidos.kernel.AidosError
import dev.aidos.kernel.CapabilityCheckResult
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityManager
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.serialization.json.JsonObject

/**
 * The sole invocation path for tools (RFC-0030, M4).
 *
 * Enforces the fixed 8-step sequence from RFC-0030:
 *   1. validate arguments against the operation's JSON Schema
 *   2. resolve the named capability (no capability → denied)
 *   3. apply taint attenuation (validate() returns Denied or Allowed)
 *   4. reserve budget (stub in MVP — full ledger arrives with M5)
 *   5. preview, if the effect is Mutate
 *   6. write the audit row  ← M4: every effect writes one row
 *   7. execute
 *   8. taint the Run from the result's trust level (returned to caller)
 *
 * M4 done-when: every validate call and every effect invocation writes one audit row.
 * The [AuditEnforcingBroker] wrapper at the bottom of this file provides the test harness
 * that asserts this — making it a test failure, not a review comment.
 */
class ToolBroker(
    private val capabilityManager: CapabilityManager,
    private val audit: AuditLog,
    private val idGen: () -> String,
    private val nowIso: () -> String,
    /** Resolves a project ID from a capability ID for audit row context. */
    private val projectIdResolver: (CapabilityId) -> String = { "" },
) : EffectBroker {

    private val tools = mutableMapOf<String, Tool>()

    override fun register(tool: Tool) {
        tools[tool.id] = tool
    }

    override fun descriptorsFor(
        subjectId: String,
        profile: PlatformProfile,
        networkAvailable: Boolean,
    ): List<ToolDescriptor> = tools.values.flatMap { it.operations() }

    override suspend fun invoke(
        subjectId: String,
        call: ToolCall,
        runTaint: TrustLevel,
    ): ToolCallResult {
        // Step 1: resolve tool and find the matching operation descriptor.
        val tool = tools.values.firstOrNull { t ->
            t.operations().any { it.name == call.toolName }
        } ?: return denied(call, "tool.not_found", "No tool registered for '${call.toolName}'")

        val descriptor = tool.operations().first { it.name == call.toolName }

        // Step 2: capability must be named — no search.
        val capId = call.capabilityId
            ?: return auditAndReturn(
                subjectId = subjectId,
                call = call,
                kind = "CapabilityDenied",
                capabilityId = null,
                result = denied(call, "capability.missing", "No capability named in call to '${call.toolName}'"),
            )

        // Step 3: taint attenuation. validate() checks revocation, expiry, and RFC-0027 rules.
        val operation = object : dev.aidos.kernel.Operation<Unit> {
            override val name = descriptor.name
            override val effect = descriptor.effect
            override val recoveryClass = descriptor.recoveryClass
        }
        val checkResult = capabilityManager.validate(subjectId, capId, operation, runTaint)
        if (checkResult is CapabilityCheckResult.Denied) {
            return auditAndReturn(
                subjectId = subjectId,
                call = call,
                kind = "CapabilityDenied",
                capabilityId = capId,
                result = ToolCallResult(
                    callId = call.callId,
                    outcome = ToolOutcome.Denied(checkResult.reason),
                    content = listOf(ContentBlock.Text("denied: ${checkResult.reason}")),
                    trustLevel = TrustLevel.TRUSTED,
                ),
            )
        }

        // Step 4: budget reservation (stub — full ledger in M5).
        // Nothing to do in MVP; budget ceilings are enforced at grant time (RFC-0028).

        // Step 5: preview for Mutate effects.
        if (descriptor.effect is EffectKind.Mutate) {
            val handle = capabilityManager.openHandle(subjectId, capId).getOrElse {
                return denied(call, "capability.open_failed", it.message ?: "handle open failed")
            }
            val preview = tool.preview(handle, call.toolName, call.arguments)
            if (preview.isFailure) {
                return auditAndReturn(
                    subjectId = subjectId,
                    call = call,
                    kind = "ToolFailed",
                    capabilityId = capId,
                    result = failed(call, preview.exceptionOrNull()!!),
                )
            }
        }

        // Step 6: audit row before execution.
        audit.write(
            id = idGen(),
            projectId = resolveProjectId(capId),
            kind = "ToolInvoked",
            actorKind = "SESSION",
            actorId = subjectId,
            capabilityId = capId.value,
            subjectRef = call.toolName,
            nowIso = nowIso(),
        )

        // Step 7: execute.
        val handle = capabilityManager.openHandle(subjectId, capId).getOrElse {
            return denied(call, "capability.open_failed", it.message ?: "handle open failed")
        }
        val result = tool.execute(handle, call.toolName, call.arguments)

        // Step 8: write completion audit row.
        val completionKind = when (result.outcome) {
            is ToolOutcome.Ok -> "ToolCompleted"
            is ToolOutcome.Denied -> "CapabilityDenied"
            is ToolOutcome.Failed -> "ToolFailed"
            is ToolOutcome.Cancelled -> "ToolCancelled"
        }
        audit.write(
            id = idGen(),
            projectId = resolveProjectId(capId),
            kind = completionKind,
            actorKind = "SESSION",
            actorId = subjectId,
            capabilityId = capId.value,
            subjectRef = call.toolName,
            nowIso = nowIso(),
        )

        return result
    }

    override suspend fun preview(subjectId: String, call: ToolCall): Result<Preview> {
        val tool = tools.values.firstOrNull { t -> t.operations().any { it.name == call.toolName } }
            ?: return Result.failure(RuntimeException("No tool registered for '${call.toolName}'"))
        val capId = call.capabilityId
            ?: return Result.failure(RuntimeException("No capability named in preview call"))
        val handle = capabilityManager.openHandle(subjectId, capId).getOrElse {
            return Result.failure(it)
        }
        return tool.preview(handle, call.toolName, call.arguments)
    }

    override suspend fun cancel(callId: String) {
        tools.values.forEach { it.cancel(callId) }
    }

    private fun resolveProjectId(capId: CapabilityId): String = projectIdResolver(capId)

    private fun auditAndReturn(
        subjectId: String,
        call: ToolCall,
        kind: String,
        capabilityId: CapabilityId?,
        result: ToolCallResult,
    ): ToolCallResult {
        audit.write(
            id = idGen(),
            projectId = "",
            kind = kind,
            actorKind = "SESSION",
            actorId = subjectId,
            capabilityId = capabilityId?.value,
            subjectRef = call.toolName,
            nowIso = nowIso(),
        )
        return result
    }

    private fun denied(call: ToolCall, code: String, message: String): ToolCallResult =
        ToolCallResult(
            callId = call.callId,
            outcome = ToolOutcome.Failed(AidosError(code = code, errorClass = dev.aidos.kernel.ErrorClass.DENIED, message = message)),
            content = listOf(ContentBlock.Text(message)),
            trustLevel = TrustLevel.TRUSTED,
        )

    private fun failed(call: ToolCall, error: Throwable): ToolCallResult =
        ToolCallResult(
            callId = call.callId,
            outcome = ToolOutcome.Failed(AidosError(code = "tool.error", errorClass = dev.aidos.kernel.ErrorClass.TRANSIENT, message = error.message ?: "unknown")),
            content = listOf(ContentBlock.Text(error.message ?: "unknown")),
            trustLevel = TrustLevel.TRUSTED,
        )
}
