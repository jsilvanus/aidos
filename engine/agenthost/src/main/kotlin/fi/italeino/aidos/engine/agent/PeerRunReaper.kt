package fi.italeino.aidos.engine.agent

import java.util.concurrent.ConcurrentHashMap
import kotlin.time.Duration
import kotlin.time.Duration.Companion.minutes

/**
 * RFC-0108 Phase 5 / RFC-0107 open question: what a hosted run does when its origin disappears.
 * A run owned by a peer (`peer:<nodeId>:<app>`) **continues for a bounded window after the peer's
 * link drops, resumable by `seq`, then is cancelled**. A peer that comes back inside the window
 * cancels the clock; a run that already finished is left for its retention to collect.
 *
 * Time is passed in ([check]) so the host's tick and the tests share one code path.
 */
class PeerRunReaper(
    private val runtime: AgentRuntime,
    private val window: Duration = 5.minutes,
    private val cancelled: (runId: String, peer: String) -> Unit = { _, _ -> },
) {
    private val lostSince = ConcurrentHashMap<String, Long>()

    /**
     * Report which peers are currently connected and the time. Returns the ids of runs cancelled by this call.
     * Call it whenever the peer list changes and on a slow tick.
     */
    fun check(connectedPeers: Set<String>, nowMillis: Long): List<String> {
        val live = runtime.listAll().filter { it.owner.startsWith(PREFIX) && !isTerminal(it.status) }
        val peersWithRuns = live.map { peerOf(it.owner) }.toSet()
        // A peer that is back, or has nothing running, has no clock.
        lostSince.keys.filter { it in connectedPeers || it !in peersWithRuns }.forEach(lostSince::remove)
        for (peer in peersWithRuns) {
            if (peer !in connectedPeers) lostSince.putIfAbsent(peer, nowMillis)
        }
        val cancelledIds = mutableListOf<String>()
        for (run in live) {
            val peer = peerOf(run.owner)
            val since = lostSince[peer] ?: continue
            if (nowMillis - since >= window.inWholeMilliseconds && runtime.cancelAny(run.runId)) {
                cancelledIds += run.runId
                cancelled(run.runId, peer)
            }
        }
        return cancelledIds
    }

    private fun peerOf(owner: String) = owner.removePrefix(PREFIX).substringBefore(':')

    private fun isTerminal(status: String) =
        status == AgentTurnResponse.COMPLETED || status == AgentTurnResponse.FAILED || status == AgentTurnResponse.CANCELLED

    private companion object {
        const val PREFIX = "peer:"
    }
}
