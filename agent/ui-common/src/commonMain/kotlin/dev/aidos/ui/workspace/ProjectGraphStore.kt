package dev.aidos.ui.workspace

import dev.aidos.api.EventFilter
import dev.aidos.api.RuntimeClient
import dev.aidos.api.RuntimeEvent
import dev.aidos.api.RuntimeEventType
import dev.aidos.api.UiManifestRecord
import dev.aidos.intent.IntentEdgeRecord
import dev.aidos.intent.IntentNodeRecord
import dev.aidos.intent.IntentNodeStatusView
import dev.aidos.intent.IntentProposal
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

/** One consistent read of a project's intent graph (RFC-0124 amendment, decision U7). */
data class GraphSnapshot(
    val projectId: String,
    val nodes: List<IntentNodeRecord>,
    val edges: List<IntentEdgeRecord>,
    val statuses: Map<String, IntentNodeStatusView>,
    val proposals: List<IntentProposal>,
    val loadedAt: Long, // epoch millis
    val error: String? = null,
    /** Agent cards (RFC-0124 generative layer, UI-51) by target node, newest first, at most [MAX_CARDS_PER_NODE] each. */
    val manifests: Map<String, List<UiManifestRecord>> = emptyMap(),
) {
    companion object {
        fun empty(projectId: String) = GraphSnapshot(
            projectId = projectId,
            nodes = emptyList(),
            edges = emptyList(),
            statuses = emptyMap(),
            proposals = emptyList(),
            loadedAt = 0L,
        )
    }
}

/**
 * The single live source of graph data for one project. Views read [snapshot]; nothing else
 * fetches the graph. Refreshes on runtime events (debounced) and on a poll (decision U6), because
 * writes by another process raise no event here.
 */
/** What a running agent is doing right now (UI-70): its latest text and its last few finished tool calls. */
data class LiveRun(
    val runId: String,
    /** Latest model text, at most [LIVE_TEXT_MAX_CHARS] characters (the tail). */
    val text: String = "",
    /** Names of the last [LIVE_TOOL_CALLS_MAX] completed tool calls, oldest first. */
    val toolCalls: List<String> = emptyList(),
)

const val LIVE_TEXT_MAX_CHARS = 2_000
const val LIVE_TOOL_CALLS_MAX = 5

/** Pure: folds one runtime event into the live-run map. Ended runs are dropped. */
fun applyLiveEvent(runs: Map<String, LiveRun>, event: RuntimeEvent): Map<String, LiveRun> = when (event) {
    is RuntimeEvent.AiResponseDelta -> {
        val old = runs[event.runId] ?: LiveRun(event.runId)
        // A final delta is the whole step's text; a partial one extends what is shown.
        val text = if (event.isFinal) event.delta else old.text + event.delta
        runs + (event.runId to old.copy(text = text.takeLast(LIVE_TEXT_MAX_CHARS)))
    }
    is RuntimeEvent.ToolCallCompleted -> {
        val old = runs[event.runId] ?: LiveRun(event.runId)
        runs + (event.runId to old.copy(toolCalls = (old.toolCalls + event.toolName).takeLast(LIVE_TOOL_CALLS_MAX)))
    }
    is RuntimeEvent.RunCompleted -> runs - event.runId
    is RuntimeEvent.RunFailed -> runs - event.runId
    else -> runs
}

/** Agent cards shown under one node. More than this is noise; the rest stay in the runtime's records. */
const val MAX_CARDS_PER_NODE = 3

class ProjectGraphStore(
    val projectId: String,
    private val client: RuntimeClient,
    private val scope: CoroutineScope,
    private val pollMillis: Long = 5_000,
    private val debounceMillis: Long = 250,
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val _snapshot = MutableStateFlow(GraphSnapshot.empty(projectId))
    val snapshot: StateFlow<GraphSnapshot> = _snapshot.asStateFlow()

    private val _liveRuns = MutableStateFlow<Map<String, LiveRun>>(emptyMap())
    /** Runs in progress in this project, by run id, fed by runtime events (UI-70). */
    val liveRuns: StateFlow<Map<String, LiveRun>> = _liveRuns.asStateFlow()

    private val loadMutex = Mutex()
    private val lifecycleLock = Any()
    private var jobs: List<Job> = emptyList()

    /** First load, event subscription and poll loop. Idempotent. */
    fun start() {
        synchronized(lifecycleLock) {
            if (jobs.isNotEmpty()) return
            val first = scope.launch { refreshNow() }
            val events = scope.launch { listenForEvents() }
            val poll = scope.launch {
                while (true) {
                    delay(pollMillis)
                    refreshNow()
                }
            }
            jobs = listOf(first, events, poll)
        }
    }

    fun stop() {
        synchronized(lifecycleLock) {
            jobs.forEach { it.cancel() }
            jobs = emptyList()
        }
    }

    /** Loads the graph now. Never runs two loads at once; on failure the old data stays and [GraphSnapshot.error] is set. */
    suspend fun refreshNow() {
        loadMutex.withLock {
            try {
                val intents = client.intents
                val nodes = intents.listNodes(projectId)
                val edges = intents.listEdges(projectId)
                val statuses = LinkedHashMap<String, IntentNodeStatusView>()
                // N+1 on purpose (decision U7): add a batch API only if this is measured slow.
                for (node in nodes) {
                    intents.getNodeStatus(projectId, node.id)?.let { statuses[node.id] = it }
                }
                val proposals = intents.listProposals(projectId, state = "PENDING")
                    .filter { it.state == "PENDING" }
                // Cards are optional: if they fail to load the graph still loads, and the last good cards stay.
                val manifests = try {
                    intents.listUiManifests(projectId, limit = 100)
                        .groupBy { it.targetNodeId }
                        .mapValues { (_, records) -> records.take(MAX_CARDS_PER_NODE) }
                } catch (c: CancellationException) {
                    throw c
                } catch (_: Throwable) {
                    _snapshot.value.manifests
                }
                _snapshot.value = GraphSnapshot(projectId, nodes, edges, statuses, proposals, clock(), manifests = manifests)
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _snapshot.value = _snapshot.value.copy(error = t.message ?: t::class.simpleName ?: "Load failed")
            }
        }
    }

    private suspend fun listenForEvents() {
        val pending = Channel<Unit>(Channel.CONFLATED)
        // A failing subscribe must not stop polling, so it is caught here and the poll carries on.
        val reader = scope.launch {
            try {
                client.events.subscribe(
                    EventFilter(
                        projectIds = listOf(projectId),
                        types = listOf(
                            RuntimeEventType.INTENT_GRAPH_CHANGED,
                            RuntimeEventType.RUN_STARTED,
                            RuntimeEventType.RUN_COMPLETED,
                            RuntimeEventType.RUN_FAILED,
                            RuntimeEventType.RUN_STEP_COMPLETED,
                            RuntimeEventType.TOOL_CALL_COMPLETED,
                            RuntimeEventType.TOOL_APPROVAL_REQUIRED,
                            RuntimeEventType.AI_RESPONSE_DELTA,
                        ),
                    )
                ).collect { event ->
                    _liveRuns.value = applyLiveEvent(_liveRuns.value, event)
                    // Text deltas only feed the live panel; they change nothing a refresh would read.
                    if (event !is RuntimeEvent.AiResponseDelta) pending.trySend(Unit)
                }
            } catch (c: CancellationException) {
                throw c
            } catch (_: Throwable) {
                // the poll covers it
            }
        }
        try {
            while (true) {
                pending.receive()
                // Debounce: wait for the burst to settle, drop what piled up, then load once.
                delay(debounceMillis)
                pending.tryReceive()
                refreshNow()
            }
        } finally {
            reader.cancel()
        }
    }
}
