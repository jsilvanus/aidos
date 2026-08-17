package dev.aidos.mcp.policy

/**
 * The two trust invariants RFC-0031 states for every MCP server, expressed as enforceable code
 * rather than left as prose a caller could forget.
 *
 * Both rules are stated *unconditionally* — there is no per-server or per-tool override anywhere
 * in this layer. `mcp-broker` is expected to apply [RESULTS_ARE_UNTRUSTED] and
 * [FORBID_SERVER_SUPPLIED_RESULT_GUIDANCE] when it renders a result or a `ToolDescriptor`; this
 * object exists so that decision is named once, in the kernel-free layer, instead of being
 * re-derived (or silently dropped) at each call site.
 */
object McpTrustPolicy {

    /**
     * An MCP server's tool results are UNTRUSTED, always, regardless of what the server itself
     * claims about them (RFC-0027; D30). Nothing in this layer computes a trust level *from* the
     * server's output — that would let a malicious or buggy server assert its own trust. The
     * value is fixed, not derived.
     *
     * There is no corresponding kernel `TrustLevel` type here (that would violate the kernel-free
     * constraint); `mcp-broker` is the layer that maps this constant onto the kernel module's
     * `TrustLevel.UNTRUSTED`.
     */
    const val RESULTS_ARE_UNTRUSTED: Boolean = true

    /**
     * An MCP server may never supply `resultGuidance` for its own tools (D23, D6) — a server
     * cannot tell the model how much to trust or how to interpret its own output, which would be
     * a self-reported capability escalation. This is not a value to check but a constraint on
     * what a caller is permitted to construct: nothing in `mcp-policy` or `mcp-core` exposes a
     * `resultGuidance` field or a way to set one from server-supplied data, so there is no code
     * path to enforce here beyond that absence. `mcp-broker`, which does define `ToolDescriptor`,
     * is responsible for setting `resultGuidance = null` when it renders MCP tools — this KDoc is
     * the record of why, since the rule itself cannot be expressed more concretely than "the type
     * this layer hands to the broker has no such field."
     */
    const val SERVERS_MAY_NOT_SUPPLY_RESULT_GUIDANCE: Boolean = true
}
