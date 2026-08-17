package dev.aidos.mcp.policy

import dev.aidos.mcp.core.McpTransport

/** Whether an MCP tool call can only read, or necessarily leaves the device (RFC-0031). */
enum class McpEffect { READ, EGRESS }

/** The capability an MCP tool call is gated behind (RFC-0031). */
enum class McpPermission { SHELL_EXEC, NETWORK_EGRESS }

/** Where an MCP tool sits in the availability hierarchy (RFC-0031, RFC-0049). */
enum class McpTier { PLATFORM, NETWORKED }

/**
 * What can be done with an MCP tool's effect after a crash (RFC-0031).
 *
 * Deliberately mirrors the kernel's `RecoveryClass` value-for-value, for the same reason
 * [McpProfile] mirrors `PlatformProfile`: this layer stays kernel-free so it can be reused
 * outside Aidos, and `mcp-broker` translates. Mirroring rather than inventing keeps that
 * translation total and meaning-preserving — a value here with no counterpart there would force
 * the seam to guess.
 *
 * This is a crash-recovery axis ("can this be re-run"), not an undo axis ("does this reverse
 * cleanly"). `McpTransportPolicy.classify` only ever returns [CHECKABLE] today.
 */
enum class McpRecovery { PURE, IDEMPOTENT, CHECKABLE, UNSAFE }

/**
 * The transport→effect/permission/availability decision for an MCP tool, in this layer's own
 * vocabulary rather than the kernel's (`EffectKind`, `Permission`, `ToolAvailability`,
 * `RecoveryClass`). `mcp-broker` renders this as a `ToolDescriptor` (RFC-0031, "Implementation
 * Layering": "the decision" lives in `mcp-policy`, "rendering that decision as a `ToolDescriptor`"
 * lives in `mcp-broker`).
 */
data class McpOperationClassification(
    val effect: McpEffect,
    /** Non-null iff [effect] is [McpEffect.EGRESS]. */
    val egressDestination: String?,
    val permission: McpPermission,
    val profiles: Set<McpProfile>,
    val tier: McpTier,
    val requiresNetwork: Boolean,
    val recovery: McpRecovery,
)

/**
 * Classifies an [McpTransport] into an [McpOperationClassification] (RFC-0031).
 *
 * Moved from `dev.aidos.mcp.McpToolAdapter.descriptorsFor` (RFC-0031, "Implementation Layering"),
 * which split the transport→effect/permission/availability decision itself (kept here, unchanged
 * in behavior) from rendering it as a `ToolDescriptor` (left with `mcp-broker`).
 *
 * - stdio: [McpEffect.READ], [McpPermission.SHELL_EXEC], DESKTOP and HEADLESS_SERVER only,
 *   [McpTier.PLATFORM], no network required.
 * - HTTP: [McpEffect.EGRESS] to the endpoint URL, [McpPermission.NETWORK_EGRESS], every profile,
 *   [McpTier.NETWORKED], network required.
 * - Both transports default to [McpRecovery.CHECKABLE] (conservative; operator may upgrade).
 */
object McpTransportPolicy {

    fun classify(transport: McpTransport): McpOperationClassification {
        return when (transport) {
            is McpTransport.Stdio -> McpOperationClassification(
                effect = McpEffect.READ,
                egressDestination = null,
                permission = McpPermission.SHELL_EXEC,
                profiles = setOf(McpProfile.DESKTOP, McpProfile.HEADLESS_SERVER),
                tier = McpTier.PLATFORM,
                requiresNetwork = false,
                recovery = McpRecovery.CHECKABLE,
            )
            is McpTransport.Http -> McpOperationClassification(
                effect = McpEffect.EGRESS,
                egressDestination = transport.endpointUrl,
                permission = McpPermission.NETWORK_EGRESS,
                profiles = setOf(McpProfile.MOBILE, McpProfile.DESKTOP, McpProfile.HEADLESS_SERVER),
                tier = McpTier.NETWORKED,
                requiresNetwork = true,
                recovery = McpRecovery.CHECKABLE,
            )
        }
    }
}
