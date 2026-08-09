package dev.aidos.modelruntime

import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.Permission
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.ToolAvailability
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.assertFalse

/**
 * Tests for GBNF grammar compilation and tool call parsing (RFC-0021, M22).
 */
class GbnfGrammarAndParsingTest {

    @Test
    fun `GbnfGrammarCompiler produces non-null grammar for multiple tools`() {
        val tools = listOf(
            createToolDescriptor("git.commit"),
            createToolDescriptor("fs.read"),
        )

        val grammar = GbnfGrammarCompiler.compile(tools)

        assertNotNull(grammar, "Grammar should not be null")
        assertTrue(grammar.contains("tool-name"), "Grammar should define tool-name rule")
        assertTrue(grammar.contains("git.commit"), "Grammar should include git.commit tool")
        assertTrue(grammar.contains("fs.read"), "Grammar should include fs.read tool")
    }

    @Test
    fun `GbnfGrammarCompiler returns null for empty tools list`() {
        val grammar = GbnfGrammarCompiler.compile(emptyList())
        assertEquals(null, grammar, "Grammar should be null for empty tools")
    }

    @Test
    fun `GbnfGrammarCompiler handles tool names with dots`() {
        val tools = listOf(
            createToolDescriptor("git.commit"),
            createToolDescriptor("git.push"),
            createToolDescriptor("shell.exec"),
        )

        val grammar = GbnfGrammarCompiler.compile(tools)

        assertNotNull(grammar)
        assertTrue(grammar.contains("git.commit"), "Should handle dotted tool names")
        assertTrue(grammar.contains("git.push"), "Should handle multiple tools with same prefix")
        assertTrue(grammar.contains("shell.exec"), "Should handle shell. prefix")
    }

    @Test
    fun `ToolCallParser extracts single tool call from output`() {
        val tools = listOf(
            createToolDescriptor("git.commit"),
        )

        val output = """
            The commit was successful.
            {"tool": "git.commit", "args": {"message": "Fix bug", "author": "Alice"}}
        """.trimIndent()

        val calls = ToolCallParser.parse(output, tools, isConstrained = false)

        assertEquals(1, calls.size, "Should extract one tool call")
        assertEquals("git.commit", calls[0].toolName, "Tool name should be git.commit")
        assertNotNull(calls[0].arguments, "Arguments should not be null")
        assertNotNull(calls[0].callId, "Call ID should be generated")
        assertNotNull(calls[0].rawText, "Raw text should be retained for heuristic parsing")
    }

    @Test
    fun `ToolCallParser extracts multiple tool calls from output`() {
        val tools = listOf(
            createToolDescriptor("git.commit"),
            createToolDescriptor("fs.read"),
        )

        val output = """
            First, I'll commit the changes.
            {"tool": "git.commit", "args": {"message": "First change"}}
            Then, I'll read a file.
            {"tool": "fs.read", "args": {"path": "/etc/hosts"}}
        """.trimIndent()

        val calls = ToolCallParser.parse(output, tools, isConstrained = false)

        assertEquals(2, calls.size, "Should extract two tool calls")
        assertEquals("git.commit", calls[0].toolName, "First call should be git.commit")
        assertEquals("fs.read", calls[1].toolName, "Second call should be fs.read")
    }

    @Test
    fun `ToolCallParser ignores unrecognized tool names`() {
        val tools = listOf(
            createToolDescriptor("git.commit"),
        )

        val output = """
            {"tool": "unknown.tool", "args": {"x": 1}}
            {"tool": "git.commit", "args": {"message": "Valid"}}
        """.trimIndent()

        val calls = ToolCallParser.parse(output, tools, isConstrained = false)

        assertEquals(1, calls.size, "Should only extract recognized tool")
        assertEquals("git.commit", calls[0].toolName, "Should have extracted the valid tool")
    }

    @Test
    fun `ToolCallParser retains rawText when parsing heuristic`() {
        val tools = listOf(createToolDescriptor("git.commit"))

        val output = """{"tool": "git.commit", "args": {"message": "test"}}"""
        val calls = ToolCallParser.parse(output, tools, isConstrained = false)

        assertEquals(1, calls.size)
        assertNotNull(calls[0].rawText, "Raw text should be retained for audit trail when heuristic")
    }

    @Test
    fun `ToolCallParser omits rawText when constrained decoding is used`() {
        val tools = listOf(createToolDescriptor("git.commit"))

        val output = """{"tool": "git.commit", "args": {"message": "test"}}"""
        val calls = ToolCallParser.parse(output, tools, isConstrained = true)

        assertEquals(1, calls.size)
        assertEquals(null, calls[0].rawText, "Raw text should be null when output was constrained")
    }

    @Test
    fun `ToolCallParser generates unique call IDs`() {
        val tools = listOf(
            createToolDescriptor("git.commit"),
            createToolDescriptor("fs.read"),
        )

        val output = """
            {"tool": "git.commit", "args": {"message": "Change 1"}}
            {"tool": "fs.read", "args": {"path": "/tmp"}}
            {"tool": "git.commit", "args": {"message": "Change 2"}}
        """.trimIndent()

        val calls = ToolCallParser.parse(output, tools, isConstrained = false)

        assertEquals(3, calls.size)
        // All call IDs should be unique
        val ids = calls.map { it.callId }
        assertEquals(ids.size, ids.toSet().size, "All call IDs should be unique")
    }

    @Test
    fun `ToolCallParser handles empty output gracefully`() {
        val tools = listOf(createToolDescriptor("git.commit"))

        val calls = ToolCallParser.parse("", tools, isConstrained = false)
        assertEquals(0, calls.size, "Should return empty list for empty output")

        val calls2 = ToolCallParser.parse("   \n\t   ", tools, isConstrained = false)
        assertEquals(0, calls2.size, "Should return empty list for whitespace-only output")
    }

    @Test
    fun `ToolCallParser handles malformed JSON gracefully`() {
        val tools = listOf(createToolDescriptor("git.commit"))

        val output = """
            This is some text.
            {"tool": "git.commit", "args": {"unclosed": "json}
            More text here.
        """.trimIndent()

        val calls = ToolCallParser.parse(output, tools, isConstrained = false)
        // Should not crash; malformed JSON is skipped, returning empty list
        assertEquals(0, calls.size, "Should handle malformed JSON gracefully")
    }

    @Test
    fun `ToolCallParser extracts arguments as JSON object`() {
        val tools = listOf(createToolDescriptor("git.commit"))

        val output = """{"tool": "git.commit", "args": {"message": "Test", "skip": true}}"""

        val calls = ToolCallParser.parse(output, tools, isConstrained = false)

        assertEquals(1, calls.size)
        assertNotNull(calls[0].arguments, "Arguments should be parsed")
        // The arguments should be a JsonObject with key-value pairs
        assertTrue(calls[0].arguments.containsKey("message"), "Should have message argument")
        assertTrue(calls[0].arguments.containsKey("skip"), "Should have skip argument")
    }

    @Test
    fun `ToolCallParser sets capabilityId to null (resolved by agent loop)`() {
        val tools = listOf(createToolDescriptor("git.commit"))

        val output = """{"tool": "git.commit", "args": {"message": "Test"}}"""
        val calls = ToolCallParser.parse(output, tools, isConstrained = false)

        assertEquals(1, calls.size)
        assertEquals(null, calls[0].capabilityId, "Capability ID should be null; resolved by agent loop")
    }

    @Test
    fun `GbnfGrammarCompiler produces grammar with JSON rule`() {
        val tools = listOf(createToolDescriptor("git.commit"))
        val grammar = GbnfGrammarCompiler.compile(tools)

        assertNotNull(grammar)
        assertTrue(grammar.contains("object"), "Grammar should include JSON object rule")
        assertTrue(grammar.contains("string"), "Grammar should include JSON string rule")
        assertTrue(grammar.contains("value"), "Grammar should include JSON value rule")
    }

    // Helper function to create a minimal tool descriptor
    private fun createToolDescriptor(name: String): ToolDescriptor {
        val schema: JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {})
        }

        return ToolDescriptor(
            name = name,
            title = name,
            description = "Test tool: $name",
            inputSchema = schema,
            effect = EffectKind.Query,
            requiredPermission = Permission.ReadOnly,
            recoveryClass = RecoveryClass.SAFE,
            availability = ToolAvailability.Everywhere,
        )
    }
}
