package dev.aidos.modelruntime

import dev.aidos.kernel.AvailabilityTier
import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.EffectKind
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelStreamEvent
import dev.aidos.kernel.Permission
import dev.aidos.kernel.PlatformProfile
import dev.aidos.kernel.RecoveryClass
import dev.aidos.kernel.TextOutput
import dev.aidos.kernel.ToolAvailability
import dev.aidos.kernel.ToolCall
import dev.aidos.kernel.ToolCallResult
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.ToolOutcome
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** RFC-0021: prompts use the model's chat template, and tools reach the model in a syntax that is parsed back. */
class LlamaChatPromptTest {

    private fun user(text: String) = Turn.User(listOf(ContentBlock.Text(text)), TrustLevel.TRUSTED)

    private fun tool(name: String) = ToolDescriptor(
        name = name,
        title = name,
        description = "Reads the clock",
        inputSchema = buildJsonObject { put("type", "object") },
        effect = EffectKind.Read,
        requiredPermission = Permission.FS_READ,
        recoveryClass = RecoveryClass.PURE,
        availability = ToolAvailability(profiles = PlatformProfile.entries.toSet(), tier = AvailabilityTier.UNIVERSAL),
    )

    private fun request(
        messages: List<Turn>,
        tools: List<ToolDescriptor> = emptyList(),
        choice: ToolChoice = if (tools.isEmpty()) ToolChoice.None else ToolChoice.Auto,
    ) = ModelRequest(messages = messages, tools = tools, toolChoice = choice, maxOutputTokens = 8)

    private fun roles(json: String): List<Pair<String, String>> =
        Json.parseToJsonElement(json).jsonObject.getValue("messages").jsonArray.map {
            it.jsonObject.getValue("role").jsonPrimitive.content to it.jsonObject.getValue("content").jsonPrimitive.content
        }

    @Test
    fun templateRequest_keepsRolesAndMergesSystemTurnsIntoOneLeadingMessage() {
        val json = LlamaChatPrompt.templateRequestJson(
            request(listOf(Turn.System("Be brief."), user("Hi"), Turn.Assistant("Hello", emptyList()), Turn.System("Use Finnish."), user("Bye")))
        )

        assertEquals(
            listOf("system" to "Be brief.\n\nUse Finnish.", "user" to "Hi", "assistant" to "Hello", "user" to "Bye"),
            roles(json),
        )
    }

    @Test
    fun templateRequest_describesToolsInTheSyntaxTheParserReads() {
        val json = LlamaChatPrompt.templateRequestJson(request(listOf(user("What time is it?")), tools = listOf(tool("clock.now"))))

        val system = roles(json).first()
        assertEquals("system", system.first)
        assertTrue(system.second.contains("{\"tool\": \"<tool name>\", \"args\""))
        assertTrue(system.second.contains("clock.now: Reads the clock"))
        // Optional calls: the model must be told it may simply answer.
        assertTrue(system.second.contains("answer normally"))
    }

    @Test
    fun templateRequest_replaysEarlierCallsAndResultsSoTheModelSeesTheWholeExchange() {
        val call = ToolCall("c1", "clock.now", buildJsonObject { put("tz", "Europe/Helsinki") }, null, null)
        val result = ToolCallResult("c1", ToolOutcome.Ok, listOf(ContentBlock.Text("12:00")), TrustLevel.UNTRUSTED)
        val json = LlamaChatPrompt.templateRequestJson(
            request(listOf(user("Time?"), Turn.Assistant(null, listOf(call)), Turn.ToolResult(result)), tools = listOf(tool("clock.now")))
        )

        val messages = roles(json)
        assertEquals("assistant" to "{\"tool\":\"clock.now\",\"args\":{\"tz\":\"Europe/Helsinki\"}}", messages[2])
        assertEquals("tool" to "12:00", messages[3])
        // What the model is shown for an earlier call is what the parser accepts as a call.
        assertEquals(1, ToolCallParser.parse(messages[2].second, listOf(tool("clock.now"))).size)
    }

    @Test
    fun toolChoice_decidesWhichToolsAreOfferedAndWhetherACallIsForced() {
        val tools = listOf(tool("clock.now"), tool("fs.read"))
        val turns = listOf(user("x"))

        assertNull(LlamaChatPrompt.grammar(request(turns, tools, ToolChoice.Auto)), "optional calls leave prose possible")
        assertNotNull(LlamaChatPrompt.grammar(request(turns, tools, ToolChoice.Required)))

        val specific = request(turns, tools, ToolChoice.Specific("fs.read"))
        assertEquals(listOf("fs.read"), LlamaChatPrompt.offeredTools(specific).map { it.name })
        val grammar = assertNotNull(LlamaChatPrompt.grammar(specific))
        assertTrue(grammar.contains("\"fs.read\""))
        assertFalse(grammar.contains("clock.now"))

        val none = request(turns, tools, ToolChoice.None)
        assertTrue(LlamaChatPrompt.offeredTools(none).isEmpty())
        assertFalse(LlamaChatPrompt.templateRequestJson(none).contains("clock.now"))
    }

    @Test
    fun textWithoutCalls_removesTheCallFromWhatTheUserReads() {
        val tools = listOf(tool("clock.now"))
        val output = "Let me check.\n{\"tool\": \"clock.now\", \"args\": {}}"
        val calls = ToolCallParser.parse(output, tools)

        assertEquals(1, calls.size)
        assertEquals("Let me check.", LlamaChatPrompt.textWithoutCalls(output, calls, constrained = false))
        assertEquals("plain", LlamaChatPrompt.textWithoutCalls("plain", emptyList(), constrained = false))
    }

    // ---- against llama.cpp itself, with the checked-in echo fixture ----

    private fun echoFixture(): File =
        generateSequence(File(System.getProperty("user.dir")).absoluteFile) { it.parentFile }
            .map { File(it, "models/echo-gguf/echo.gguf") }.firstOrNull { it.isFile } ?: error("echo.gguf fixture not found")

    /** A Llama 3 style template: distinctive markers, and a generation prompt that ends in a newline. */
    private val template = "{% for m in messages %}<|start_header_id|>{{ m['role'] }}<|end_header_id|>\n\n{{ m['content'] }}<|eot_id|>" +
        "{% endfor %}{% if add_generation_prompt %}<|start_header_id|>assistant<|end_header_id|>\n\n{% endif %}"

    private fun adapter(chatTemplate: String?): LlamaCppAdapter {
        val file = echoFixture()
        val metadata = GgufLoader.loadMetadata(file) ?: error("fixture metadata")
        return LlamaCppAdapter(
            "echo", file, metadata, contextSize = 512, threads = 1,
            settings = ModelRunSettings(temperature = 0f), chatTemplateOverride = chatTemplate,
        )
    }

    @Test
    fun adapter_rendersThePromptWithTheModelsChatTemplate() = runTest {
        val adapter = adapter(template)
        try {
            val request = request(listOf(Turn.System("Be brief."), user("Hi")))

            assertEquals(
                "<|start_header_id|>system<|end_header_id|>\n\nBe brief.<|eot_id|>" +
                    "<|start_header_id|>user<|end_header_id|>\n\nHi<|eot_id|>" +
                    "<|start_header_id|>assistant<|end_header_id|>\n\n",
                adapter.renderPrompt(request),
            )
            assertFalse(adapter.renderPrompt(request).contains("User:"))

            // The echo model repeats the last prompt byte, so its output shows which prompt it
            // was really run with: the template's generation prompt ends in a newline.
            val done = adapter.invokeStreaming(request).toList().last()
            val text = assertIs<ModelStreamEvent.Done>(done).response.outputs.filterIsInstance<TextOutput>().single().text
            assertEquals("\n".repeat(8), text)
        } finally {
            adapter.close()
        }
    }

    @Test
    fun adapter_putsToolInstructionsInsideTheTemplatedSystemTurn() {
        val adapter = adapter(template)
        try {
            val prompt = adapter.renderPrompt(request(listOf(user("Time?")), tools = listOf(tool("clock.now"))))

            assertTrue(prompt.startsWith("<|start_header_id|>system<|end_header_id|>\n\nYou can call tools."))
            assertTrue(prompt.contains("clock.now: Reads the clock"))
        } finally {
            adapter.close()
        }
    }

    @Test
    fun adapter_givesAModelWithoutATemplateItsContentVerbatim() {
        val adapter = adapter(null)
        try {
            assertEquals("Be brief.Hi", adapter.renderPrompt(request(listOf(Turn.System("Be brief."), user("Hi")))))
        } finally {
            adapter.close()
        }
    }
}
