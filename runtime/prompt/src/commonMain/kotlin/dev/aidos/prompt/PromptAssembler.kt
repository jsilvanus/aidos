package dev.aidos.prompt

import dev.aidos.kernel.ContentBlock
import dev.aidos.kernel.ContextItem
import dev.aidos.kernel.ModelAdapter
import dev.aidos.kernel.ModelRequest
import dev.aidos.kernel.ToolChoice
import dev.aidos.kernel.ToolDescriptor
import dev.aidos.kernel.Turn

// Safety margin reserved for the model's response (RFC-0025).
private const val SAFETY_MARGIN = 256

/**
 * A fully assembled prompt package ready to send to a model (RFC-0025).
 *
 * Assembly is two-phase: the router selects a model, then assembly runs against
 * that model's context window. If the reserved sections do not fit, [AssemblyResult.TooBig]
 * is returned; the caller returns this to the router once for a larger candidate — bounded,
 * not a loop (D22).
 *
 * [instructionSetHash] records which instruction set steered this Run; it is written into
 * `runs.instruction_set_hash` so an audit trail can answer "what instructions governed this?"
 * months later (RFC-0016).
 */
data class PromptPackage(
    val model: ModelAdapter,
    val request: ModelRequest,
    val instructionSetHash: String?,
    val totalTokensEstimate: Int,
)

sealed interface AssemblyResult {
    data class Ok(val pkg: PromptPackage) : AssemblyResult

    /** Reserved sections do not fit. The router may offer a larger-context candidate (RFC-0025). */
    data class TooBig(val minimumContextWindow: Int) : AssemblyResult
}

/**
 * Instruction set for one Run (RFC-0016, M15).
 *
 * The composed set is identified by a hash of the ordered (filename, blobHash) pairs — exact
 * change detection at zero cost, and the audit answer to "what steered this Run?".
 *
 * An [InstructionSet] that was never adopted does not reach the system turn. [adopted] is
 * false until a user has reviewed and accepted it.
 */
data class InstructionSet(
    val hash: String,
    val sources: List<InstructionSource>,
    val composedText: String,
    val adopted: Boolean,
)

data class InstructionSource(
    val filename: String,
    val blobHash: String,
    val content: String,
)

/**
 * Prompt assembler (RFC-0025, M15).
 *
 * Builds a [PromptPackage] for a model call from:
 * - system instructions (safety + runtime + project/session instructions if adopted)
 * - tool descriptors (reserved, filtered by profile)
 * - conversation history (soft-capped, oldest dropped first)
 * - knowledge context (soft-capped, lowest-ranked dropped first)
 * - user message (always included)
 *
 * Token counting uses an estimate: 4 chars ≈ 1 token (conservative). A production
 * implementation would use a model-specific tokenizer; this estimate is a bounded error.
 */
class PromptAssembler {

    fun assemble(request: AssemblyRequest): AssemblyResult {
        val adapter = request.model
        val budget = adapter.contextWindow - adapter.contextWindow / 8 - SAFETY_MARGIN
        // ^^ reserve ~12.5% for the response (model.contextWindow / 8 is a rough maxOutputTokens)

        // ── Reserved sections ──────────────────────────────────────────────────
        val systemParts = mutableListOf<String>()
        systemParts.add(SAFETY_SYSTEM_PROMPT)
        if (request.runtimeInstructions.isNotBlank()) {
            systemParts.add(request.runtimeInstructions)
        }
        // Unadopted instruction sets never reach the system turn (RFC-0016).
        val instructionSet = request.instructionSet
        if (instructionSet != null && instructionSet.adopted) {
            systemParts.add(renderInstructions(instructionSet))
        }
        val systemText = systemParts.joinToString("\n\n")

        // Tool descriptors — reserved, must fit.
        val tools = request.tools

        // Estimate reserved cost.
        val reservedTokens = estimateTokens(systemText) +
                tools.sumOf { estimateTokens(it.description) + estimateTokens(it.name) + 20 } +
                estimateTokens(request.userMessage)

        if (reservedTokens > budget) {
            return AssemblyResult.TooBig(reservedTokens + SAFETY_MARGIN + adapter.contextWindow / 8)
        }

        // ── Soft-capped sections ───────────────────────────────────────────────
        val remaining = budget - reservedTokens

        // Tool results and history — newest first, drop oldest.
        val history = fitHistory(request.conversationHistory, remaining / 2)
        val knowledge = fitKnowledge(request.knowledgeContext, remaining / 2)

        // Compose turns.
        val turns = mutableListOf<Turn>()
        turns.add(Turn.System(systemText))

        // Inject knowledge as a user turn if present.
        if (knowledge.isNotEmpty()) {
            val knowledgeText = knowledge.joinToString("\n\n---\n\n") { item ->
                item.content
            }
            turns.add(Turn.User(
                content = listOf(ContentBlock.Text("[Knowledge context]\n$knowledgeText")),
                trustLevel = dev.aidos.kernel.TrustLevel.TRUSTED,
            ))
        }

        turns.addAll(history)
        turns.add(Turn.User(
            content = listOf(ContentBlock.Text(request.userMessage)),
            trustLevel = dev.aidos.kernel.TrustLevel.TRUSTED,
        ))

        val modelRequest = ModelRequest(
            messages = turns,
            tools = tools,
            toolChoice = ToolChoice.Auto,
            maxOutputTokens = adapter.contextWindow / 8,
        )
        return AssemblyResult.Ok(
            PromptPackage(
                model = adapter,
                request = modelRequest,
                instructionSetHash = instructionSet?.hash,
                totalTokensEstimate = reservedTokens,
            )
        )
    }

    private fun renderInstructions(set: InstructionSet): String {
        return set.sources.joinToString("\n\n") { source ->
            "# Instructions from ${source.filename}\n\n${source.content}"
        }
    }

    private fun fitHistory(history: List<Turn>, tokenBudget: Int): List<Turn> {
        val result = ArrayDeque<Turn>()
        var used = 0
        // Take from the end (most recent first).
        for (turn in history.reversed()) {
            val cost = estimateTurnTokens(turn)
            if (used + cost > tokenBudget) break
            result.addFirst(turn)
            used += cost
        }
        return result.toList()
    }

    private fun fitKnowledge(items: List<ContextItem>, tokenBudget: Int): List<ContextItem> {
        val result = mutableListOf<ContextItem>()
        var used = 0
        for (item in items) {  // items are pre-ranked by caller
            val cost = estimateTokens(item.content)
            if (used + cost > tokenBudget) break
            result.add(item)
            used += cost
        }
        return result
    }

    // ── Token estimation ───────────────────────────────────────────────────────

    /** Conservative 4-chars-per-token estimate. */
    fun estimateTokens(text: String): Int = (text.length / 4).coerceAtLeast(1)

    private fun estimateTurnTokens(turn: Turn): Int = when (turn) {
        is Turn.System -> estimateTokens(turn.content) + 4
        is Turn.User -> turn.content.sumOf { estimateBlock(it) } + 4
        is Turn.Assistant -> estimateTokens(turn.text ?: "") + 4
        is Turn.ToolResult -> turn.result.content.sumOf { estimateBlock(it) } + 4
    }

    private fun estimateBlock(block: ContentBlock): Int = when (block) {
        is ContentBlock.Text -> estimateTokens(block.text)
        else -> 4
    }

    companion object {
        // Safety constraints — always present, not overridable (RFC-0025, RFC-0027).
        private const val SAFETY_SYSTEM_PROMPT = """You are Aidos, an AI coding assistant.
You operate within a capability-based security system. You may only use tools you have been granted permission to use. You cannot escalate permissions, grant yourself new capabilities, or override security controls. You cannot confirm your own success — the system observes outcomes directly. You report what you attempted and what happened; the execution layer records what was committed."""
    }
}

data class AssemblyRequest(
    val model: ModelAdapter,
    val userMessage: String,
    val tools: List<ToolDescriptor> = emptyList(),
    val conversationHistory: List<Turn> = emptyList(),
    val knowledgeContext: List<ContextItem> = emptyList(),
    val instructionSet: InstructionSet? = null,
    val runtimeInstructions: String = "",
)
