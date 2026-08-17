package dev.aidos.mcp

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.ToolAvailability
import dev.aidos.mcp.core.McpContent
import dev.aidos.mcp.policy.McpEffect
import dev.aidos.mcp.policy.McpOperationClassification
import dev.aidos.mcp.policy.McpPermission
import dev.aidos.mcp.policy.McpProfile
import dev.aidos.mcp.policy.McpRecovery

/**
 * The single seam where `mcp-core`/`mcp-policy` vocabulary becomes the kernel's (RFC-0031,
 * "Implementation Layering").
 *
 * `mcp-core` and `mcp-policy` are kernel-free by construction, so that a consumer outside Aidos
 * can reuse the MCP transport and trust rules as plain code with no dependency on Aidos's
 * authority vocabulary. That means every kernel type MCP tools eventually need —
 * `ContentBlock`, `PlatformProfile`, `EffectKind`, `Permission`, `AvailabilityTier`,
 * `RecoveryClass`, `ToolAvailability` — has to be produced somewhere on the Aidos-bound side of
 * the boundary. This file is that somewhere: the only place in the codebase that imports both a
 * `dev.aidos.mcp.core`/`dev.aidos.mcp.policy` type and its kernel counterpart in the same
 * translation.
 */

/** [McpContent.Text] is the only case today (mcp-core's own KDoc explains why). */
fun McpContent.toContentBlock(): ContentBlock = when (this) {
    is McpContent.Text -> ContentBlock.Text(text)
}

fun McpProfile.toPlatformProfile(): PlatformProfile = when (this) {
    McpProfile.MOBILE -> PlatformProfile.MOBILE
    McpProfile.DESKTOP -> PlatformProfile.DESKTOP
    McpProfile.HEADLESS_SERVER -> PlatformProfile.HEADLESS_SERVER
}

fun PlatformProfile.toMcpProfile(): McpProfile = when (this) {
    PlatformProfile.MOBILE -> McpProfile.MOBILE
    PlatformProfile.DESKTOP -> McpProfile.DESKTOP
    PlatformProfile.HEADLESS_SERVER -> McpProfile.HEADLESS_SERVER
}

fun McpPermission.toPermission(): Permission = when (this) {
    McpPermission.SHELL_EXEC -> Permission.SHELL_EXEC
    McpPermission.NETWORK_EGRESS -> Permission.NETWORK_EGRESS
}

/**
 * Total and value-for-value, because `mcp-policy`'s [McpRecovery] mirrors [RecoveryClass]
 * deliberately. Nothing here is a judgement call: a mapping that had to pick a "closest" kernel
 * value would mean the two enums had drifted, and this seam is the place that would silently
 * absorb the drift.
 *
 * [McpTransportPolicy.classify][dev.aidos.mcp.policy.McpTransportPolicy.classify] only ever
 * produces [McpRecovery.CHECKABLE] today; the other branches exist because the enum does.
 */
fun McpRecovery.toRecoveryClass(): RecoveryClass = when (this) {
    McpRecovery.PURE -> RecoveryClass.PURE
    McpRecovery.IDEMPOTENT -> RecoveryClass.IDEMPOTENT
    McpRecovery.CHECKABLE -> RecoveryClass.CHECKABLE
    McpRecovery.UNSAFE -> RecoveryClass.UNSAFE
}

/**
 * Renders an [McpOperationClassification]'s effect as a kernel [EffectKind]. [egressDestination]
 * is non-null iff [McpOperationClassification.effect] is [McpEffect.EGRESS] — the same invariant
 * `mcp-policy` documents on the data class itself.
 */
fun McpOperationClassification.toEffectKind(): EffectKind = when (effect) {
    McpEffect.READ -> EffectKind.Read
    McpEffect.EGRESS -> EffectKind.Egress(destination = requireNotNull(egressDestination) {
        "McpOperationClassification.effect is EGRESS but egressDestination is null"
    })
}

fun McpOperationClassification.toToolAvailability(): ToolAvailability = ToolAvailability(
    profiles = profiles.map { it.toPlatformProfile() }.toSet(),
    tier = when (tier) {
        dev.aidos.mcp.policy.McpTier.PLATFORM -> AvailabilityTier.PLATFORM
        dev.aidos.mcp.policy.McpTier.NETWORKED -> AvailabilityTier.NETWORKED
    },
    requiresNetwork = requiresNetwork,
)
