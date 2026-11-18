package dev.aidos.ui.paradigms.kanban

import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentLifecycle
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeType
import dev.aidos.intent.IntentTargetProfile
import dev.aidos.intent.NodeDispatchPolicy

/**
 * RFC-0111 Design 3 (Cartridge Dock), RFC-0124 UI-36. Pure rules for the board: which column a
 * task sits in, which drops are allowed, how tasks group into goal swimlanes, and what the compact
 * deck shows. Nothing here touches Compose or the runtime, so the JVM tests cover all of it.
 */

/** The five board columns, in board order. */
enum class DockLane(val title: String, val status: DerivedStatus) {
    BLOCKED("Blocked", DerivedStatus.BLOCKED),
    READY("Ready", DerivedStatus.NOT_STARTED),
    IN_FLIGHT("In flight", DerivedStatus.IN_PROGRESS),
    VERIFY("Verify & dock", DerivedStatus.NEEDS_REVIEW),
    DONE("Done", DerivedStatus.DONE),
}

/**
 * Column for a task, or `null` for STALE and CONFLICTED, which go to the attention strip above the
 * board instead. A NOT_STARTED task with unmet dependencies is blocked, not ready: Ready means
 * "nothing stands in the way".
 */
fun laneFor(status: DerivedStatus, unmetDependencies: Int): DockLane? = when (status) {
    DerivedStatus.BLOCKED -> DockLane.BLOCKED
    DerivedStatus.NOT_STARTED -> if (unmetDependencies > 0) DockLane.BLOCKED else DockLane.READY
    DerivedStatus.IN_PROGRESS -> DockLane.IN_FLIGHT
    DerivedStatus.NEEDS_REVIEW -> DockLane.VERIFY
    DerivedStatus.DONE -> DockLane.DONE
    DerivedStatus.STALE, DerivedStatus.CONFLICTED -> null
}

/** What a drop on the board should do. The screen performs the action through the controller. */
sealed interface DockAction {
    data class Start(val nodeId: String) : DockAction
    data class Complete(val nodeId: String) : DockAction
    data class Confirm(val nodeId: String) : DockAction
    data class Refuse(val reason: String) : DockAction
    data object NoOp : DockAction
}

/**
 * Decides a drop from [from] to [to]. Status is derived, so most moves are refused with the reason
 * shown in a snackbar; only three moves change anything:
 * - Ready to In flight on an AGENT or HYBRID task starts a run.
 * - Any column to Done on a HUMAN task completes it.
 * - Verify & dock to Done confirms the implementation.
 */
fun dropAction(task: IntentNodeRecord, from: DockLane, to: DockLane, unmetDependencies: Int = 0): DockAction {
    if (from == to) return DockAction.NoOp
    if (from == DockLane.DONE) return DockAction.Refuse("Done tasks stay done")
    if (to == DockLane.BLOCKED) return DockAction.Refuse("Blocked follows dependencies: finish them to unblock")
    if (to == DockLane.READY) return DockAction.Refuse("Ready follows the graph, so it cannot be dragged into")
    if (to == DockLane.VERIFY) return DockAction.Refuse("Only a finished agent run reaches Verify & dock")
    if (unmetDependencies > 0) return DockAction.Refuse(blockedReason(unmetDependencies))
    val human = task.actorKind == IntentActorKind.HUMAN
    return when (to) {
        DockLane.IN_FLIGHT -> when {
            from != DockLane.READY -> DockAction.Refuse("Only ready tasks can start")
            human -> DockAction.Refuse("Human tasks are completed, not started")
            else -> DockAction.Start(task.id)
        }
        DockLane.DONE -> when {
            human -> DockAction.Complete(task.id)
            from == DockLane.VERIFY -> DockAction.Confirm(task.id)
            else -> DockAction.Refuse("Only human tasks or verified work can be marked done")
        }
        DockLane.BLOCKED, DockLane.READY, DockLane.VERIFY -> DockAction.Refuse("Unsupported move")
    }
}

/** Snackbar text for a move that dependencies forbid. */
fun blockedReason(unmet: Int): String = "Blocked by $unmet ${if (unmet == 1) "task" else "tasks"}"

/** Cartridges are atomic tasks that are still active; goals and groups are lane headers, not cards. */
fun isCartridge(node: IntentNodeRecord): Boolean =
    node.type == IntentNodeType.ATOMIC_TASK && node.lifecycle == IntentLifecycle.ACTIVE

/** The nearest GOAL ancestor of [node], walking up through sub-goals and groups; `null` when none. */
fun nearestGoal(node: IntentNodeRecord, byId: Map<String, IntentNodeRecord>): IntentNodeRecord? {
    val seen = HashSet<String>()
    var parentId = node.parentId
    while (parentId != null && seen.add(parentId)) {
        val parent = byId[parentId] ?: return null
        if (parent.type == IntentNodeType.GOAL) return parent
        parentId = parent.parentId
    }
    return null
}

/** One row of the board: a goal's tasks split across the columns. `goalId` is null for "No goal". */
data class DockSwimlane(
    val goalId: String?,
    val title: String,
    val cells: Map<DockLane, List<IntentNodeRecord>>,
)

/**
 * Groups [tasks] (already placed in [lanes]) into swimlanes, one per nearest GOAL, in the order
 * the goals first appear. The "No goal" lane comes last. Lanes with no tasks are dropped.
 */
fun buildSwimlanes(
    tasks: Map<String, Pair<IntentNodeRecord, DockLane>>,
    byId: Map<String, IntentNodeRecord>,
): List<DockSwimlane> {
    val order = LinkedHashMap<String?, MutableMap<DockLane, MutableList<IntentNodeRecord>>>()
    for ((node, lane) in tasks.values) {
        val goal = nearestGoal(node, byId)?.id
        val cells = order.getOrPut(goal) { DockLane.entries.associateWith { mutableListOf<IntentNodeRecord>() }.toMutableMap() }
        cells.getValue(lane).add(node)
    }
    val goalNodes = order.keys.filterNotNull()
    val titled = goalNodes.map { id -> DockSwimlane(id, byId[id]?.title ?: id, order.getValue(id).mapValues { it.value.toList() }) }
    val noGoal = order[null]?.let { DockSwimlane(null, "No goal", it.mapValues { cell -> cell.value.toList() }) }
    return titled + listOfNotNull(noGoal)
}

/**
 * The compact deck shows Ready cartridges whose target is a phone (MOBILE) or any device. "All"
 * shows every Ready cartridge.
 */
fun deckFilter(readyTasks: List<IntentNodeRecord>, showAll: Boolean): List<IntentNodeRecord> =
    if (showAll) {
        readyTasks
    } else {
        readyTasks.filter { it.targetProfile == null || it.targetProfile == IntentTargetProfile.ANY || it.targetProfile == IntentTargetProfile.MOBILE }
    }

/** Badge text for the target device: a phone, a desktop, a server, or "any". */
fun targetBadge(profile: IntentTargetProfile?): String = when (profile) {
    IntentTargetProfile.MOBILE -> "\uD83D\uDCF1"
    IntentTargetProfile.DESKTOP -> "\uD83D\uDDA5"
    IntentTargetProfile.HEADLESS_SERVER -> "\u2601"
    IntentTargetProfile.ANY, null -> "any"
}

/** The dispatch toggle shows AUTO only for AUTO_WHEN_READY; everything else reads as MANUAL. */
fun dispatchLabel(policy: NodeDispatchPolicy): String =
    if (policy == NodeDispatchPolicy.AUTO_WHEN_READY) "AUTO" else "MANUAL"

/** The policy a click on the dispatch toggle switches to. */
fun toggledDispatch(policy: NodeDispatchPolicy): NodeDispatchPolicy =
    if (policy == NodeDispatchPolicy.AUTO_WHEN_READY) NodeDispatchPolicy.MANUAL_START else NodeDispatchPolicy.AUTO_WHEN_READY
