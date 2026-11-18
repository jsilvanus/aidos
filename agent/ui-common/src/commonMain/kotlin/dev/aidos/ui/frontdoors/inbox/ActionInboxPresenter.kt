package dev.aidos.ui.frontdoors.inbox

import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.ProjectCommands
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentNodeType
import dev.aidos.ui.state.MviPresenter
import dev.aidos.ui.workspace.GraphSnapshot
import dev.aidos.ui.workspace.WorkspaceController
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ActionInboxUiState(
    /** Every project's open human work, in [ActionInboxPresenter.sortForInbox] order. */
    val items: List<ActionItemUiModel> = emptyList(),
    /** Items of the current project only when [currentProjectOnly] is set. */
    val currentProjectOnly: Boolean = false,
    val currentProjectId: String = "",
    /** Pending proposals plus NEEDS_REVIEW nodes in the current project (the "approvals in Gates" line). */
    val gatesCount: Int = 0,
    val isLoading: Boolean = false,
    val errorMessage: String? = null,
) {
    val visibleItems: List<ActionItemUiModel>
        get() = if (currentProjectOnly) items.filter { it.node.projectId == currentProjectId } else items
}

sealed interface ActionInboxIntent {
    data object Refresh : ActionInboxIntent
    data class CompleteItem(val projectId: String, val nodeId: String) : ActionInboxIntent
    data class SetCurrentProjectOnly(val value: Boolean) : ActionInboxIntent
    data class CreateItem(val title: String) : ActionInboxIntent
}

/**
 * Action Inbox across every project (RFC-0124 amendment, UI-20). Each project's graph comes from
 * [WorkspaceController.storeFor], so the inbox updates as the stores do. The project list is re-read every 30 s.
 */
class ActionInboxPresenter(
    private val controller: WorkspaceController,
    private val projectCommands: ProjectCommands,
    private val scope: CoroutineScope,
    private val projectListMillis: Long = PROJECT_LIST_MILLIS,
) : MviPresenter<ActionInboxUiState, ActionInboxIntent> {

    private val _state = MutableStateFlow(ActionInboxUiState(isLoading = true))
    override val state: StateFlow<ActionInboxUiState> = _state.asStateFlow()

    private val sources = MutableStateFlow(InboxSources())
    private val collectors = HashMap<String, Job>()

    init {
        val currentProjectId = controller.state.map { it.projectId }.distinctUntilChanged()
        scope.launch {
            combine(sources, currentProjectId) { src, current -> src to current }
                .collect { (src, current) -> apply(src, current) }
        }
        scope.launch {
            while (isActive) {
                listProjects()
                delay(projectListMillis)
            }
        }
    }

    override fun send(intent: ActionInboxIntent) {
        when (intent) {
            is ActionInboxIntent.Refresh -> scope.launch { listProjects() }
            is ActionInboxIntent.SetCurrentProjectOnly -> {
                _state.update { it.copy(currentProjectOnly = intent.value) }
            }
            is ActionInboxIntent.CompleteItem -> {
                controller.completeHumanNode(intent.nodeId, inProject = intent.projectId)
            }
            is ActionInboxIntent.CreateItem -> {
                val title = intent.title.trim()
                if (title.isNotEmpty()) {
                    // Quick add: a HUMAN task in the project the workspace is showing (RFC-0124 amendment, UI-30).
                    controller.createNode(
                        CreateIntentNodeRequest(
                            projectId = _state.value.currentProjectId,
                            title = title,
                            type = IntentNodeType.ATOMIC_TASK,
                            actorKind = IntentActorKind.HUMAN,
                        )
                    )
                }
            }
        }
    }

    private suspend fun listProjects() {
        val projects = try {
            projectCommands.list()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            _state.update { it.copy(isLoading = false, errorMessage = t.message ?: "Failed to load inbox") }
            return
        }
        val ids = projects.map { it.id }.toSet()
        for (project in projects) {
            if (project.id !in collectors) {
                val store = controller.storeFor(project.id)
                collectors[project.id] = scope.launch {
                    store.snapshot.collect { snap ->
                        sources.update { it.copy(snapshots = it.snapshots + (project.id to snap)) }
                    }
                }
            }
        }
        collectors.keys.filter { it !in ids }.forEach { collectors.remove(it)?.cancel() }
        sources.update { current ->
            current.copy(
                names = projects.associate { it.id to it.name },
                snapshots = current.snapshots.filterKeys { it in ids },
            )
        }
    }

    private fun apply(src: InboxSources, currentProjectId: String) {
        val items = mutableListOf<ActionItemUiModel>()
        for ((projectId, name) in src.names) {
            val snap = src.snapshots[projectId] ?: continue
            if (snap.loadedAt == 0L) continue
            val isLife = name.contains("personal", ignoreCase = true) || name.contains("life", ignoreCase = true)
            val unblockedBy = { nodeId: String -> unblockedByTitle(snap, nodeId) }
            for (statusView in actionableForHuman(snap)) {
                val by = unblockedBy(statusView.node.id)
                items.add(
                    ActionItemUiModel(
                        node = statusView.node,
                        statusView = statusView,
                        projectName = name,
                        isLifeProject = isLife,
                        batonDirection = if (statusView.derivedStatus == DerivedStatus.NEEDS_REVIEW) {
                            BatonTransferDirection.AGENT_TO_HUMAN
                        } else {
                            BatonTransferDirection.PEER_COLLABORATION
                        },
                        unblockedBy = by,
                    )
                )
            }
        }
        val current = src.snapshots[currentProjectId]
        _state.update {
            it.copy(
                items = sortForInbox(items),
                currentProjectId = currentProjectId,
                gatesCount = current?.let { gatesCount(it) } ?: 0,
                isLoading = src.names.keys.any { (src.snapshots[it]?.loadedAt ?: 0L) == 0L } && src.snapshots.values.none { s -> s.error != null },
                errorMessage = src.snapshots.values.firstNotNullOfOrNull { it.error },
            )
        }
    }

    private data class InboxSources(
        val names: Map<String, String> = emptyMap(),
        val snapshots: Map<String, GraphSnapshot> = emptyMap(),
    )

    companion object {
        const val PROJECT_LIST_MILLIS = 30_000L

        private val OPEN_STATUSES = setOf(DerivedStatus.IN_PROGRESS, DerivedStatus.NOT_STARTED)

        /**
         * Open work with nothing unmet comes first, then lower priority numbers, then the earliest due date
         * (undated last), then creation time. Pinned by `InboxViewTest`.
         */
        fun sortForInbox(items: List<ActionItemUiModel>): List<ActionItemUiModel> =
            items.sortedWith(
                compareBy<ActionItemUiModel>(
                    { if (it.statusView.derivedStatus in OPEN_STATUSES && it.statusView.unmetDependencyIds.isEmpty()) 0 else 1 },
                    { it.node.priority },
                    { if (it.node.dueAt == null) 1 else 0 },
                    { it.node.dueAt ?: "" },
                    { it.node.createdAt },
                )
            )

        /**
         * The title of the dependency that unblocked [nodeId], only when the most recently finished DONE dependency
         * is an AGENT node. Null for human-unblocked work or when nothing is satisfied yet.
         */
        fun unblockedByTitle(snapshot: GraphSnapshot, nodeId: String): String? {
            val satisfied = snapshot.edges
                .filter { it.fromNodeId == nodeId && it.edgeKind == IntentEdgeKind.DEPENDS_ON }
                .mapNotNull { edge ->
                    val node = snapshot.nodes.firstOrNull { it.id == edge.toNodeId } ?: return@mapNotNull null
                    node.takeIf { snapshot.statuses[node.id]?.derivedStatus == DerivedStatus.DONE }
                }
            val last = satisfied.maxByOrNull { it.modifiedAt } ?: return null
            return last.title.takeIf { last.actorKind == IntentActorKind.AGENT }
        }

        /** Pending proposals plus NEEDS_REVIEW nodes in one project's snapshot. */
        fun gatesCount(snapshot: GraphSnapshot): Int =
            snapshot.proposals.count { it.state == "PENDING" } +
                snapshot.statuses.values.count { it.derivedStatus == DerivedStatus.NEEDS_REVIEW }

        /**
         * The filter and order of `DerivedStatusComputer.listActionableForHuman`, applied to one project's snapshot.
         * Kept in step with that function by hand; `PresentersOnStoreTest` pins the rule.
         */
        fun actionableForHuman(snapshot: GraphSnapshot): List<IntentNodeStatusView> =
            snapshot.nodes
                .mapNotNull { snapshot.statuses[it.id] }
                .filter { view ->
                    when (view.node.actorKind) {
                        IntentActorKind.HUMAN -> view.derivedStatus in setOf(
                            DerivedStatus.NOT_STARTED,
                            DerivedStatus.IN_PROGRESS,
                            DerivedStatus.NEEDS_REVIEW,
                        )
                        IntentActorKind.HYBRID, IntentActorKind.AGENT ->
                            view.derivedStatus == DerivedStatus.NEEDS_REVIEW
                    }
                }
                .sortedWith(
                    compareBy<IntentNodeStatusView>(
                        { it.node.dueAt == null },
                        { it.node.dueAt ?: "" },
                        { it.node.priority },
                        { it.node.createdAt },
                    )
                )
    }
}
