package dev.aidos.ui.pills

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.workspace.GraphSnapshot

/** RFC-0124 §3 / RFC-0111 §3: at most this many worker pills are drawn at once. */
const val MAX_WORKER_PILLS = 6

/** Pill labels are cut to this many characters, the last one an ellipsis. */
const val PILL_LABEL_MAX = 24

private const val PILL_ROW_Y = 0.88f
private const val PILL_ROW_X_START = 0.12f
private const val PILL_ROW_X_STEP = 0.12f

/**
 * Pills for the Multi-Agent Pill Overlay, derived from the live graph (UI-42).
 *
 * The red Assistant pill is always present and radiates while the Assistant is open. Each node with a
 * run in flight gets a worker pill, in snapshot order, up to [MAX_WORKER_PILLS]. The role follows the
 * node: a verification-shaped node is green, a node with scope paths is blue, anything else violet.
 */
fun pillsFor(snapshot: GraphSnapshot, assistantActive: Boolean): List<AgentPillState> {
    val red = AgentPillState(
        id = "pill-red",
        role = PillRole.ORCHESTRATOR_RED,
        label = "Dictator Caret",
        xNormalized = 0.85f,
        yNormalized = 0.15f,
        isRadiatingWave = assistantActive,
    )
    val workers = snapshot.nodes
        .filter { node -> snapshot.statuses[node.id]?.activeRunIds?.isNotEmpty() == true }
        .take(MAX_WORKER_PILLS)
        .mapIndexed { index, node ->
            AgentPillState(
                id = "pill-worker-${node.id}",
                role = roleFor(node, snapshot.statuses[node.id]?.derivedStatus),
                label = truncateLabel(node.title),
                activeTaskId = node.id,
                xNormalized = PILL_ROW_X_START + PILL_ROW_X_STEP * index,
                yNormalized = PILL_ROW_Y,
            )
        }
    return listOf(red) + workers
}

internal fun roleFor(node: IntentNodeRecord, status: DerivedStatus?): PillRole = when {
    status == DerivedStatus.NEEDS_REVIEW || node.type == IntentNodeType.ACCEPTANCE_CRITERION ->
        PillRole.VERIFIER_GREEN
    node.scopePaths.isNotEmpty() -> PillRole.WORKER_CODE_BLUE
    else -> PillRole.RESEARCH_VIOLET
}

internal fun truncateLabel(title: String): String =
    if (title.length <= PILL_LABEL_MAX) title else title.take(PILL_LABEL_MAX - 1) + "…"
