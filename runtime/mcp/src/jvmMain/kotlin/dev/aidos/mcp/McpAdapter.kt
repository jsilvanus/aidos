package dev.aidos.mcp

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectBroker
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.Preview
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.Tool
import dev.aidos.kernel.ToolAvailability
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject

/**
 * MCP tool registration (RFC-0031, M18).
 *
 * An MCP server's tools are registered into the [EffectBroker] with:
 * - [EffectKind] assigned by transport type and tool metadata
 * - [RecoveryClass] defaulting to REVERSIBLE for read-only, UNSAFE for mutations
 * - [TrustLevel.UNTRUSTED] for all results — MCP servers are UNTRUSTED subjects (RFC-0027)
 * - No capability escalation — an MCP server cannot raise a capability request (D30)
 *
 * Transport differences:
 * - **stdio**: spawns a subprocess; DESKTOP/HEADLESS_SERVER only; `Read` effect possible
 * - **HTTP**: every call is `Egress`; available on every profile; HTTPS enforced except loopback
 *
 * Nothing spawns or connects on project open (D30, lazy start).
 */

/** Transport kind for an MCP server registration (RFC-0031). */
sealed interface McpTransport {
    /** stdio subprocess — desktop and headless server only. */
    data class Stdio(
        val command: String,
        val args: List<String> = emptyList(),
        /** Credential injected into the child's environment (never logged). */
        val credentialEnvVar: String? = null,
    ) : McpTransport

    /** Streamable HTTP — all profiles, HTTPS enforced (loopback plain-HTTP excepted). */
    data class Http(
        val endpointUrl: String,
        val authHeaderName: String = "Authorization",
        /** Credential injected as auth header value (never logged). */
        val credentialEnvVar: String? = null,
    ) : McpTransport
}

/**
 * A registered MCP server (RFC-0031).
 *
 * [tools] is the catalog fetched at enable-time and frozen for the session.
 * The catalog is the complete authority picture; it never grows at runtime (D30).
 */
data class McpServerRegistration(
    val serverId: String,
    val transport: McpTransport,
    val tools: List<McpToolSpec>,
)

data class McpToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject = buildJsonObject {},
)

/**
 * Adapts an [McpServerRegistration] into [ToolDescriptor]s for the [EffectBroker] (RFC-0031).
 *
 * Enforces:
 * - All tools are [TrustLevel.UNTRUSTED] — tool results taint the Run.
 * - HTTP transport forces [EffectKind.Egress] on every tool; stdio may be [EffectKind.Read].
 * - [RecoveryClass.CHECKABLE] by default (conservative; operator may upgrade).
 * - MCP_SERVER availability tier: never UNIVERSAL or BUNDLED.
 */
object McpToolAdapter {

    /** Returns [ToolDescriptor]s for all tools in the server registration. */
    fun descriptorsFor(registration: McpServerRegistration): List<ToolDescriptor> {
        return registration.tools.map { spec ->
            val (effect, availability) = when (registration.transport) {
                is McpTransport.Stdio -> Pair(
                    EffectKind.Read,  // stdio tools may be read-only; HTTP tools are always Egress
                    ToolAvailability(
                        profiles = setOf(PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER),
                        tier = AvailabilityTier.PLATFORM,
                        requiresNetwork = false,
                    )
                )
                is McpTransport.Http -> Pair(
                    EffectKind.Egress(destination = registration.transport.endpointUrl),
                    ToolAvailability(
                        profiles = setOf(
                            PlatformProfile.DESKTOP,
                            PlatformProfile.HEADLESS_SERVER,
                            PlatformProfile.MOBILE,
                        ),
                        tier = AvailabilityTier.NETWORKED,
                        requiresNetwork = true,
                    )
                )
            }

            val requiredPermission = when (registration.transport) {
                is McpTransport.Stdio -> Permission.SHELL_EXEC
                is McpTransport.Http -> Permission.NETWORK_EGRESS
            }

            ToolDescriptor(
                name = "${registration.serverId}:${spec.name}",
                title = spec.name,
                description = spec.description,
                inputSchema = spec.inputSchema,
                resultGuidance = null,  // MCP servers may never supply resultGuidance (D23, D6)
                effect = effect,
                requiredPermission = requiredPermission,
                recoveryClass = RecoveryClass.CHECKABLE,
                availability = availability,
            )
        }
    }
}

/**
 * Validates HTTP transport registration (RFC-0031).
 *
 * HTTPS is enforced except for loopback addresses on DESKTOP and HEADLESS_SERVER.
 * Cross-host redirects are refused (not enforced here — the HTTP client must be configured).
 */
fun validateHttpEndpoint(
    url: String,
    profile: PlatformProfile,
): McpValidationResult {
    if (url.startsWith("http://")) {
        val isLoopback = url.startsWith("http://localhost") ||
                url.startsWith("http://127.0.0.1") ||
                url.startsWith("http://[::1]")
        val allowPlain = isLoopback &&
                profile in setOf(PlatformProfile.DESKTOP, PlatformProfile.HEADLESS_SERVER)
        if (!allowPlain) {
            return McpValidationResult.Rejected(
                "Plain HTTP is refused for non-loopback endpoints on $profile. Use HTTPS."
            )
        }
    }
    return McpValidationResult.Ok
}

sealed interface McpValidationResult {
    data object Ok : McpValidationResult
    data class Rejected(val reason: String) : McpValidationResult
}
