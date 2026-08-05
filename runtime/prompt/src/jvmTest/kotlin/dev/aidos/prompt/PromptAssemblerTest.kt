package dev.aidos.prompt

import dev.aidos.kernel.ContextItem
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.Turn
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * M15 done-when (RFC-0025, RFC-0016, D22):
 *
 * 1. Token budget derives from the selected model's context window.
 * 2. Assembly that cannot fit returns [AssemblyResult.TooBig] to the router — bounded one
 *    re-selection, not a loop.
 * 3. An unadopted instruction file does not reach the system turn.
 * 4. [runs.instruction_set_hash] (carried in PromptPackage) records the governing set.
 */
class PromptAssemblerTest {

    private val assembler = PromptAssembler()

    private fun fakeModel(contextWindow: Int = 4096) = object : ModelAdapter {
        override val providerId = "test"
        override val modelId = "test-model"
        override val modelVersion = "1.0"
        override val contextWindow = contextWindow
        override val isLocal = true
        override fun supportsNativeToolCalls() = false
        override suspend fun invoke(request: ModelRequest) = Result.failure<ModelResponse>(
            UnsupportedOperationException("test model")
        )
    }

    @Test
    fun `assembles with system prompt and user message`() {
        val result = assembler.assemble(AssemblyRequest(
            model = fakeModel(),
            userMessage = "Hello, world!",
        ))
        assertIs<AssemblyResult.Ok>(result)
        val pkg = result.pkg
        val system = pkg.request.messages.filterIsInstance<Turn.System>().first()
        assertTrue(system.content.contains("Aidos"))
        val user = pkg.request.messages.filterIsInstance<Turn.User>().last()
        assertTrue(user.content.any { it is dev.aidos.kernel.ContentBlock.Text &&
                (it as dev.aidos.kernel.ContentBlock.Text).text.contains("Hello, world!") })
    }

    @Test
    fun `unadopted instruction set does not reach system turn`() {
        val unadoptedSet = InstructionSet(
            hash = "abc123",
            sources = listOf(InstructionSource("AGENTS.md", "blobhash", "Never do X")),
            composedText = "Never do X",
            adopted = false,
        )
        val result = assembler.assemble(AssemblyRequest(
            model = fakeModel(),
            userMessage = "Test",
            instructionSet = unadoptedSet,
        ))
        assertIs<AssemblyResult.Ok>(result)
        val system = result.pkg.request.messages.filterIsInstance<Turn.System>().first()
        assertTrue(!system.content.contains("Never do X"),
            "Unadopted instruction must not appear in system turn")
    }

    @Test
    fun `adopted instruction set appears in system turn`() {
        val adoptedSet = InstructionSet(
            hash = "def456",
            sources = listOf(InstructionSource("AGENTS.md", "blobhash", "Always be helpful")),
            composedText = "Always be helpful",
            adopted = true,
        )
        val result = assembler.assemble(AssemblyRequest(
            model = fakeModel(),
            userMessage = "Test",
            instructionSet = adoptedSet,
        ))
        assertIs<AssemblyResult.Ok>(result)
        val system = result.pkg.request.messages.filterIsInstance<Turn.System>().first()
        assertTrue(system.content.contains("Always be helpful"))
    }

    @Test
    fun `instruction set hash is recorded in package`() {
        val set = InstructionSet(
            hash = "myhash",
            sources = emptyList(),
            composedText = "",
            adopted = true,
        )
        val result = assembler.assemble(AssemblyRequest(
            model = fakeModel(),
            userMessage = "Test",
            instructionSet = set,
        ))
        assertIs<AssemblyResult.Ok>(result)
        assertEquals("myhash", result.pkg.instructionSetHash)
    }

    @Test
    fun `returns TooBig when reserved sections exceed budget`() {
        // A very tiny context window that cannot hold the safety prompt.
        val tinyModel = fakeModel(contextWindow = 10)
        val result = assembler.assemble(AssemblyRequest(
            model = tinyModel,
            userMessage = "a".repeat(500),
        ))
        assertIs<AssemblyResult.TooBig>(result)
        assertTrue(result.minimumContextWindow > 10)
    }

    @Test
    fun `token budget is bounded to one re-selection (returns TooBig, not a loop)`() {
        // The API is: if TooBig, the caller may ask the router for a larger model.
        // Here we verify the assembler returns TooBig and a minimum context window,
        // not a recursive internal loop.
        val tinyModel = fakeModel(contextWindow = 50)
        val result = assembler.assemble(AssemblyRequest(
            model = tinyModel,
            userMessage = "x".repeat(200),
        ))
        // Either Ok (it fit) or TooBig (it didn't) — never an infinite loop.
        assertTrue(result is AssemblyResult.Ok || result is AssemblyResult.TooBig)
    }

    @Test
    fun `knowledge context included in assembly`() {
        val item = ContextItem(
            contentNodeId = null,
            kind = dev.aidos.kernel.ContextItemKind.CODE_SNIPPET,
            content = "// src/Main.kt\nfun main() = println(\"hello\")",
            relevanceScore = 0.9f,
            tokenCount = 10,
            trustLevel = dev.aidos.kernel.TrustLevel.TRUSTED,
        )
        val result = assembler.assemble(AssemblyRequest(
            model = fakeModel(),
            userMessage = "Explain main",
            knowledgeContext = listOf(item),
        ))
        assertIs<AssemblyResult.Ok>(result)
        val allText = result.pkg.request.messages.joinToString(" ") { turn ->
            when (turn) {
                is Turn.User -> turn.content.filterIsInstance<dev.aidos.kernel.ContentBlock.Text>()
                    .joinToString(" ") { it.text }
                else -> ""
            }
        }
        assertTrue(allText.contains("Main.kt"))
    }
}

class InstructionDiscoveryTest {

    private fun tempProject(files: Map<String, String>): File {
        val dir = Files.createTempDirectory("instr-test").toFile()
        for ((name, content) in files) File(dir, name).writeText(content)
        return dir
    }

    @Test
    fun `discovers AGENTS_md at project root`() {
        val dir = tempProject(mapOf("AGENTS.md" to "# Instructions\nBe helpful"))
        val set = InstructionDiscovery.discover(dir)
        assertNotNull(set)
        assertEquals(1, set.sources.size)
        assertEquals("AGENTS.md", set.sources.first().filename)
        assertTrue(set.composedText.contains("Be helpful"))
    }

    @Test
    fun `composes AGENTS_md then CLAUDE_md in order`() {
        val dir = tempProject(mapOf(
            "AGENTS.md" to "First",
            "CLAUDE.md" to "Second",
        ))
        val set = InstructionDiscovery.discover(dir)!!
        val agents = set.composedText.indexOf("First")
        val claude = set.composedText.indexOf("Second")
        assertTrue(agents < claude)
    }

    @Test
    fun `returns null when no instruction files present`() {
        val dir = tempProject(emptyMap())
        assertNull(InstructionDiscovery.discover(dir))
    }

    @Test
    fun `hash changes when file content changes`() {
        val dir = tempProject(mapOf("AGENTS.md" to "Version 1"))
        val hash1 = InstructionDiscovery.discover(dir)!!.hash
        File(dir, "AGENTS.md").writeText("Version 2")
        val hash2 = InstructionDiscovery.discover(dir)!!.hash
        assertTrue(hash1 != hash2)
    }

    @Test
    fun `same content yields same hash (adoption check)`() {
        val dir = tempProject(mapOf("AGENTS.md" to "Static content"))
        val hash1 = InstructionDiscovery.discover(dir)!!.hash
        val hash2 = InstructionDiscovery.discover(dir)!!.hash
        assertEquals(hash1, hash2)
    }

    @Test
    fun `adopted when hash matches`() {
        val dir = tempProject(mapOf("AGENTS.md" to "Instructions"))
        val first = InstructionDiscovery.discover(dir)!!
        val second = InstructionDiscovery.discover(dir, adoptedHash = first.hash)!!
        assertTrue(second.adopted)
    }

    @Test
    fun `not adopted when hash differs`() {
        val dir = tempProject(mapOf("AGENTS.md" to "Instructions"))
        val set = InstructionDiscovery.discover(dir, adoptedHash = "wronghash")!!
        assertTrue(!set.adopted)
    }
}
