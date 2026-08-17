package dev.aidos.mcp.core

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject

/**
 * MCP tool registration (RFC-0031, M18).
 *
 * An MCP server's tools are registered into the `EffectBroker` with:
 * - `EffectKind` assigned by transport type and tool metadata
 * - `RecoveryClass` defaulting to REVERSIBLE for read-only, UNSAFE for mutations
 * - `TrustLevel.UNTRUSTED` for all results — MCP servers are UNTRUSTED subjects (RFC-0027)
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
