package dev.aidos.ui.frontdoors.inbox

import dev.aidos.api.ProjectCommands
import dev.aidos.intent.DerivedStatus
import dev.aidos.intent.IntentActorKind
import dev.aidos.intent.IntentNodeStatusView
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class ActionInboxUiState(
    val items: List<ActionItemUiModel> = emptyList(),
    val pendingApprovalsCount: Int = 0,
    val isLoading: Boolean = false,
    val selectedFilterProjectId: String? = null,
    val errorMessage: String? = null,
)

sealed interface ActionInboxIntent {
    data object Refresh : ActionInboxIntent
    data class CompleteItem(val projectId: String, val nodeId: String) : ActionInboxIntent
    data class FilterByProject(val projectId: String?) : ActionInboxIntent
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
        scope.launch {
            sources.collect { apply(it) }
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
            is ActionInboxIntent.FilterByProject -> {
                _state.update { it.copy(selectedFilterProjectId = intent.projectId) }
            }
            is ActionInboxIntent.CompleteItem -> {
                controller.completeHumanNode(intent.nodeId, inProject = intent.projectId)
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

    private fun apply(src: InboxSources) {
        val items = mutableListOf<ActionItemUiModel>()
        for ((projectId, name) in src.names) {
            val snap = src.snapshots[projectId] ?: continue
            if (snap.loadedAt == 0L) continue
            val isLife = name.contains("personal", ignoreCase = true) || name.contains("life", ignoreCase = true)
            for (statusView in actionableForHuman(snap)) {
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
                    )
                )
            }
        }
        _state.update {
            it.copy(
                items = items,
                pendingApprovalsCount = items.count { item -> item.statusView.derivedStatus == DerivedStatus.NEEDS_REVIEW },
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
