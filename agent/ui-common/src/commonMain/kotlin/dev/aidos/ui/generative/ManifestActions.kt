package dev.aidos.ui.generative

import dev.aidos.api.UiManifestRecord
import dev.aidos.kernel.TrustLevel
import dev.aidos.tcr.a2ui.A2uiManifest
import dev.aidos.tcr.a2ui.A2uiNode
import dev.aidos.tcr.a2ui.A2uiValue
import dev.aidos.ui.workspace.GraphSnapshot

/**
 * RFC-0124 0124.4 (decision U14): the only actions a generative card may trigger. A manifest names
 * an `actionId`; the host maps it to one of these, or refuses it. Nothing else is reachable.
 */
sealed interface ManifestAction {
    data class OpenNode(val nodeId: String) : ManifestAction
    data class CompleteNode(val nodeId: String) : ManifestAction
    data class StartNode(val nodeId: String) : ManifestAction
    data class ReplyToAgent(val runId: String, val text: String) : ManifestAction
}

/**
 * Maps a card's `actionId` to a host action (RFC-0124 manifest actions).
 *
 * Accepted ids are exactly `open_node`, `complete_node`, `start_node` and `reply_to_agent`. The node
 * actions also accept a `:<nodeId>` suffix; without it the node is the card's target node. A node
 * must be in the snapshot of the card's own project, so a suffix naming another project's node
 * resolves to null. `reply_to_agent` takes no suffix: it replies in the run that produced the card.
 */
object ManifestActionRegistry {
    private const val OPEN_NODE = "open_node"
    private const val COMPLETE_NODE = "complete_node"
    private const val START_NODE = "start_node"
    private const val REPLY_TO_AGENT = "reply_to_agent"

    fun resolve(
        actionId: String,
        record: UiManifestRecord,
        buttonLabel: String,
        snapshot: GraphSnapshot,
    ): ManifestAction? {
        val base = actionId.substringBefore(':')
        val suffix = if (actionId.contains(':')) actionId.substringAfter(':') else null
        if (record.projectId != snapshot.projectId) return null
        return when (base) {
            REPLY_TO_AGENT ->
                if (suffix != null) null else ManifestAction.ReplyToAgent(record.runId, buttonLabel)
            OPEN_NODE, COMPLETE_NODE, START_NODE -> {
                val nodeId = suffix ?: record.targetNodeId
                if (nodeId.isEmpty() || snapshot.nodes.none { it.id == nodeId && it.projectId == snapshot.projectId }) {
                    null
                } else {
                    when (base) {
                        OPEN_NODE -> ManifestAction.OpenNode(nodeId)
                        COMPLETE_NODE -> ManifestAction.CompleteNode(nodeId)
                        else -> ManifestAction.StartNode(nodeId)
                    }
                }
            }
            else -> null
        }
    }

    /**
     * True when every button id of a card resolves. A card with any unresolved id is drawn with
     * actions disabled as a whole (the renderer does not support per-button enabling).
     */
    fun allResolve(
        actionIds: Collection<String>,
        record: UiManifestRecord,
        buttonLabel: (String) -> String,
        snapshot: GraphSnapshot,
    ): Boolean = actionIds.all { resolve(it, record, buttonLabel(it), snapshot) != null }
}

/**
 * Taint rule (RFC-0027, U14): a card from a run that is not exactly TRUSTED asks for confirmation
 * before any action. Unknown taint strings count as untrusted (fail closed).
 */
fun needsConfirmation(record: UiManifestRecord): Boolean =
    record.runTaint != TrustLevel.TRUSTED.name

/** The confirmation question shown before an untrusted card's action runs. */
fun confirmationText(action: ManifestAction): String {
    val what = when (action) {
        is ManifestAction.OpenNode -> "open this node"
        is ManifestAction.CompleteNode -> "mark this node complete"
        is ManifestAction.StartNode -> "start an agent run on this node"
        is ManifestAction.ReplyToAgent -> "send this reply to the agent"
    }
    return "This card came from an agent that read untrusted content. Do you want to $what?"
}

/**
 * Every button of a manifest as actionId to label. The renderer hands the host only the id, so
 * `reply_to_agent` takes its text from here. A repeated id keeps its first label.
 */
fun manifestButtons(manifest: A2uiManifest): Map<String, String> {
    val out = linkedMapOf<String, String>()
    fun walk(node: A2uiNode) {
        if (node.type == "ActionButton") {
            val id = (node.props["actionId"] as? A2uiValue.Text)?.value
            if (id != null) out.putIfAbsent(id, (node.props["label"] as? A2uiValue.Text)?.value.orEmpty())
        }
        node.slots.values.forEach(::walk)
        node.children.forEach(::walk)
    }
    walk(manifest.root)
    return out
}
