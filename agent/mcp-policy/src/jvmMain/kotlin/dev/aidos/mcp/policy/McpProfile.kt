package dev.aidos.mcp.policy

/**
 * Platform profile as this layer sees it (RFC-0031, "Implementation Layering").
 *
 * This deliberately mirrors the kernel module's `PlatformProfile` value-for-value rather than
 * importing it. `mcp-policy` is specified as kernel-free (RFC-0031's open question, resolved in
 * favor of the kernel-free recommendation) so that a consumer outside Aidos — one with no kernel
 * dependency and no reason to take one — can still reuse the transport and endpoint rules in this
 * module as plain code. Importing `PlatformProfile` would drag Aidos's kernel module into that
 * consumer's build for the sake of one three-value enum.
 *
 * `mcp-broker`, which is kernel-bound by construction, is responsible for translating between
 * this enum and the kernel module's `PlatformProfile` at the boundary.
 */
enum class McpProfile { MOBILE, DESKTOP, HEADLESS_SERVER }
