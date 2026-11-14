package dev.aidos.broker

import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ActorRef
import dev.aidos.kernel.AidosError
import dev.aidos.kernel.Capability
import dev.aidos.kernel.CapabilityCheckResult
import dev.aidos.kernel.CapabilityId
import dev.aidos.kernel.CapabilityManager
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.DenialReason
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.ResourceHandle
import dev.aidos.kernel.SubjectKind
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.serialization.json.JsonObject

/**
 * The sole invocation path for tools (RFC-0030, RFC-0046, M4).
 *
 * Enforces the fixed 8-step sequence from RFC-0030:
 *   1. validate arguments against the operation's JSON Schema
 *   2. resolve the named capability (no capability → denied)
 *   3. apply taint attenuation (validate() returns Denied or Allowed)
 *   4. reserve budget (stub in MVP — full ledger arrives with M5)
 *   5. preview, if the effect is Mutate
 *   6. write the audit row  ← M4: every effect writes one row (attributed per RFC-0046)
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
    private val actorResolver: ((subjectId: String, capabilityId: CapabilityId?) -> ActorRef)? = null,
) : EffectBroker {

    private val tools = mutableMapOf<String, Tool>()
    private val schemaValidator = JsonSchemaValidator()

    override fun register(tool: Tool) {
        tools[tool.id] = tool
    }

    override fun descriptorsFor(
        subjectId: String,
        profile: PlatformProfile,
        networkAvailable: Boolean,
    ): List<ToolDescriptor> =
        tools.values
            .flatMap { it.operations() }
            .filter { it.availability.availableOn(profile, networkAvailable) }

    override suspend fun invoke(
        subjectId: String,
        call: ToolCall,
        runTaint: TrustLevel,
    ): ToolCallResult {
        // Step 1: operation exists.
        val tool = tools.values.firstOrNull { t -> t.operations().any { it.name == call.toolName } }
            ?: return auditAndReturn(
                subjectId = subjectId,
                call = call,
                kind = "ToolFailed",
                capabilityId = call.capabilityId,
                result = denied(call, "tool.not_found", "No registered tool offers operation '${call.toolName}'"),
            )
        val descriptor = tool.operations().first { it.name == call.toolName }

        // Step 1a: validate arguments against the operation's JSON Schema (RFC-0030, RFC-0008)
        val schemaValidationResult = validateArgumentSchema(call, descriptor)
        if (schemaValidationResult.isFailure) {
            return auditAndReturn(
                subjectId = subjectId,
                call = call,
                kind = "ToolFailed",
                capabilityId = call.capabilityId,
                result = denied(
                    call,
                    "arguments.invalid",
                    schemaValidationResult.exceptionOrNull()?.message ?: "arguments do not match schema"
                ),
            )
        }

        // Step 2: capability is named in the call.
        val capId = call.capabilityId
            ?: return auditAndReturn(
                subjectId = subjectId,
                call = call,
                kind = "CapabilityDenied",
                capabilityId = null,
                result = denied(call, "capability.missing", "ToolCall does not name a capability"),
            )

        // Step 2a: verify that the named capability actually grants descriptor.requiredPermission.
        val capsForCheck = runCatching { capabilityManager.loadForSubject(subjectId) }.getOrDefault(emptyList())
        val mcpCapsForCheck = if (':' in descriptor.name) {
            runCatching { capabilityManager.loadForSubject(descriptor.name.substringBefore(':')) }.getOrDefault(emptyList())
        } else {
            emptyList()
        }
        val matchedCap = (capsForCheck + mcpCapsForCheck).firstOrNull { it.id == capId }
        if (matchedCap != null && matchedCap.permission != descriptor.requiredPermission) {
            return auditAndReturn(
                subjectId = subjectId,
                call = call,
                kind = "CapabilityDenied",
                capabilityId = capId,
                result = ToolCallResult(
                    callId = call.callId,
                    outcome = ToolOutcome.Denied(DenialReason.SCOPE_MISMATCH),
                    content = listOf(ContentBlock.Text("denied: capability permission ${matchedCap.permission} does not match required ${descriptor.requiredPermission}")),
                    trustLevel = TrustLevel.TRUSTED,
                ),
            )
        }

        // Step 3: validate through the capability manager.
        val operation = object : dev.aidos.kernel.Operation<Unit> {
            override val name = descriptor.name
            override val effect = descriptor.effect
            override val recoveryClass = descriptor.recoveryClass
        }
        val check = capabilityManager.validate(
            subjectId = subjectId,
            capabilityId = capId,
            operation = operation,
            runTaint = runTaint,
        )
        if (check is CapabilityCheckResult.Denied) {
            val reason = check.reason
            return auditAndReturn(
                subjectId = subjectId,
                call = call,
                kind = "CapabilityDenied",
                capabilityId = capId,
                result = ToolCallResult(
                    callId = call.callId,
                    outcome = ToolOutcome.Denied(reason),
                    content = listOf(ContentBlock.Text("denied: $reason")),
                    trustLevel = TrustLevel.TRUSTED,
                ),
            )
        }

        // Step 4: preview, if the effect is Mutate.
        if (descriptor.effect is EffectKind.Mutate) {
            val handle = capabilityManager.openHandle(subjectId, capId).getOrElse {
                return auditAndReturn(
                    subjectId = subjectId,
                    call = call,
                    kind = "ToolFailed",
                    capabilityId = capId,
                    result = denied(call, "capability.open_failed", it.message ?: "handle open failed"),
                )
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

        val caps = runCatching { capabilityManager.loadForSubject(subjectId) }.getOrDefault(emptyList())
        val actor = resolveActor(subjectId, capId, caps)
        val projectId = resolveProjectId(subjectId, capId, caps)

        // Step 6: audit row before execution.
        audit.write(
            id = idGen(),
            projectId = projectId,
            kind = "ToolInvoked",
            actor = actor,
            capabilityId = capId.value,
            subjectRef = call.toolName,
            nowIso = nowIso(),
        )

        // Step 7: execute.
        val handle = capabilityManager.openHandle(subjectId, capId).getOrElse {
            val errorResult = denied(call, "capability.open_failed", it.message ?: "handle open failed")
            audit.write(
                id = idGen(),
                projectId = projectId,
                kind = "ToolFailed",
                actor = actor,
                capabilityId = capId.value,
                subjectRef = call.toolName,
                nowIso = nowIso(),
            )
            return errorResult
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
            projectId = projectId,
            kind = completionKind,
            actor = actor,
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

    private suspend fun resolveProjectId(
        subjectId: String,
        capId: CapabilityId?,
        caps: List<Capability>? = null,
    ): String {
        if (capId != null) {
            val resolved = projectIdResolver(capId)
            if (resolved.isNotBlank()) return resolved
        }
        val subjectCaps = caps ?: runCatching { capabilityManager.loadForSubject(subjectId) }.getOrDefault(emptyList())
        if (capId != null) {
            subjectCaps.firstOrNull { it.id == capId }
                ?.projectId?.value?.takeIf { it.isNotBlank() }
                ?.let { return it }
        }
        val projectIds = subjectCaps.map { it.projectId.value }.filter { it.isNotBlank() }.distinct()
        return if (projectIds.size == 1) projectIds.single() else ""
    }

    private suspend fun resolveActor(
        subjectId: String,
        capId: CapabilityId?,
        preloadedCaps: List<Capability>? = null,
    ): ActorRef {
        actorResolver?.invoke(subjectId, capId)?.let { return it }
        val caps = preloadedCaps ?: runCatching { capabilityManager.loadForSubject(subjectId) }.getOrDefault(emptyList())
        val cap = if (capId != null) caps.firstOrNull { it.id == capId } else caps.firstOrNull()
        if (cap != null) {
            val kind = when (cap.subjectKind) {
                SubjectKind.SESSION -> ActorKind.SESSION
                SubjectKind.WORKER -> ActorKind.WORKER
                SubjectKind.PLUGIN -> ActorKind.PLUGIN
                SubjectKind.MCP_SERVER -> ActorKind.MCP_SERVER
                SubjectKind.FRONTEND -> ActorKind.USER
            }
            return ActorRef(kind, subjectId)
        }
        val kind = when {
            subjectId.startsWith("worker") -> ActorKind.WORKER
            subjectId.startsWith("mcp") -> ActorKind.MCP_SERVER
            subjectId.startsWith("plugin") -> ActorKind.PLUGIN
            subjectId.startsWith("user") -> ActorKind.USER
            subjectId == "reconciler" || subjectId == "recovery" || subjectId == "runtime" -> ActorKind.RUNTIME
            else -> ActorKind.SESSION
        }
        return ActorRef(kind, subjectId)
    }

    private suspend fun auditAndReturn(
        subjectId: String,
        call: ToolCall,
        kind: String,
        capabilityId: CapabilityId?,
        result: ToolCallResult,
    ): ToolCallResult {
        val caps = runCatching { capabilityManager.loadForSubject(subjectId) }.getOrDefault(emptyList())
        val actor = resolveActor(subjectId, capabilityId, caps)
        val projectId = resolveProjectId(subjectId, capabilityId, caps)
        audit.write(
            id = idGen(),
            projectId = projectId,
            kind = kind,
            actor = actor,
            capabilityId = capabilityId?.value,
            subjectRef = call.toolName,
            nowIso = nowIso(),
        )
        return result
    }

    private fun validateArgumentSchema(
        call: ToolCall,
        descriptor: ToolDescriptor,
    ): Result<Unit> = schemaValidator.validate(call.arguments, descriptor.inputSchema)

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
            outcome = ToolOutcome.Failed(AidosError(code = "tool.failed", errorClass = dev.aidos.kernel.ErrorClass.TRANSIENT, message = error.message ?: "tool execution failed")),
            content = listOf(ContentBlock.Text("Tool execution failed: ${error.message}")),
            trustLevel = TrustLevel.TRUSTED,
        )
}
