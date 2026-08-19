package dev.aidos.mcp.policy

import dev.aidos.mcp.core.McpToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Guards [McpDescriptorHash]'s invariant: it hashes exactly what the model is shown.
 *
 * The invariant cannot be checked directly -- "what the model is shown" is decided in `mcp-broker`,
 * which this layer must not depend on. What *can* be checked is the thing that silently breaks it:
 * a new field appearing on [McpToolSpec] and quietly reaching the model, or steering policy, without
 * joining the hash and the adoption row. A server could then change that field after adoption for
 * free.
 *
 * So this test pins the field set. Adding a field to [McpToolSpec] fails it, and the fix is a
 * decision rather than an edit: either the field reaches the model/policy -- in which case add it to
 * [McpDescriptorHash], persist it in `mcp_operation_adoptions`, and update [HASHED] here -- or it
 * does not, in which case add it to [CAPTURED_NOT_HASHED] with a note saying why.
 */
class McpToolSpecFieldsTest {

    /** Covered by [McpDescriptorHash] and persisted with the adoption. */
    private val hashed = setOf("name", "description", "inputSchema")

    /**
     * Captured from the server but neither shown to the model nor consumed by policy, so
     * deliberately outside the hash (hashing them would break the invariant, not uphold it).
     */
    private val capturedNotHashed = setOf("title", "outputSchema", "annotations")

    @Test
    fun `every McpToolSpec field is either hashed or explicitly excluded`() {
        // java.lang.reflect, not kotlin-reflect: mcp-policy is deliberately light on dependencies
        // and a data class's backing fields are enough to detect a new one appearing.
        val declared = McpToolSpec::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }
            .toSet()
        assertEquals(
            hashed + capturedNotHashed,
            declared,
            "McpToolSpec's fields changed. Decide, do not just update this list: if the new field " +
                "reaches the model or steers policy it must join McpDescriptorHash AND be persisted " +
                "in mcp_operation_adoptions, otherwise a server can change it after adoption for free.",
        )
    }

    @Test
    fun `a field outside the hash does not change the hash`() {
        val base = McpToolSpec(
            name = "delete_file",
            description = "Deletes a file",
            inputSchema = buildJsonObject { put("type", "object") },
        )
        // A server flipping destructiveHint must not silently pass as "unchanged" *because we
        // failed to notice* -- it passes because nothing consumes annotations yet. The moment
        // something does, the test above fails and this expectation has to be revisited.
        val withAnnotations = base.copy(
            annotations = buildJsonObject { put("destructiveHint", true) },
            title = "Delete file",
        )
        assertEquals(
            McpDescriptorHash.hash(base),
            McpDescriptorHash.hash(withAnnotations),
            "unconsumed fields are outside the hash by design; see McpDescriptorHash's invariant",
        )
    }

    @Test
    fun `a widened parameter inside properties still changes the hash`() {
        val narrow = McpToolSpec(
            name = "read_file",
            description = "Reads a file",
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject { put("path", buildJsonObject { put("type", "string") }) },
                )
            },
        )
        // Same prose, wider parameter: the case schema/project.sql's mcp_operation_adoptions
        // comment calls out. `properties` survives the SDK verbatim, so this is covered.
        val widened = narrow.copy(
            inputSchema = buildJsonObject {
                put("type", "object")
                put(
                    "properties",
                    buildJsonObject { put("path", buildJsonObject { put("type", "object") }) },
                )
            },
        )
        assert(McpDescriptorHash.hash(narrow) != McpDescriptorHash.hash(widened)) {
            "a widened parameter with unchanged prose must change the descriptor hash"
        }
    }
}
