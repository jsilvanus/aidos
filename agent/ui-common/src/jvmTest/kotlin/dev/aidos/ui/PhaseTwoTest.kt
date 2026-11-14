package dev.aidos.ui

import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentMinLocality
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.ui.pills.AgentPillState
import dev.aidos.ui.pills.PillRole
import dev.aidos.ui.tcr.code.DiffLine
import dev.aidos.ui.tcr.code.DiffLineType
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Test
import kotlin.system.measureTimeMillis
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Serializable
data class A2UiAtomSchema(
    val atomType: String,
    val properties: Map<String, String>,
)

class PhaseTwoTest {

    @Test
    fun testPillRoleIdentitiesAndOverlayState() {
        val pills = listOf(
            AgentPillState(
                id = "pill-red",
                role = PillRole.ORCHESTRATOR_RED,
                label = "Dictator Caret",
                xNormalized = 0.2f,
                yNormalized = 0.3f,
                ghostText = "Propose new task",
            ),
            AgentPillState(
                id = "pill-blue",
                role = PillRole.WORKER_CODE_BLUE,
                label = "Code Worker",
                activeTaskId = "task-42",
                xNormalized = 0.5f,
                yNormalized = 0.5f,
                isRadiatingWave = true,
            ),
            AgentPillState(
                id = "pill-green",
                role = PillRole.VERIFIER_GREEN,
                label = "Verifier",
                activeTaskId = "verify-42",
                xNormalized = 0.8f,
                yNormalized = 0.5f,
            ),
            AgentPillState(
                id = "pill-violet",
                role = PillRole.RESEARCH_VIOLET,
                label = "Researcher",
                xNormalized = 0.9f,
                yNormalized = 0.8f,
            ),
        )

        assertEquals(4, pills.size)
        assertEquals("🔴", PillRole.ORCHESTRATOR_RED.iconEmoji)
        assertEquals("🔵", PillRole.WORKER_CODE_BLUE.iconEmoji)
        assertEquals("🟢", PillRole.VERIFIER_GREEN.iconEmoji)
        assertEquals("🟣", PillRole.RESEARCH_VIOLET.iconEmoji)
    }

    @Test
    fun testDictatorGhostTextStreamSimulation() {
        val whisperTokenChunks = listOf(
            "Add ",
            "Add authentication ",
            "Add authentication test",
        )

        var streamedBuffer = ""
        val committedTexts = mutableListOf<String>()

        whisperTokenChunks.forEach { tokenDelta ->
            streamedBuffer = tokenDelta
            assertTrue(streamedBuffer.isNotBlank())
        }

        // Final commit
        committedTexts.add(streamedBuffer.trim())
        assertEquals("Add authentication test", committedTexts.first())
    }

    @Test
    fun testTcrA2UiJsonSchemaParsingLatencyBenchmark() {
        val json = Json { ignoreUnknownKeys = true }

        val schemas = listOf(
            A2UiAtomSchema(
                atomType = "TextSpanAtom",
                properties = mapOf("text" to "fun execute()", "kind" to "CODE_SNIPPET")
            ),
            A2UiAtomSchema(
                atomType = "ActionButtonAtom",
                properties = mapOf("text" to "Deploy", "kind" to "PRIMARY")
            ),
            A2UiAtomSchema(
                atomType = "StepCounterAtom",
                properties = mapOf("currentStep" to "3", "totalSteps" to "5")
            ),
            A2UiAtomSchema(
                atomType = "ScopePathChipAtom",
                properties = mapOf("path" to "agent/api/RuntimeClient.kt", "isLocked" to "true")
            ),
        )

        val serialized = json.encodeToString(schemas)

        // Benchmark 1,000 parses to ensure < 5ms per parse
        val elapsedMs = measureTimeMillis {
            repeat(1000) {
                val parsed = json.decodeFromString<List<A2UiAtomSchema>>(serialized)
                assertEquals(4, parsed.size)
            }
        }

        val avgLatencyPerParseMs = elapsedMs / 1000.0
        assertTrue(avgLatencyPerParseMs < 5.0, "Parsing latency too high: $avgLatencyPerParseMs ms")
    }

    @Test
    fun testDiffChunkModel() {
        val lines = listOf(
            DiffLine(type = DiffLineType.CONTEXT, content = "class RuntimeClient {"),
            DiffLine(type = DiffLineType.DELETION, content = "    fun oldMethod()", oldLineNumber = 12),
            DiffLine(type = DiffLineType.ADDITION, content = "    fun newMethod()", newLineNumber = 12),
            DiffLine(type = DiffLineType.CONTEXT, content = "}"),
        )

        assertEquals(4, lines.size)
        assertEquals(DiffLineType.DELETION, lines[1].type)
        assertEquals(DiffLineType.ADDITION, lines[2].type)
    }
}
