package dev.aidos.mcp.policy

import dev.aidos.mcp.core.McpToolSpec
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Stable descriptor hash over `(name, description, inputSchema)` (RFC-0031, D31; schema/project.sql's
 * `mcp_operation_adoptions` comment).
 *
 * This is the hash `mcp_operation_adoptions.descriptor_hash` is keyed on. An operation is offered to
 * the model only if the user has seen the descriptor this hash covers — the DDL comment states why
 * all three fields are in scope: *"a description that stays constant while a parameter widens is a
 * real attack, and hashing prose alone would miss it."* [McpOperationAdoptionStore] (`mcp-broker`) is
 * the consumer; this layer only computes the hash, kernel-free (RFC-0031 "Implementation Layering").
 *
 * **The invariant: this hashes exactly what the model is shown, and nothing else.**
 *
 * That is a deliberate choice, not an accident of which fields were handy. The official Kotlin MCP
 * SDK parses a tool's `inputSchema` into a typed `ToolSchema` carrying only `$schema`, `properties`,
 * `required`, `$defs` and `type`; any other top-level JSON Schema keyword a server sent -- notably
 * `additionalProperties`, `patternProperties` and the `oneOf`/`anyOf`/`allOf` combinators -- is
 * dropped at parse time and cannot be recovered downstream. Hashing the raw wire bytes was
 * considered and rejected: the SDK's view is also what we render for the model, so a keyword the SDK
 * drops is a keyword the model never sees and therefore cannot act on, while hashing bytes the model
 * never sees would report changes that cannot affect behaviour. Anything the model *can* act on is
 * covered -- `properties` passes through the SDK as a raw [JsonObject], so a widened or retyped
 * parameter still changes this hash, which is the attack the DDL comment names.
 *
 * The corollary is a rule, enforced by `McpToolSpecFieldsTest`: **the moment a field of
 * [McpToolSpec] starts reaching the model or steering policy, it must join this hash and be
 * persisted alongside the adoption in the same change.** `annotations` (`readOnlyHint`,
 * `destructiveHint`) is the likeliest candidate; until something consumes it, hashing it would
 * violate the invariant above rather than uphold it.
 *
 * **Canonicalization.** [JsonObject] does not define key-order equality — two structurally identical
 * schemas can arrive with their keys in different orders (a server re-serializing its catalog, a
 * different JSON library, a client re-fetch) and a hash that changed under that would silently
 * un-adopt every operation on the next connection. [canonicalize] recursively rebuilds every nested
 * object with its keys sorted lexicographically before the tree is serialized to text and hashed;
 * arrays keep their element order (order is semantically meaningful in a JSON array, unlike an
 * object's key order). Serialization uses [Json.encodeToString] rather than hand-rolled string
 * concatenation so that string escaping (quotes, backslashes, control characters) is delegated to a
 * tested encoder instead of re-derived here — an under-escaped separator is exactly the kind of bug
 * that would make two different descriptors collide.
 */
object McpDescriptorHash {

    private val json = Json { encodeDefaults = true }

    /** Hashes `(spec.name, spec.description, spec.inputSchema)`. */
    fun hash(spec: McpToolSpec): String = hash(spec.name, spec.description, spec.inputSchema)

    /** Hashes `(name, description, inputSchema)` directly, for callers that don't hold an [McpToolSpec]. */
    fun hash(name: String, description: String, inputSchema: JsonObject): String {
        val wrapper = buildJsonObject {
            put("name", name)
            put("description", description)
            put("inputSchema", inputSchema)
        }
        val canonical = canonicalize(wrapper)
        val text = json.encodeToString(JsonElement.serializer(), canonical)
        return sha256Hex(text)
    }

    /**
     * Rebuilds [element] with every nested [JsonObject]'s keys sorted lexicographically. Arrays are
     * canonicalized element-by-element but keep their order; primitives (including `null`) are
     * returned unchanged.
     */
    fun canonicalize(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(
            element.entries
                .sortedBy { it.key }
                .associate { (key, value) -> key to canonicalize(value) },
        )
        is JsonArray -> JsonArray(element.map { canonicalize(it) })
        else -> element
    }

    private fun sha256Hex(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }
}
