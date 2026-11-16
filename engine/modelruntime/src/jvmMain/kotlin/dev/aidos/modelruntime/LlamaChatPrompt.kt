package dev.aidos.modelruntime

import net.ladenthin.llama.LlamaModel
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.Turn
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Turns a [ModelRequest] into the prompt text a llama.cpp model is given (RFC-0022, "Amendment
 * 2026-11-16: prompt formatting and tool calling"; RFC-0021 for the tool protocol). Shared by the
 * desktop and Android adapters.
 *
 * A chat model is trained on one exact framing of a conversation, and its GGUF carries that
 * framing as a chat template. The adapters used to write `User:` / `Assistant:` lines instead,
 * which no model was trained on: instruction-tuned models answered worse, ran past the end of
 * their turn, or continued the dialogue themselves. The prompt is now rendered by llama.cpp from
 * the model's own template.
 *
 * Tools are described in the system turn in the one call syntax [ToolCallParser] reads, rather
 * than in each model family's own tool syntax: the binding returns raw text, so a family-specific
 * call could not be parsed back. When the caller requires a call, [grammar] constrains the output
 * to that syntax; when calls are optional the model must stay free to answer in prose, so the
 * instructions alone ask for it.
 */
object LlamaChatPrompt {

    /**
     * The prompt for [request]. [chatTemplate] is the template the GGUF declares, or null. A model
     * that declares none has no notion of roles, so it gets the content verbatim: any framing
     * would be text the caller did not send, and with a base completion model would decide what
     * it predicts next.
     */
    fun render(model: LlamaModel, chatTemplate: String?, request: ModelRequest): String {
        if (chatTemplate == null) return rawPrompt(request.messages)
        return try {
            model.applyTemplate(templateRequestJson(request))
        } catch (e: Exception) {
            // A template llama.cpp cannot evaluate must not make the model unusable. The labelled
            // form is a poor substitute, so say that it is in use.
            System.err.println("Chat template could not be applied (${e.message}); using role-labelled prompt")
            labelledPrompt(request)
        }
    }

    /** The `{"messages": [...]}` object llama.cpp's template engine takes, as JSON text. */
    fun templateRequestJson(request: ModelRequest): String {
        val tools = offeredTools(request)
        // One leading system message: several templates accept only that, and reject or drop a
        // system message anywhere else.
        val system = buildList {
            request.messages.filterIsInstance<Turn.System>().forEach { if (it.content.isNotBlank()) add(it.content) }
            if (tools.isNotEmpty()) add(toolInstructions(tools, request.toolChoice))
        }.joinToString("\n\n")

        val messages = buildJsonArray {
            if (system.isNotEmpty()) add(message("system", system))
            for (turn in request.messages) {
                when (turn) {
                    is Turn.System -> Unit
                    is Turn.User -> add(message("user", turn.content.joinToString("") { renderBlock(it) }))
                    is Turn.Assistant -> add(message("assistant", assistantText(turn)))
                    is Turn.ToolResult -> add(message("tool", turn.result.content.joinToString("") { renderBlock(it) }))
                }
            }
        }
        return buildJsonObject { put("messages", messages) }.toString()
    }

    /**
     * The grammar that forces a tool call, or null when the model may answer in prose
     * ([ToolChoice.Auto]) or no tools are offered.
     */
    fun grammar(request: ModelRequest): String? = when (request.toolChoice) {
        is ToolChoice.Required, is ToolChoice.Specific -> GbnfGrammarCompiler.compile(offeredTools(request))
        else -> null
    }

    /** The tools the model may call: none under [ToolChoice.None], one under [ToolChoice.Specific]. */
    fun offeredTools(request: ModelRequest): List<ToolDescriptor> = when (val choice = request.toolChoice) {
        is ToolChoice.None -> emptyList()
        is ToolChoice.Specific -> request.tools.filter { it.name == choice.toolName }
        else -> request.tools
    }

    /** [text] without the tool calls found in it, so a call is not also shown to the user as prose. */
    fun textWithoutCalls(text: String, calls: List<ToolCall>, constrained: Boolean): String {
        if (calls.isEmpty()) return text
        if (constrained) return ""
        return calls.fold(text) { remaining, call -> call.rawText?.let { remaining.replace(it, "") } ?: remaining }.trim()
    }

    internal fun toolInstructions(tools: List<ToolDescriptor>, choice: ToolChoice): String = buildString {
        append("You can call tools. To call a tool, reply with only a JSON object in exactly this form:\n")
        append("{\"tool\": \"<tool name>\", \"args\": {<arguments as JSON>}}\n")
        append("Write one such object per call and nothing else, then stop and wait for the tool result. ")
        append(
            when (choice) {
                is ToolChoice.Required -> "You must call a tool now."
                is ToolChoice.Specific -> "You must call the tool ${choice.toolName} now."
                else -> "If no tool is needed, answer normally and do not write such an object."
            }
        )
        append("\n\nAvailable tools:")
        for (tool in tools) {
            append("\n- ").append(tool.name).append(": ").append(tool.description)
            append("\n  arguments (JSON Schema): ").append(tool.inputSchema.toString())
        }
    }

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    /** An earlier assistant turn, with its tool calls in the syntax the instructions ask for. */
    private fun assistantText(turn: Turn.Assistant): String = buildList {
        turn.text?.takeIf { it.isNotEmpty() }?.let { add(it) }
        turn.toolCalls.forEach { call ->
            add(buildJsonObject { put("tool", JsonPrimitive(call.toolName)); put("args", call.arguments) }.toString())
        }
    }.joinToString("\n")

    /** Content only, in order, with nothing the caller did not supply. */
    internal fun rawPrompt(turns: List<Turn>): String = buildString {
        for (turn in turns) {
            when (turn) {
                is Turn.System -> append(turn.content)
                is Turn.User -> turn.content.forEach { append(renderBlock(it)) }
                is Turn.Assistant -> turn.text?.let { append(it) }
                is Turn.ToolResult -> turn.result.content.forEach { append(renderBlock(it)) }
            }
        }
    }

    /** Fallback only: used when the model's template cannot be evaluated. */
    internal fun labelledPrompt(request: ModelRequest): String = buildString {
        val tools = offeredTools(request)
        for (turn in request.messages) {
            when (turn) {
                is Turn.System -> append(turn.content).append("\n\n")
                is Turn.User -> append("User: ").append(turn.content.joinToString("") { renderBlock(it) }).append("\n")
                is Turn.Assistant -> append("Assistant: ").append(assistantText(turn)).append("\n")
                is Turn.ToolResult ->
                    append("Tool result: ").append(turn.result.content.joinToString("") { renderBlock(it) }).append("\n")
            }
        }
        if (tools.isNotEmpty()) append(toolInstructions(tools, request.toolChoice)).append("\n\n")
        append("Assistant:")
    }

    private fun renderBlock(block: ContentBlock): String = when (block) {
        is ContentBlock.Text -> block.text
        is ContentBlock.Image -> "[Image: ${block.mimeType}]"
        is ContentBlock.Audio -> "[Audio: ${block.mimeType}]"
        is ContentBlock.ResourceRef -> "[Resource: ${block.nodeId}, ${block.sizeBytes} bytes]"
    }
}
