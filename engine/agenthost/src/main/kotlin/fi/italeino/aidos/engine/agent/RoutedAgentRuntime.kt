package fi.italeino.aidos.engine.agent

import dev.aidos.aidin.NodeId
import fi.italeino.aidos.engine.inference.Locality
import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration

/** Where the origin Engine may run a loop (RFC-0107 "Routing"): an ordered list of these. */
sealed interface AgentCandidate {
    data class Peer(val nodeId: NodeId) : AgentCandidate
    data object Local : AgentCandidate
}

/**
 * The virtual `aidos:agent` of RFC-0107: per turn, the first usable candidate wins.
 *
 * - A loop on a peer is placement `cluster`, so it is only a candidate when the turn's effective
 *   locality allows the cluster; `local_only` never reaches a peer, and falling back is always to a
 *   narrower placement (never wider).
 * - A peer is usable when it is connected and trusted for serving ([peerUsable]) and a client exists for it.
 * - If a peer cannot be reached when the turn starts, the next candidate is tried; a run, once started,
 *   stays where it started (the id is a reference to that host's run).
 */
class RoutedAgentRuntime(
    private val local: AgentRunApi,
    private val candidates: () -> List<AgentCandidate>,
    private val remoteFor: (NodeId) -> RemoteAgentClient?,
    private val peerUsable: (NodeId) -> Boolean = { true },
) : AgentRunApi {

    private val placement = ConcurrentHashMap<String, AgentRunApi>()

    override val events = local.events

    override fun info(): AgentInfo = local.info()

    override suspend fun start(owner: String, request: AgentTurnRequest): AgentTurnResponse {
        val locality = Locality.parse(request.locality ?: "local_only") ?: Locality.LOCAL_ONLY
        val clusterAllowed = locality != Locality.LOCAL_ONLY
        for (c in candidates()) {
            val backend: AgentRunApi = when (c) {
                AgentCandidate.Local -> local
                is AgentCandidate.Peer -> {
                    if (!clusterAllowed || !peerUsable(c.nodeId)) continue
                    remoteFor(c.nodeId) ?: continue
                }
            }
            try {
                val response = backend.start(owner, request)
                response.runId?.let { placement[it] = backend }
                return response
            } catch (e: AgentRequestException) {
                // An unreachable peer is a reason to try the next candidate, never to widen anything.
                if (backend !== local && e.httpStatus == 503) continue
                throw e
            }
        }
        // No candidate configured (or none usable): the local loop is the narrowest placement.
        return local.start(owner, request).also { r -> r.runId?.let { placement[it] = local } }
    }

    override suspend fun submit(owner: String, runId: String, results: List<ClientToolResult>, async: Boolean) =
        backendOf(runId).submit(owner, runId, results, async)

    override suspend fun get(owner: String, runId: String, since: Int?, waitFor: Duration) =
        backendOf(runId).get(owner, runId, since, waitFor)

    override fun list(owner: String): List<AgentTurnResponse> =
        (local.list(owner) + candidates().filterIsInstance<AgentCandidate.Peer>().mapNotNull { remoteFor(it.nodeId) }.flatMap { it.list(owner) })
            .distinctBy { it.runId }.sortedBy { it.runId }

    override fun cancel(owner: String, runId: String): Boolean = backendOf(runId).cancel(owner, runId).also { if (it) placement.remove(runId) }

    private fun backendOf(runId: String): AgentRunApi = placement[runId] ?: local
}
