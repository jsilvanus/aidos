package dev.aidos.prompt

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView

/**
 * Renders Dual-Mode Intent Graph context into the reserved Layer 3 `[INTENT GRAPH]`
 * section of the system prompt (RFC-0110, RFC-0025).
 *
 * Ensures:
 * - Atomic coding tasks pin exact `scopePaths` and `estimatedSteps` for constrained mobile/local models.
 * - `CONSTRAINS` constraints and `ACCEPTS` criteria are surfaced inline.
 * - `HUMAN`-owned tasks are strictly fenced as read-only handoff context so agents never execute them autonomously.
 */
object IntentPromptRenderer {

    fun renderTargetContext(
        targetView: IntentNodeStatusView,
        constraints: List<IntentNodeRecord> = emptyList(),
        acceptanceCriteria: List<IntentNodeRecord> = emptyList(),
        humanHandoffs: List<IntentNodeStatusView> = emptyList(),
    ): String {
        val node = targetView.node
        require(node.actorKind != IntentActorKind.HUMAN) {
            "HUMAN-owned intent node '${node.id}' must not be rendered as an autonomous agent target."
        }

        val sb = StringBuilder()
        sb.appendLine("[INTENT GRAPH]")
        sb.appendLine(
            "Target: ${node.title} (id=${node.id}, type=${node.type}, actor=${node.actorKind}, " +
                "granularity=${node.granularity}, status=${targetView.derivedStatus})",
        )
        node.description?.takeIf { it.isNotBlank() }?.let {
            sb.appendLine("Description: $it")
        }
        val profileInfo = listOfNotNull(
            node.targetProfile?.let { "target_profile=$it" },
            node.minLocality?.let { "min_locality=$it" },
        )
        if (profileInfo.isNotEmpty()) {
            sb.appendLine("Execution constraints: ${profileInfo.joinToString(", ")}")
        }
        node.estimatedSteps?.let {
            sb.appendLine("Step budget: $it steps max")
        }
        if (node.scopePaths.isNotEmpty()) {
            sb.appendLine("Pinned scope paths (limit discovery and edits to these paths first):")
            node.scopePaths.forEach { path -> sb.appendLine("- $path") }
        }
        if (constraints.isNotEmpty()) {
            sb.appendLine("Active constraints:")
            constraints.forEach { c ->
                val detail = c.description?.takeIf { it.isNotBlank() }?.let { ": $it" } ?: ""
                sb.appendLine("- ${c.title}$detail")
            }
        }
        if (acceptanceCriteria.isNotEmpty()) {
            sb.appendLine("Acceptance criteria:")
            acceptanceCriteria.forEach { crit ->
                val metLabel = if (crit.verificationMet == true) "MET" else "UNMET"
                val checkDetail = listOfNotNull(
                    crit.checkKind?.let { "check=$it" },
                    crit.checkSpec?.let { "spec=$it" },
                ).joinToString(", ")
                val suffix = if (checkDetail.isNotEmpty()) " ($checkDetail)" else ""
                sb.appendLine("- [$metLabel] ${crit.title}$suffix")
            }
        }
        val humanOnly = humanHandoffs.filter { it.node.actorKind == IntentActorKind.HUMAN }
        if (humanOnly.isNotEmpty()) {
            sb.appendLine("Human-owned tasks (READ-ONLY CONTEXT — do NOT execute on the user's behalf):")
            humanOnly.forEach { h ->
                val dueSuffix = h.node.dueAt?.let { ", due=$it" } ?: ""
                sb.appendLine("- [HUMAN | ${h.derivedStatus}] ${h.node.title} (id=${h.node.id}$dueSuffix)")
            }
        }
        return sb.toString().trimEnd()
    }

    fun renderProjectIntentSummary(
        agentReadyNodes: List<IntentNodeStatusView>,
        humanTasks: List<IntentNodeStatusView> = emptyList(),
    ): String {
        val visibleAgent = agentReadyNodes.filter {
            it.node.actorKind != IntentActorKind.HUMAN &&
                it.derivedStatus !in setOf(DerivedStatus.DONE, DerivedStatus.BLOCKED, DerivedStatus.STALE)
        }
        val visibleHuman = humanTasks.filter {
            it.node.actorKind == IntentActorKind.HUMAN &&
                it.derivedStatus !in setOf(DerivedStatus.DONE, DerivedStatus.STALE)
        }
        if (visibleAgent.isEmpty() && visibleHuman.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine("[INTENT GRAPH]")
        if (visibleAgent.isNotEmpty()) {
            sb.appendLine("Agent-eligible goals:")
            visibleAgent.forEach { view ->
                val n = view.node
                val scopeSuffix = if (n.scopePaths.isNotEmpty()) " scope=${n.scopePaths.joinToString(",")}" else ""
                sb.appendLine("- [${n.actorKind} | ${view.derivedStatus}] ${n.title} (id=${n.id}$scopeSuffix)")
            }
        }
        if (visibleHuman.isNotEmpty()) {
            sb.appendLine("Human-owned tasks (READ-ONLY CONTEXT — do NOT execute on the user's behalf):")
            visibleHuman.forEach { view ->
                val n = view.node
                val dueSuffix = n.dueAt?.let { ", due=$it" } ?: ""
                sb.appendLine("- [HUMAN | ${view.derivedStatus}] ${n.title} (id=${n.id}$dueSuffix)")
            }
        }
        return sb.toString().trimEnd()
    }
}
