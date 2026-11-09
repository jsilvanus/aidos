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
 * - **Binder**: another app on the device; MOBILE only; no network; provider pinned by signing certificate
 *
 * Nothing spawns or connects on project open (D30, lazy start).
 */

/**
 * Transport kind for an MCP server registration (RFC-0031).
 *
 * **[secretRefs] holds references, never values** (RFC-0035). The key is the name the secret is
 * injected *under* — an environment variable for stdio, a header for HTTP — and the value is the
 * vault entry to resolve it from. Resolution happens at connect time, in the layer that has vault
 * access; a registration loaded from storage never carries a plaintext secret, so it is safe to
 * hold, compare, and pass around.
 *
 * This is a map rather than a single slot because that is what `mcp_servers.secret_refs_json`
 * stores, and because [scrubbedEnvironment]'s `extra` parameter — the actual stdio injection
 * point — takes a map of name to value. An earlier single `credentialEnvVar: String?` could hold
 * either the name or the reference but not both, which lost the env var name for stdio outright
 * and forced a server configured with two secrets to be rejected as unrepresentable.
 */
sealed interface McpTransport {
    /** stdio subprocess — desktop and headless server only. */
    data class Stdio(
        val command: String,
        val args: List<String> = emptyList(),
        /** Environment variable name → vault reference. Never values, never logged. */
        val secretRefs: Map<String, String> = emptyMap(),
    ) : McpTransport

    /** Streamable HTTP — all profiles, HTTPS enforced (loopback plain-HTTP excepted). */
    data class Http(
        val endpointUrl: String,
        val authHeaderName: String = "Authorization",
        /** Header name → vault reference. Never values, never logged. */
        val secretRefs: Map<String, String> = emptyMap(),
    ) : McpTransport

    /**
     * Another app on this device, reached over Android Binder (MOBILE only). The provider is
     * identified by [packageName] **and** [signingCertSha256]: a package name alone can be claimed
     * by any app installed first, so a registration without a pinned certificate is invalid.
     * There are no secrets: authentication is the OS-verified package and signature on both sides.
     */
    data class Binder(
        val packageName: String,
        /** Fully qualified class name of the provider's exported MCP service. */
        val serviceClass: String,
        /** Lower-case hex SHA-256 digests of acceptable signing certificates. Never empty. */
        val signingCertSha256: Set<String>,
    ) : McpTransport {
        init {
            require(packageName.isNotBlank() && serviceClass.isNotBlank()) { "binder transport needs a package and a service class" }
            require(signingCertSha256.isNotEmpty()) { "binder transport requires a pinned signing certificate" }
        }
    }
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

/**
 * One operation advertised by an MCP server.
 *
 * [name], [description] and [inputSchema] are what reaches the model, and they are exactly what
 * `McpDescriptorHash` covers (RFC-0031, D31). The remaining fields are captured but **not hashed
 * and not shown**, because nothing consumes them yet.
 *
 * **If you make any of them influence policy or the model, add it to the hash and persist it in
 * `mcp_operation_adoptions` in the same change.** A field that steers a decision without being
 * hashed can be changed by a server after adoption for free -- `annotations` carries MCP's
 * `readOnlyHint`/`destructiveHint`, so it is the likeliest one to matter. `McpToolSpecFieldsTest`
 * fails when a field is added here, to force that decision rather than let it pass silently.
 */
data class McpToolSpec(
    val name: String,
    val description: String,
    val inputSchema: JsonObject = buildJsonObject {},

    /** Server-supplied display name. Not shown: `ToolDescriptor.title` uses the namespaced name. */
    val title: String? = null,

    /** Declared shape of the tool's result. Not consumed; results are validated by trust, not schema. */
    val outputSchema: JsonObject? = null,

    /** MCP tool annotations (`readOnlyHint`, `destructiveHint`, ...). Untrusted server claims. */
    val annotations: JsonObject? = null,
)
