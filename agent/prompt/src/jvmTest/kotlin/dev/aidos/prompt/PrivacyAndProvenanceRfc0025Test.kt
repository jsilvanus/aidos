package dev.aidos.prompt

import dev.aidos.kernel.ActorKind
import dev.aidos.kernel.ContentKind
import dev.aidos.kernel.ContentNode
import dev.aidos.kernel.ContentNodeId
import dev.aidos.kernel.ContentNodeState
import dev.aidos.kernel.ContextItem
import dev.aidos.kernel.ContextItemKind
import dev.aidos.kernel.EgressEligibility
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ModelResponse
import dev.aidos.kernel.MutabilityPolicy
import dev.aidos.kernel.ProjectId
import dev.aidos.kernel.SensitivityLevel
import dev.aidos.kernel.StorageLocation
import dev.aidos.kernel.TrustLevel
import dev.aidos.kernel.Turn
import kotlinx.datetime.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * RFC-0024 + RFC-0025 gap-closure tests:
 * - PrivacyFilter & live ContentNodeLookup sensitivity/egress enforcement
 * - PromptPackage.provenance population (included, dropped, instruction, memory version, intent)
 * - Conversation history omission marker when turns are dropped
 */
class PrivacyAndProvenanceRfc0025Test {

    private fun fakeModel(isLocal: Boolean = false, contextWindow: Int = 4096) = object : ModelAdapter {
        override val providerId = "test"
        override val modelId = "test-model"
        override val modelVersion = "1.0"
        override val contextWindow = contextWindow
        override val isLocal = isLocal
        override fun supportsNativeToolCalls() = false
        override suspend fun invoke(request: ModelRequest) = Result.failure<ModelResponse>(
            UnsupportedOperationException("test model")
        )
    }

    private fun node(
        id: String,
        sensitivity: SensitivityLevel,
        egress: EgressEligibility,
    ) = ContentNode(
        id = ContentNodeId(id),
        projectId = ProjectId("proj-1"),
        kind = ContentKind.ARCHITECTURE_DOCUMENT,
        name = "doc-$id.md",
        description = null,
        mutabilityPolicy = MutabilityPolicy.VERSIONED,
        sensitivityLevel = sensitivity,
        egressEligibility = egress,
        trustLevel = TrustLevel.PROJECT,
        storageLocation = StorageLocation.FilesystemPath("doc-$id.md", gitTracked = true),
        contentHash = "hash-$id",
        contentType = "text/markdown",
        sizeBytes = 64L,
        createdAt = Instant.parse("2026-08-05T00:00:00Z"),
        createdByKind = ActorKind.USER,
        createdById = "user-1",
        updatedAt = Instant.parse("2026-08-05T00:00:00Z"),
        updatedByKind = null,
        updatedById = null,
        contentVersion = 1,
        rowVersion = 1,
        state = ContentNodeState.ACTIVE,
        tags = emptyList(),
    )

    private fun ctxItem(id: String, text: String) = ContextItem(
        contentNodeId = ContentNodeId(id),
        kind = ContextItemKind.DOCUMENT_SECTION,
        content = text,
        relevanceScore = 0.9f,
        tokenCount = 10,
        trustLevel = TrustLevel.PROJECT,
    )

    @Test
    fun `remote model excludes SECRET, SENSITIVE, INTERNAL, and unapproved REQUIRES_APPROVAL nodes`() {
        val nodes = mutableMapOf(
            "pub" to node("pub", SensitivityLevel.PUBLIC, EgressEligibility.ELIGIBLE),
            "sec" to node("sec", SensitivityLevel.SECRET, EgressEligibility.BLOCKED),
            "sens" to node("sens", SensitivityLevel.SENSITIVE, EgressEligibility.ELIGIBLE),
            "intl" to node("intl", SensitivityLevel.INTERNAL, EgressEligibility.ELIGIBLE),
            "req" to node("req", SensitivityLevel.PUBLIC, EgressEligibility.REQUIRES_APPROVAL),
        )
        val assembler = PromptAssembler(contentNodeLookup = { id -> nodes[id.value] })
        val items = listOf(
            ctxItem("pub", "PUBLIC_TEXT"),
            ctxItem("sec", "SECRET_TEXT"),
            ctxItem("sens", "SENSITIVE_TEXT"),
            ctxItem("intl", "INTERNAL_TEXT"),
            ctxItem("req", "APPROVAL_TEXT"),
        )

        val res = assembler.assemble(
            AssemblyRequest(
                model = fakeModel(isLocal = false),
                userMessage = "summarize",
                knowledgeContext = items,
                instructionNodeIds = listOf("instr-1"),
                sessionMemoryVersion = 7L,
                intentNodeId = "intent-42",
                modelSelectionRationale = "remote-capable",
            )
        )
        assertIs<AssemblyResult.Ok>(res)
        val pkg = res.pkg
        val allText = pkg.request.messages.joinToString("\n") { it.toString() }
        assertTrue(allText.contains("PUBLIC_TEXT"))
        assertFalse(allText.contains("SECRET_TEXT"))
        assertFalse(allText.contains("SENSITIVE_TEXT"))
        assertFalse(allText.contains("INTERNAL_TEXT"))
        assertFalse(allText.contains("APPROVAL_TEXT"))

        assertEquals(listOf("pub"), pkg.provenance.contextNodeIds)
        assertEquals(setOf("sec", "sens", "intl", "req"), pkg.provenance.droppedNodeIds.toSet())
        assertEquals(7L, pkg.provenance.sessionMemoryVersion)
        assertEquals("intent-42", pkg.provenance.intentNodeId)
        assertEquals("remote-capable", pkg.provenance.modelSelectionRationale)

        // Approving "req" admits it on the next assembly.
        val approvedRes = assembler.assemble(
            AssemblyRequest(
                model = fakeModel(isLocal = false),
                userMessage = "summarize",
                knowledgeContext = items,
                approvedNodeIds = setOf(ContentNodeId("req")),
            )
        )
        assertIs<AssemblyResult.Ok>(approvedRes)
        assertEquals(listOf("pub", "req"), approvedRes.pkg.provenance.contextNodeIds)

        // Live lookup: mutating "pub" to SECRET immediately excludes it.
        nodes["pub"] = node("pub", SensitivityLevel.SECRET, EgressEligibility.BLOCKED)
        val afterMutation = assembler.assemble(
            AssemblyRequest(
                model = fakeModel(isLocal = false),
                userMessage = "summarize",
                knowledgeContext = listOf(ctxItem("pub", "PUBLIC_TEXT")),
            )
        )
        assertIs<AssemblyResult.Ok>(afterMutation)
        assertTrue(afterMutation.pkg.provenance.contextNodeIds.isEmpty())
        assertEquals(listOf("pub"), afterMutation.pkg.provenance.droppedNodeIds)
    }

    @Test
    fun `local model includes INTERNAL but still excludes SECRET`() {
        val nodes = mapOf(
            "intl" to node("intl", SensitivityLevel.INTERNAL, EgressEligibility.REQUIRES_APPROVAL),
            "sec" to node("sec", SensitivityLevel.SECRET, EgressEligibility.BLOCKED),
        )
        val assembler = PromptAssembler(contentNodeLookup = { id -> nodes[id.value] })
        val res = assembler.assemble(
            AssemblyRequest(
                model = fakeModel(isLocal = true),
                userMessage = "inspect",
                knowledgeContext = listOf(
                    ctxItem("intl", "INTERNAL_OK"),
                    ctxItem("sec", "SECRET_NEVER"),
                ),
            )
        )
        assertIs<AssemblyResult.Ok>(res)
        assertEquals(listOf("intl"), res.pkg.provenance.contextNodeIds)
        assertEquals(listOf("sec"), res.pkg.provenance.droppedNodeIds)
    }

    @Test
    fun `history truncation inserts explicit omission marker (RFC-0025)`() {
        val assembler = PromptAssembler()
        val history = (1..20).map { i ->
            Turn.Assistant(text = "Turn $i " + "x".repeat(120), toolCalls = emptyList())
        }
        val res = assembler.assemble(
            AssemblyRequest(
                model = fakeModel(contextWindow = 1024),
                userMessage = "latest",
                conversationHistory = history,
            )
        )
        assertIs<AssemblyResult.Ok>(res)
        val marker = res.pkg.request.messages
            .filterIsInstance<Turn.System>()
            .firstOrNull { it.content.contains("earlier conversation turn(s) omitted") }
        assertTrue(marker != null, "Expected an explicit omission marker when history turns are dropped")
    }
}
