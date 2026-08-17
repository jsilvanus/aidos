package dev.aidos.mcp

import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.mcp.core.McpServerRegistration
import dev.aidos.mcp.policy.McpTransportPolicy
import dev.aidos.mcp.policy.McpValidationResult
import dev.aidos.mcp.policy.validateHttpEndpoint as policyValidateHttpEndpoint

/**
 * Renders an [McpServerRegistration] into [ToolDescriptor]s for the `EffectBroker` (RFC-0031,
 * "Implementation Layering").
 *
 * The transport→effect/permission/availability/recovery decision itself lives in
 * [McpTransportPolicy.classify] (`mcp-policy`, kernel-free); this file's job is only to render
 * that decision into the kernel's [ToolDescriptor] shape, through [KernelMapping.kt]'s
 * translations. Enforces:
 * - All tools are `TrustLevel.UNTRUSTED` — tool results taint the Run ([McpTool] carries this,
 *   not this file — descriptors have no trust level of their own).
 * - HTTP transport forces `Egress` on every tool; stdio may be `Read`.
 * - [dev.aidos.mcp.policy.McpRecovery.CHECKABLE] by default (conservative; operator may upgrade).
 * - MCP_SERVER availability tier: never UNIVERSAL or BUNDLED.
 */
object McpToolAdapter {

    /** Returns [ToolDescriptor]s for all tools in the server registration. */
    fun descriptorsFor(registration: McpServerRegistration): List<ToolDescriptor> {
        val classification = McpTransportPolicy.classify(registration.transport)
        return registration.tools.map { spec ->
            ToolDescriptor(
                name = "${registration.serverId}:${spec.name}",
                title = spec.name,
                description = spec.description,
                inputSchema = spec.inputSchema,
                resultGuidance = null,  // MCP servers may never supply resultGuidance (D23, D6)
                effect = classification.toEffectKind(),
                requiredPermission = classification.permission.toPermission(),
                recoveryClass = classification.recovery.toRecoveryClass(),
                availability = classification.toToolAvailability(),
            )
        }
    }
}

/**
 * Validates HTTP transport registration (RFC-0031).
 *
 * Delegates to `mcp-policy`'s [dev.aidos.mcp.policy.validateHttpEndpoint] via the
 * [PlatformProfile]→[dev.aidos.mcp.policy.McpProfile] translation, so callers that only know the
 * kernel's [PlatformProfile] keep working unchanged.
 */
fun validateHttpEndpoint(
    url: String,
    profile: PlatformProfile,
): McpValidationResult = policyValidateHttpEndpoint(url, profile.toMcpProfile())
