package dev.aidos.mcp.core

/**
 * MCP `tools/call` result content (RFC-0031, Implementation Layering amendment, blocker 1).
 *
 * Only [Text] exists here. The kernel's `ContentBlock` has three cases (`Text`, `Image`,
 * `ResourceRef`), but all three transports in this module parse only text content blocks today —
 * `type: "image"` and `type: "resource"` frames are MVP-deferred, not silently mishandled. Adding
 * `Image` or `ResourceRef` cases to this type before a transport actually produces them would be
 * speculative; adding them by reusing `ContentBlock` directly would be worse, because
 * `ContentBlock.ResourceRef` carries a `ContentNodeId`, a kernel type, and `mcp-core` must stay
 * kernel-free by construction (RFC-0031's whole reason for splitting `mcp-core` out of `agent/mcp`
 * in the first place). `mcp-broker` is where this type meets `ContentBlock`: it maps
 * [McpContent.Text] to `ContentBlock.Text` at the boundary between this reusable layer and the
 * Aidos-bound one.
 */
sealed interface McpContent {
    data class Text(val text: String) : McpContent
}
