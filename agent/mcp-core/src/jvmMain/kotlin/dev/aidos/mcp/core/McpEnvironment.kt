package dev.aidos.mcp.core

/**
 * Scrubbed child environment for a spawned stdio MCP server (RFC-0031 Security §3).
 *
 * "Spawned servers receive no runtime connection token and no socket path" is the RFC's literal
 * requirement, but this codebase has no such variables to individually deny — RFC-0055's runtime
 * token lives in a file (`SocketPaths.defaultTokenPath`), never an environment variable, so a
 * denylist naming today's non-existent variables would protect nothing and rot silently the day
 * one is added. An **allowlist** is the only version of "scrubbed" that stays true as the runtime
 * grows: a spawned server gets nothing from the parent process's environment except what is named
 * here, plus whatever this specific server's own registration explicitly resolves from the vault
 * (`extra` — RFC-0031's `secret_refs`, an env var name to a vault entry, injected by the caller).
 */
private val ALLOWED_AMBIENT_VARS = setOf("PATH", "HOME", "LANG", "LC_ALL", "TMPDIR", "TZ", "SystemRoot")

fun scrubbedEnvironment(
    extra: Map<String, String> = emptyMap(),
    ambient: Map<String, String> = System.getenv(),
): Map<String, String> {
    val allowed = ambient.filterKeys { it in ALLOWED_AMBIENT_VARS }
    // `extra` (this server's own resolved secret_ref) wins over an ambient var of the same name --
    // an explicit, per-server grant is never shadowed by whatever happens to be in the parent's
    // environment.
    return allowed + extra
}
