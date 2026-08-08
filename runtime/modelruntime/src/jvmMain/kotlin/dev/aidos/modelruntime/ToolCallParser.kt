package dev.aidos.modelruntime

import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolDescriptor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.util.UUID
import kotlin.text.Regex

/**
 * Parses tool calls from model output text (RFC-0008, RFC-0021, M22).
 *
 * When GBNF grammar is enforced during inference, the model output is constrained to valid
 * tool calls. When grammar is not available, this parser applies heuristic text parsing.
 *
 * In both cases, the parser:
 * 1. Extracts tool calls from the output
 * 2. Generates unique call IDs
 * 3. Retains rawText when parsing was heuristic (security-relevant for audit trail)
 *
 * Tool call format (enforced by GBNF grammar, matched by heuristic parser):
 * ```
 * {"tool": "toolName", "args": {...}}
 * ```
 * Multiple calls can appear in a single output, separated by whitespace.
 */
object ToolCallParser {

    /**
     * Parse tool calls from model output.
     *
     * Attempts to extract structured tool calls from the output text. Each call
     * is converted to a ToolCall with the appropriate fields:
     * - callId: Unique identifier, generated if not in output
     * - toolName: Extracted tool name
     * - arguments: JSON arguments for the tool
     * - capabilityId: null (will be resolved by the agent loop, RFC-0008)
     * - rawText: Preserved when parsing was heuristic (not enforced by grammar)
     *
     * When parsing is heuristic (no grammar), rawText is set to the matched text
     * so the audit trail can show what was actually emitted. Security-relevant:
     * a call the runtime *guessed* at is a call whose arguments may not be what
     * the model meant (RFC-0021).
     *
     * @param output The raw output text from the model
     * @param tools The tool descriptors (for validation of tool names)
     * @param isConstrained Whether output was generated under GBNF constraints
     * @return List of parsed ToolCalls, empty if none found or parsing failed
     */
    fun parse(
        output: String,
        tools: List<ToolDescriptor>,
        isConstrained: Boolean = false,
    ): List<ToolCall> {
        if (output.isBlank()) return emptyList()

        val toolNames = tools.map { it.name }.toSet()
        val calls = mutableListOf<ToolCall>()

        try {
            // Find all JSON-like objects that look like tool calls
            // Pattern: {"tool": "...", "args": {...}}
            // This uses a simpler pattern that doesn't require COMMENTS mode
            val pattern = Regex(
                "\\{\\s*\"tool\"\\s*:\\s*\"([^\"]+)\"\\s*,\\s*\"args\"\\s*:\\s*(\\{(?:[^{}]|\\{[^{}]*\\})*\\})\\s*\\}"
            )

            pattern.findAll(output).forEach { match ->
                val toolName = match.groupValues[1]
                val argsJson = match.groupValues[2]

                // Validate tool name exists
                if (!toolNames.contains(toolName)) {
                    // Skip unrecognized tools
                    return@forEach
                }

                try {
                    val parsedArgs = parseJsonToJsonObject(argsJson)
                    if (parsedArgs != null) {
                        val callId = generateCallId()
                        calls.add(
                            ToolCall(
                                callId = callId,
                                toolName = toolName,
                                arguments = parsedArgs,
                                capabilityId = null,
                                rawText = if (isConstrained) null else match.value, // Retain for audit when heuristic
                            )
                        )
                    }
                } catch (e: Exception) {
                    // Skip malformed JSON; parsing is best-effort
                }
            }
        } catch (e: Exception) {
            // Log but don't throw; parsing should be best-effort
            System.err.println("Tool call parsing error: ${e.message}")
        }

        return calls
    }

    /**
     * Generate a unique call ID for a tool call.
     * Uses UUID for uniqueness within a run.
     */
    private fun generateCallId(): String = "call_${UUID.randomUUID()}"

    /**
     * Parse a JSON string to JsonObject using kotlinx.serialization.
     *
     * Uses Json.parseToJsonElement for robust parsing of JSON arguments.
     * Returns a JsonObject, or null if parsing fails.
     *
     * Arguments are not validated against their schema here; that happens
     * in the agent loop (RFC-0008).
     */
    private fun parseJsonToJsonObject(jsonStr: String): JsonObject? {
        return try {
            val element = Json.parseToJsonElement(jsonStr)
            element as? JsonObject
        } catch (e: Exception) {
            // Return null for unparseable JSON; this is expected when parsing heuristically
            null
        }
    }
}
