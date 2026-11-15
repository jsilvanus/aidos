package fi.italeino.aidos.engine.agent

import dev.aidos.aidin.AidinNode
import dev.aidos.aidin.LinkMessage
import dev.aidos.aidin.NodeId
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What happened to one tool call a peer's loop asked for. Never carries arguments or results (RFC-0107 audit). */
data class OriginToolAudit(
    val runId: String,
    val peer: NodeId,
    val app: String,
    val tool: String,
    val outcome: String,
) {
    companion object {
        const val FORWARDED = "forwarded"
        const val REFUSED_NOT_OFFERED = "refused_not_offered"
        const val REFUSED_NEEDS_APPROVAL = "refused_needs_approval"
        const val APPROVED = "approved"
    }
}

/**
 * The origin Engine's enforcement as tool host (RFC-0107 "Tool-host enforcement"). The app's tools
 * run on the origin (in the app), never on the peer; this decides what the peer may even ask for.
 *
 * - [allowedTools]: the per-peer, per-app allowlist the user approved. The peer is only told about
 *   these, and a call to anything else is refused. `null` or empty offers no tools.
 * - Taint is computed here and only here: once any tool result has entered a remote run, that run is
 *   tainted, and a tool the app did not declare `readOnly` then needs [approve]. Whatever the peer says
 *   about taint is ignored.
 * - [approve] defaults to refusing, so an effectful call on a tainted remote run is denied until a host
 *   wires a real approval surface.
 */
class OriginToolHost(
    val allowedTools: (peer: NodeId, app: String) -> Set<String>?,
    val approve: suspend (peer: NodeId, app: String, tool: String) -> Boolean = { _, _, _ -> false },
    val audit: (OriginToolAudit) -> Unit = {},
)

/**
 * [AgentRunApi] for a loop that runs on [peer] (RFC-0107 phase 3). The calling app talks to this
 * Engine as for any `/v1/agent` run; this forwards the turn over the peer link, keeps the app's tools
 * local, and applies [toolHost] to everything the peer asks for.
 */
class RemoteAgentClient(
    private val node: () -> AidinNode?,
    val peer: NodeId,
    private val toolHost: OriginToolHost,
    private val scope: kotlinx.coroutines.CoroutineScope,
    private val timeoutMillis: Long = 150_000L,
) : AgentRunApi {

    private class RunState(
        val app: String,
        val tools: Map<String, ClientToolSpec>,
        @Volatile var tainted: Boolean = false,
        var last: AgentTurnResponse,
        /** Calls refused at the origin: their error results ride along with the app's next submit. */
        val refused: MutableMap<String, ClientToolResult> = mutableMapOf(),
    )

    private val runs = ConcurrentHashMap<String, RunState>()

    override val events: SharedFlow<Pair<String, AgentEvent>> = MutableSharedFlow<Pair<String, AgentEvent>>().asSharedFlow()

    override fun info() = AgentInfo(maxConcurrentRuns = 8, maxSteps = 8, toolWaitSeconds = 120)

    override suspend fun start(owner: String, request: AgentTurnRequest): AgentTurnResponse {
        val allowed = toolHost.allowedTools(peer, owner).orEmpty()
        val offered = request.tools.filter { it.name in allowed }
        val body = AgentPeerWire.json.encodeToString(
            AgentPeerWire.TurnBody.serializer(),
            AgentPeerWire.TurnBody(owner, request.copy(tools = offered)),
        )
        val response = call(AgentPeerWire.TURNS, body, timeoutMillis)
        val runId = response.runId ?: throw AgentRequestException("peer_error", "peer returned no run id", 502)
        val state = RunState(owner, offered.associateBy { it.name }, last = response)
        runs[runId] = state
        return settle(runId, state, response)
    }

    override suspend fun submit(owner: String, runId: String, results: List<ClientToolResult>, async: Boolean): AgentTurnResponse {
        val state = owned(owner, runId)
        // A tool result is now entering the remote run: from here the run is tainted, decided locally.
        if (results.isNotEmpty()) state.tainted = true
        val merged = results + state.refused.values
        state.refused.clear()
        val body = AgentPeerWire.json.encodeToString(
            AgentPeerWire.ResultsBody.serializer(),
            AgentPeerWire.ResultsBody(owner, runId, merged, async),
        )
        return settle(runId, state, call(AgentPeerWire.RUN_RESULTS, body, timeoutMillis))
    }

    override suspend fun get(owner: String, runId: String, since: Int?, waitFor: Duration): AgentTurnResponse {
        val state = owned(owner, runId)
        val body = AgentPeerWire.json.encodeToString(
            AgentPeerWire.RunBody.serializer(),
            AgentPeerWire.RunBody(owner, runId, since, waitFor.inWholeMilliseconds),
        )
        return settle(runId, state, call(AgentPeerWire.RUN_GET, body, waitFor.inWholeMilliseconds + 15.seconds.inWholeMilliseconds))
    }

    override fun list(owner: String): List<AgentTurnResponse> =
        runs.values.filter { it.app == owner }.map { it.last }.sortedBy { it.runId }

    override fun cancel(owner: String, runId: String): Boolean {
        val state = runs[runId]?.takeIf { it.app == owner } ?: return false
        runs.remove(runId)
        val n = node() ?: return true
        // Best effort: the peer also drops the run when its retention lapses.
        scope.launchCancel(n, runId, state.app)
        return true
    }

    fun owns(runId: String): Boolean = runs.containsKey(runId)

    // ── Tool-host enforcement ─────────────────────────────────────────────────

    /**
     * Applies the allowlist and taint rule to the peer's pending calls. Calls that are refused are answered
     * to the peer without bothering the app; the rest are what the app sees. If nothing is left for the
     * app, the refusals are submitted at once and the loop continues.
     */
    private suspend fun settle(runId: String, state: RunState, first: AgentTurnResponse): AgentTurnResponse {
        var response = first
        while (true) {
            state.last = response
            if (response.isTerminal) { runs.remove(runId); return response }
            if (response.status != AgentTurnResponse.TOOL_CALLS || response.toolCalls.isEmpty()) return response

            val forward = mutableListOf<PendingToolCall>()
            val refusedNow = mutableListOf<ClientToolResult>()
            for (c in response.toolCalls) {
                if (state.refused.containsKey(c.callId)) continue
                val spec = state.tools[c.name]
                val verdict = when {
                    spec == null -> OriginToolAudit.REFUSED_NOT_OFFERED
                    state.tainted && !spec.readOnly ->
                        if (toolHost.approve(peer, state.app, c.name)) OriginToolAudit.APPROVED else OriginToolAudit.REFUSED_NEEDS_APPROVAL
                    else -> OriginToolAudit.FORWARDED
                }
                toolHost.audit(OriginToolAudit(runId, peer, state.app, c.name, verdict))
                if (verdict == OriginToolAudit.FORWARDED || verdict == OriginToolAudit.APPROVED) forward += c
                else {
                    val msg = if (verdict == OriginToolAudit.REFUSED_NOT_OFFERED) "tool is not available to this agent" else "approval required: this run has received tool results, so effectful tools need the user's approval"
                    val result = ClientToolResult(c.callId, msg, isError = true)
                    refusedNow += result
                    state.refused[c.callId] = result
                }
            }
            if (forward.isNotEmpty()) return response.copy(toolCalls = forward).also { state.last = it }
            // Everything was refused here: answer the peer and carry on to its next stop.
            state.refused.clear()
            val body = AgentPeerWire.json.encodeToString(
                AgentPeerWire.ResultsBody.serializer(),
                AgentPeerWire.ResultsBody(state.app, runId, refusedNow, async = false),
            )
            response = call(AgentPeerWire.RUN_RESULTS, body, timeoutMillis)
        }
    }

    private companion object {
        val UNAVAILABLE_CODES = setOf("untrusted_peer", "peer_unreachable", "peer_lost", "peer_timeout", "peer_busy")
    }

    private fun owned(owner: String, runId: String): RunState =
        runs[runId]?.takeIf { it.app == owner } ?: throw AgentRequestException("run_not_found", "no such run: $runId", 404)

    private suspend fun call(endpoint: String, body: String, timeout: Long): AgentTurnResponse {
        val n = node() ?: throw AgentRequestException("peer_unreachable", "no peer network on this Engine", 503)
        val r: LinkMessage.DelegateResponse = n.delegate(peer, endpoint, body, locality = "trusted_cluster", timeoutMillis = timeout)
        if (r.status != 200) {
            val code = Regex("\"code\"\\s*:\\s*\"([^\"]+)\"").find(r.bodyJson)?.groupValues?.get(1) ?: "peer_error"
            val status = if (r.status in 400..499) r.status else 502
            // A peer that is gone, unreachable, timed out or no longer trusted is "unavailable" (503): the
            // router may then fall back to a narrower placement. Everything else is the peer's own answer.
            val unavailable = code in UNAVAILABLE_CODES || r.status == 503
            throw AgentRequestException(code, "peer ${peer.value}: ${code}", if (unavailable) 503 else status)
        }
        return AgentPeerWire.json.decodeFromString(AgentTurnResponse.serializer(), r.bodyJson)
    }

    private fun kotlinx.coroutines.CoroutineScope.launchCancel(n: AidinNode, runId: String, app: String) {
        this.launch {
            runCatching {
                n.delegate(
                    peer, AgentPeerWire.RUN_CANCEL,
                    AgentPeerWire.json.encodeToString(AgentPeerWire.RunBody.serializer(), AgentPeerWire.RunBody(app, runId)),
                    locality = "trusted_cluster", timeoutMillis = 10_000,
                )
            }
        }
    }
}
