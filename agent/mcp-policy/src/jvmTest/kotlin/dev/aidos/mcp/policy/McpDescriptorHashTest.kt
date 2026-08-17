package dev.aidos.mcp.policy

import dev.aidos.mcp.core.McpToolSpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * RFC-0031 / D31: [McpDescriptorHash] is the security-critical piece of per-operation adoption —
 * a wrong answer here either re-prompts forever (harmless but user-hostile) or, the direction that
 * matters, fails to change when it should and lets a widened operation ride on an old adoption.
 */
class McpDescriptorHashTest {

    private fun schema(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject {
        for ((k, v) in pairs) put(k, v)
    }

    @Test
    fun `hash is stable under JsonObject key reordering`() {
        val a = schema("repo" to "string", "state" to "string")
        val b = schema("state" to "string", "repo" to "string")

        val hashA = McpDescriptorHash.hash("list_issues", "Lists issues", a)
        val hashB = McpDescriptorHash.hash("list_issues", "Lists issues", b)

        assertEquals(hashA, hashB, "key order must not affect the hash")
    }

    @Test
    fun `hash is stable under key reordering in nested objects`() {
        val a = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("repo", buildJsonObject { put("type", "string"); put("minLength", "1") })
                    put("state", buildJsonObject { put("type", "string") })
                },
            )
        }
        val b = buildJsonObject {
            put(
                "properties",
                buildJsonObject {
                    put("state", buildJsonObject { put("type", "string") })
                    put("repo", buildJsonObject { put("minLength", "1"); put("type", "string") })
                },
            )
            put("type", "object")
        }

        assertEquals(
            McpDescriptorHash.hash("op", "desc", a),
            McpDescriptorHash.hash("op", "desc", b),
        )
    }

    @Test
    fun `hash is stable under whitespace differences in the source JSON text`() {
        val compact = Json.parseToJsonElement("""{"repo":"string","state":"string"}""") as JsonObject
        val spread = Json.parseToJsonElement(
            """
            {
                "repo"  :   "string" ,
                "state" :   "string"
            }
            """.trimIndent(),
        ) as JsonObject

        assertEquals(
            McpDescriptorHash.hash("op", "desc", compact),
            McpDescriptorHash.hash("op", "desc", spread),
        )
    }

    @Test
    fun `different names produce different hashes`() {
        val s = schema("a" to "string")
        assertNotEquals(
            McpDescriptorHash.hash("op_one", "same description", s),
            McpDescriptorHash.hash("op_two", "same description", s),
        )
    }

    @Test
    fun `different descriptions produce different hashes`() {
        val s = schema("a" to "string")
        assertNotEquals(
            McpDescriptorHash.hash("op", "reads a file", s),
            McpDescriptorHash.hash("op", "deletes a file", s),
        )
    }

    @Test
    fun `different schemas produce different hashes`() {
        assertNotEquals(
            McpDescriptorHash.hash("op", "desc", schema("a" to "string")),
            McpDescriptorHash.hash("op", "desc", schema("a" to "string", "b" to "string")),
        )
    }

    @Test
    fun `a widened schema with an unchanged description changes the hash`() {
        // This is the attack the DDL comment names by name: "a constant description over a
        // widened parameter is a real attack." A server that starts accepting an extra parameter
        // (e.g. adding a write-capable field) while leaving its prose untouched must not keep
        // riding a previously adopted hash.
        val narrow = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject { put("path", buildJsonObject { put("type", "string") }) })
            put("required", buildJsonObject { })
        }
        val widened = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                buildJsonObject {
                    put("path", buildJsonObject { put("type", "string") })
                    put("recursive", buildJsonObject { put("type", "boolean") })
                },
            )
            put("required", buildJsonObject { })
        }
        val description = "Reads a file from the project."

        assertNotEquals(
            McpDescriptorHash.hash("read_file", description, narrow),
            McpDescriptorHash.hash("read_file", description, widened),
            "a widened input schema must change the hash even when the description is untouched",
        )
    }

    @Test
    fun `hash(spec) matches hash(name, description, inputSchema) for the same values`() {
        val s = schema("q" to "string")
        val spec = McpToolSpec(name = "search", description = "Full text search", inputSchema = s)

        assertEquals(
            McpDescriptorHash.hash("search", "Full text search", s),
            McpDescriptorHash.hash(spec),
        )
    }

    @Test
    fun `hash is stable across repeated calls`() {
        val s = schema("x" to "string")
        val first = McpDescriptorHash.hash("op", "desc", s)
        val second = McpDescriptorHash.hash("op", "desc", s)
        assertEquals(first, second)
    }
}
