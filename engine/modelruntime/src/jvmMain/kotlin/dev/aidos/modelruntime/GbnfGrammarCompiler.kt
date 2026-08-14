package dev.aidos.modelruntime

import dev.aidos.kernel.ToolDescriptor
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * Compiles GBNF (GGML BNF) grammars from tool descriptors for constrained decoding (RFC-0021).
 *
 * GBNF is llama.cpp's grammar format that constrains model output to valid tool calls.
 * This allows models without native tool-calling support to still participate in the agent loop
 * (RFC-0008) by enforcing well-formed JSON tool calls via the model's sampling constraints.
 *
 * Grammar structure:
 * - root: Produces an array of tool calls
 * - tool-call: A single tool call (name + arguments as JSON)
 * - tool-name: One of the available tool names
 * - arguments: A valid JSON object (reuses standard JSON GBNF)
 *
 * Reference: https://github.com/ggerganov/llama.cpp/blob/master/grammars/json.gbnf
 */
object GbnfGrammarCompiler {

    /**
     * Compile tool descriptors to GBNF grammar for constrained decoding.
     *
     * Returns a GBNF string that constrains the model to generate valid tool calls.
     * Tools are listed by name (e.g., "git.commit", "fs.read"), and arguments must be
     * valid JSON matching the tool's inputSchema.
     *
     * For M22, this implements basic JSON structure enforcement. Full schema validation
     * (verifying argument types match the schema) is future work; the grammar here ensures
     * only *well-formed* JSON is generated, not that it *satisfies* the schema.
     *
     * @param tools List of available tools from the model request.
     * @return GBNF grammar string, or null if tools is empty or grammar compilation fails.
     */
    fun compile(tools: List<ToolDescriptor>): String? {
        if (tools.isEmpty()) return null

        try {
            val toolNames = tools.map { it.name }
            return buildGrammar(toolNames)
        } catch (e: Exception) {
            // Log but don't throw; parsing should be best-effort
            System.err.println("GBNF compilation failed: ${e.message}")
            return null
        }
    }

    /**
     * Build the complete GBNF grammar.
     *
     * The grammar allows multiple tool calls to be output (for multi-step planning),
     * each with a tool name and JSON arguments. This mirrors the structure that
     * extractToolCalls expects to parse.
     */
    private fun buildGrammar(toolNames: List<String>): String {
        val toolNamePattern = toolNames.joinToString(" | ") { "\"$it\"" }

        return """
            # GBNF grammar for tool calling (RFC-0021, M22)
            # Constrains model output to valid tool calls for local models without native function calling.
            
            root     ::= tool-call+
            
            tool-call ::= ws "{" ws
                             "\"tool\"" ws ":" ws tool-name ws "," ws
                             "\"args\"" ws ":" ws value
                         ws "}" ws
            
            tool-name ::= $toolNamePattern
            
            # JSON structure (simplified; full validation happens post-parse)
            value    ::= object | array | string | number | ("true" | "false" | "null")
            
            object   ::= "{" ws (string ws ":" ws value (ws "," ws string ws ":" ws value)*)? ws "}"
            
            array    ::= "[" ws (value (ws "," ws value)*)? ws "]"
            
            string   ::= "\"" ([^"\\] | "\\\\" | "\\\"" | "\\/" | "\\b" | "\\f" | "\\n" | "\\r" | "\\t" | "\\u" [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F] [0-9a-fA-F])* "\""
            
            number   ::= ("-"? ([0-9] | [1-9] [0-9]*)) ("." [0-9]+)? ([eE] [-+]? [0-9]+)?
            
            ws       ::= ([ \t\n] ws)?
        """.trimIndent()
    }
}
