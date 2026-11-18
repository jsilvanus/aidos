package dev.aidos.ui.workspace

import dev.aidos.api.CreateIntentNodeRequest
import dev.aidos.api.RuntimeClient
import dev.aidos.api.StartNodeRunResult
import dev.aidos.api.UserMessage
import dev.aidos.api.UpdateIntentNodeRequest
import dev.aidos.intent.IntentEdgeKind
import dev.aidos.intent.ProposalResolutionDecision
import dev.aidos.ui.generative.ManifestAction
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkspaceUiState(
    val projectId: String,
    val viewId: String,
    val selectedNodeId: String?,
    val inspectorOpen: Boolean,
    val message: String?,
)

/**
 * The one place the workspace mutates (RFC-0124 amendment). Navigation and selection live here so
 * views stop keeping their own; every mutation goes through `client.intents` for the current
 * project, refreshes the store, and leaves a short message or error in [state].
 */
class WorkspaceController(
    private val client: RuntimeClient,
    private val scope: CoroutineScope,
    initialProjectId: String,
    initialViewId: String,
) {
    private val _state = MutableStateFlow(
        WorkspaceUiState(initialProjectId, initialViewId, selectedNodeId = null, inspectorOpen = false, message = null)
    )
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    private val stores = HashMap<String, ProjectGraphStore>()
    private val _currentStore = MutableStateFlow(storeFor(initialProjectId))
    val currentStore: StateFlow<ProjectGraphStore> = _currentStore.asStateFlow()

    /** Cached per project, started on first use. */
    fun storeFor(projectId: String): ProjectGraphStore = synchronized(stores) {
        stores.getOrPut(projectId) { ProjectGraphStore(projectId, client, scope).also { it.start() } }
    }

    /**
     * The store for [projectId] only if it is already running. Lets the Projects view count open
     * tasks for projects the person has opened, without starting a store for every project (UI-34).
     */
    fun storeIfStarted(projectId: String): ProjectGraphStore? = synchronized(stores) { stores[projectId] }

    fun switchProject(projectId: String) {
        if (projectId == _state.value.projectId) return
        _state.update { it.copy(projectId = projectId, selectedNodeId = null, inspectorOpen = false) }
        _currentStore.value = storeFor(projectId)
    }

    fun navigate(viewId: String, nodeId: String? = null) {
        _state.update { it.copy(viewId = viewId, selectedNodeId = nodeId ?: it.selectedNodeId) }
    }

    fun select(nodeId: String?) {
        _state.update { it.copy(selectedNodeId = nodeId) }
    }

    fun openInspector(nodeId: String) {
        _state.update { it.copy(selectedNodeId = nodeId, inspectorOpen = true) }
    }

    fun closeInspector() {
        _state.update { it.copy(inspectorOpen = false) }
    }

    fun createNode(request: CreateIntentNodeRequest) = mutate("Node created") {
        client.intents.createNode(request.copy(projectId = projectId()))
    }

    fun updateNode(request: UpdateIntentNodeRequest) = mutate("Node updated") {
        client.intents.updateNode(request.copy(projectId = projectId()))
    }

    fun archiveNode(nodeId: String) = mutate("Node archived") {
        client.intents.archiveNode(projectId(), nodeId)
        _state.update { if (it.selectedNodeId == nodeId) it.copy(selectedNodeId = null, inspectorOpen = false) else it }
    }

    /** [inProject] lets the Inbox complete an item from another project without switching to it first. */
    fun completeHumanNode(nodeId: String, note: String? = null, inProject: String = projectId()) =
        mutate("Marked done", inProject) {
            client.intents.completeHumanNode(inProject, nodeId, note = note)
        }

    fun addEdge(from: String, to: String, kind: IntentEdgeKind) = mutate("Link added") {
        client.intents.addEdge(projectId(), from, to, kind)
    }

    fun removeEdge(from: String, to: String, kind: IntentEdgeKind) = mutate("Link removed") {
        client.intents.removeEdge(projectId(), from, to, kind)
    }

    fun confirmImplementation(nodeId: String) = mutate("Implementation confirmed") {
        client.intents.confirmImplementation(projectId(), nodeId)
    }

    fun resolveProposal(proposalId: String, decision: ProposalResolutionDecision) = mutate("Proposal resolved") {
        client.intents.resolveProposal(projectId(), proposalId, decision)
    }

    /**
     * Starts an agent run on a node through `IntentCommands.startNodeRun` (RFC-0124, UI-04). The
     * outcome, started or refused with the runtime's reason, is shown in [state] as the message.
     */
    fun startNodeRun(nodeId: String) {
        val projectId = projectId()
        scope.launch {
            try {
                val message = when (val result = client.intents.startNodeRun(projectId, nodeId)) {
                    is StartNodeRunResult.Started -> "Run started"
                    is StartNodeRunResult.Refused -> result.message
                }
                storeFor(projectId).refreshNow()
                _state.update { it.copy(message = message) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Failed: ${t.message ?: t::class.simpleName}") }
            }
        }
    }

    /** Runs a registered card action (RFC-0124 manifest actions, UI-52). Effects keep their own guards. */
    fun performManifestAction(action: ManifestAction) {
        when (action) {
            is ManifestAction.OpenNode -> openInspector(action.nodeId)
            is ManifestAction.CompleteNode -> completeHumanNode(action.nodeId)
            is ManifestAction.StartNode -> startNodeRun(action.nodeId)
            is ManifestAction.ReplyToAgent -> replyToRun(action.runId, action.text)
        }
    }

    /** Sends [text] to the session that owns [runId]. The run is looked up through the sessions API. */
    private fun replyToRun(runId: String, text: String) {
        val projectId = projectId()
        scope.launch {
            try {
                var owner: String? = null
                for (summary in client.sessions.list(projectId)) {
                    val detail = client.sessions.get(summary.id) ?: continue
                    if (detail.recentRuns.any { it.id == runId }) {
                        owner = summary.id
                        break
                    }
                }
                val message = if (owner == null) {
                    "Could not find the session for that agent run."
                } else {
                    client.sessions.send(owner, UserMessage(text))
                    "Reply sent"
                }
                _state.update { it.copy(message = message) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Failed: ${t.message ?: t::class.simpleName}") }
            }
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun projectId() = _state.value.projectId

    private fun mutate(success: String, inProject: String = projectId(), block: suspend () -> Unit) {
        val store = storeFor(inProject)
        scope.launch {
            try {
                block()
                store.refreshNow()
                _state.update { it.copy(message = success) }
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _state.update { it.copy(message = "Failed: ${t.message ?: t::class.simpleName}") }
            }
        }
    }
}
